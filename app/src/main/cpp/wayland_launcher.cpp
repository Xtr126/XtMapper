#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <grp.h>
#include <jni.h>
#include <poll.h>
#include <string>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#include <vector>

namespace {
constexpr char Tag[] = "xtmapper-wayland";
constexpr char AppProcess[] = "/system/bin/app_process";
constexpr uid_t ShellUid = 2000;

struct Fd {
    int value;
    ~Fd() { if (value >= 0) close(value); }
};

std::string stringFromJava(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string string(chars);
    env->ReleaseStringUTFChars(value, chars);
    return string;
}

jstring failure(JNIEnv* env, const char* operation, int error) {
    std::string message = std::string(operation) + " (errno " + std::to_string(error) + "): " +
            std::strerror(error);
    return env->NewStringUTF(message.c_str());
}

int connectSocket(int fd, const sockaddr_un& address) {
    if (connect(fd, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) == 0) return 0;
    if (errno != EINPROGRESS && errno != EINTR) return errno;
    pollfd descriptor{fd, POLLOUT, 0};
    const int ready = poll(&descriptor, 1, 2000);
    if (ready == 0) return ETIMEDOUT;
    if (ready < 0) return errno;
    int error = 0;
    socklen_t size = sizeof(error);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) != 0) return errno;
    return error;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_xtr_keymapper_wayland_WaylandLauncher_execServer(JNIEnv* env, jclass, jstring endpoint,
        jstring apk, jstring nativeLibraries, jobjectArray serverArgs) {
    if (geteuid() != 0) return failure(env, "Start the Waydroid launcher as root", EPERM);
    const std::string path = stringFromJava(env, endpoint);
    sockaddr_un address{};
    if (path.empty() || path.front() != '/' || path.size() >= sizeof(address.sun_path)) {
        return failure(env, "Invalid compositor socket path", EINVAL);
    }
    struct stat metadata{};
    if (stat(path.c_str(), &metadata) != 0) return failure(env, "Cannot inspect compositor socket", errno);
    if (!S_ISSOCK(metadata.st_mode)) return failure(env, "Invalid compositor socket", ENOTSOCK);
    address.sun_family = AF_UNIX;
    std::memcpy(address.sun_path, path.c_str(), path.size() + 1);
    Fd connection{socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0)};
    if (connection.value < 0) return failure(env, "Cannot create compositor socket", errno);
    const int error = connectSocket(connection.value, address);
    if (error) return failure(env, "Cannot connect to the Waydroid compositor", error);
    // Preserve only this new descriptor across exec. Android's existing inherited
    // standard streams remain available for launch diagnostics.
    Fd inherited{fcntl(connection.value, F_DUPFD, 100)};
    if (inherited.value < 0) return failure(env, "Cannot preserve compositor socket", errno);

    std::vector<std::string> arguments{
        AppProcess, "-Djava.class.path=" + stringFromJava(env, apk),
        "-Djava.library.path=" + stringFromJava(env, nativeLibraries), "/",
        "xtr.keymapper.server.RemoteServiceShell", "--native-wayland-fd=" + std::to_string(inherited.value),
    };
    for (jsize i = 0; i < env->GetArrayLength(serverArgs); ++i) {
        auto argument = static_cast<jstring>(env->GetObjectArrayElement(serverArgs, i));
        arguments.push_back(stringFromJava(env, argument));
        env->DeleteLocalRef(argument);
    }
    if (env->ExceptionCheck()) return nullptr;
    std::vector<char*> argv;
    for (std::string& argument : arguments) argv.push_back(argument.data());
    argv.push_back(nullptr);

    if (setgroups(0, nullptr) != 0 || setresgid(ShellUid, ShellUid, ShellUid) != 0 ||
            setresuid(ShellUid, ShellUid, ShellUid) != 0 ||
            prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        return failure(env, "Cannot drop to Android shell identity", errno);
    }
    __android_log_print(ANDROID_LOG_INFO, Tag, "Compositor connected; starting service as uid=%u", getuid());
    execv(AppProcess, argv.data());
    return failure(env, "Cannot start the Android shell service", errno);
}
