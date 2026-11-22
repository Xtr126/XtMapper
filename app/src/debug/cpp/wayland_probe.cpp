#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <linux/memfd.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#include <jni.h>
#define REPORT(...) __android_log_print(ANDROID_LOG_INFO, "xtmapper-wayland-probe", __VA_ARGS__)
#else
#include <cstdio>
#define REPORT(...) do { std::printf(__VA_ARGS__); std::putchar('\n'); } while (0)
#endif

namespace {
using Clock = std::chrono::steady_clock;
using Time = Clock::time_point;
using namespace std::chrono_literals;
using Words = std::vector<uint32_t>;

// The probe speaks only version 1 of these interfaces. See wayland.xml and
// stable/xdg-shell/xdg-shell.xml for object method and event opcode ordering.
enum Object : uint32_t {
    Display = 1, Registry, InitialSync, Compositor, Shm, Shell, Seat, GlobalsSync, Pointer, Keyboard,
};
enum SurfaceObject : uint32_t { Surface, XdgSurface, Toplevel, Pool, Buffer, HiddenSync };
constexpr uint32_t FirstSurface = Keyboard + 1;
constexpr uint32_t SecondSurface = FirstSurface + HiddenSync + 1;

struct Fd {
    int value;
    ~Fd() { if (value >= 0) close(value); }
};

void appendString(Words& words, const char* text) {
    const size_t length = std::strlen(text) + 1;
    words.push_back(length);
    const size_t start = words.size();
    words.resize(start + (length + 3) / 4);
    std::memcpy(words.data() + start, text, length);
}

// Returns 0 on timeout, 1 on readiness and -1 on failure.
int ready(int fd, short events, Time deadline) {
    for (;;) {
        const auto remaining = deadline - Clock::now();
        if (remaining <= Clock::duration::zero()) return 0;
        pollfd descriptor{fd, events, 0};
        const int result = poll(&descriptor, 1,
                std::chrono::ceil<std::chrono::milliseconds>(remaining).count());
        if (result < 0 && errno == EINTR) continue;
        if (result <= 0) return result;
        return (descriptor.revents & events) ? 1 : -1;
    }
}

int connectSocket(int fd, const sockaddr_un& address) {
    if (connect(fd, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) == 0) return 0;
    if (errno != EINPROGRESS && errno != EINTR) return errno;

    const int readiness = ready(fd, POLLOUT, Clock::now() + 2s);
    if (readiness == 0) return ETIMEDOUT;
    int error = 0;
    socklen_t size = sizeof(error);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) != 0) return errno;
    return error == 0 && readiness < 0 ? ECONNABORTED : error;
}

struct Client {
    int fd;
    uint32_t compositor = 0;
    uint32_t shm = 0;
    uint32_t shell = 0;
    uint32_t seat = 0;
    uint32_t seatCapabilities = 0;
    uint32_t base = 0;
    uint32_t completedSync = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    bool argb = false;
    bool committed = false;
    bool keyboardFocus = false;
    bool pointerFocus = false;
    bool keyEvent = false;
    bool buttonEvent = false;
    bool closed = false;

    bool send(uint32_t object, uint16_t opcode, const Words& args = {}, int passedFd = -1) {
        Words message{object, (static_cast<uint32_t>(args.size() + 2) * 4 << 16) | opcode};
        message.insert(message.end(), args.begin(), args.end());
        iovec data{message.data(), message.size() * 4};
        const auto deadline = Clock::now() + 2s;
        while (data.iov_len) {
            if (ready(fd, POLLOUT, deadline) != 1) return false;
            msghdr header{};
            header.msg_iov = &data;
            header.msg_iovlen = 1;
            alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
            if (passedFd >= 0) {
                header.msg_control = control;
                header.msg_controllen = sizeof(control);
                cmsghdr* rights = CMSG_FIRSTHDR(&header);
                rights->cmsg_level = SOL_SOCKET;
                rights->cmsg_type = SCM_RIGHTS;
                rights->cmsg_len = CMSG_LEN(sizeof(int));
                std::memcpy(CMSG_DATA(rights), &passedFd, sizeof(int));
            }
            const ssize_t count = sendmsg(fd, &header, MSG_NOSIGNAL);
            if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
            if (count <= 0) return false;
            passedFd = -1;
            data.iov_base = static_cast<char*>(data.iov_base) + count;
            data.iov_len -= count;
        }
        return true;
    }

    bool read(void* buffer, size_t length, Time deadline) {
        auto* bytes = static_cast<char*>(buffer);
        while (length) {
            if (ready(fd, POLLIN, deadline) != 1) return false;
            // No descriptor is needed, including the wl_keyboard keymap.
            // Linux closes SCM_RIGHTS descriptors omitted from the receive buffer.
            const ssize_t count = recv(fd, bytes, length, 0);
            if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
            if (count <= 0) return false;
            bytes += count;
            length -= count;
        }
        return true;
    }

    bool bind(uint32_t name, const char* interface, uint32_t object) {
        Words args{name};
        appendString(args, interface);
        args.insert(args.end(), {1, object});
        return send(Registry, 0, args); // bind
    }

    bool global(const Words& args) {
        if (args.size() < 4 || args[1] == 0) return false;
        const size_t stringWords = (static_cast<size_t>(args[1]) + 3) / 4;
        if (stringWords != args.size() - 3) return false;
        const auto* name = reinterpret_cast<const char*>(&args[2]);
        if (name[args[1] - 1] != '\0' || args.back() == 0) return false;
        REPORT("global %s version=%u", name, args.back());
        if (!compositor && !std::strcmp(name, "wl_compositor")) compositor = args[0];
        if (!shm && !std::strcmp(name, "wl_shm")) shm = args[0];
        if (!shell && !std::strcmp(name, "xdg_wm_base")) shell = args[0];
        if (!seat && !std::strcmp(name, "wl_seat")) seat = args[0];
        return true;
    }

    bool attach() {
        if (!width || !height) {
            width = 640;
            height = 480;
        }
        if (width > 8192 || height > 8192 || uint64_t(width) * height * 4 > 128u * 1024 * 1024) {
            REPORT("unsupported buffer dimensions %ux%u", width, height);
            return false;
        }
        const uint32_t size = width * height * 4;
        Fd memory{static_cast<int>(syscall(SYS_memfd_create, "xtmapper-wayland-probe", MFD_CLOEXEC))};
        if (memory.value < 0 || ftruncate(memory.value, size) != 0) {
            REPORT("shared memory failed errno=%d", errno);
            return false;
        }
        // The new zero-filled ARGB8888 buffer is transparent. Leave the default
        // input region intact and retain the buffer object until surface teardown.
        committed = send(Shm, 0, {base + Pool, size}, memory.value)
                && send(base + Pool, 0, {base + Buffer, 0, width, height, width * 4, 0})
                && send(base + Pool, 1) // destroy pool
                && send(base + Surface, 1, {base + Buffer, 0, 0}) // attach
                && send(base + Surface, 2, {0, 0, width, height}) // damage
                && send(base + Surface, 6); // commit
        if (committed) REPORT("buffer committed %ux%u", width, height);
        return committed;
    }

    bool event(uint32_t object, uint16_t opcode, const Words& args) {
        if (object == Display && opcode == 0) {
            const char* detail = args.size() > 3 ? reinterpret_cast<const char*>(&args[3]) : "";
            REPORT("compositor protocol error object=%u code=%u detail=%.*s",
                    args.size() >= 2 ? args[0] : 0, args.size() >= 2 ? args[1] : 0,
                    args.size() > 3 ? int((args.size() - 3) * 4) : 0, detail);
            return false;
        }
        if (object == Registry && opcode == 0) return global(args);
        if (object == Registry && opcode == 1 && args.size() == 1 &&
                (args[0] == compositor || args[0] == shm || args[0] == shell || args[0] == seat)) {
            REPORT("required global removed name=%u", args[0]);
            return false;
        }
        if ((object == InitialSync || object == GlobalsSync || (base && object == base + HiddenSync))
                && opcode == 0) completedSync = object;
        if (object == Shell && opcode == 0 && args.size() == 1) return send(Shell, 3, args); // pong
        if (object == Shm && opcode == 0 && args.size() == 1 && args[0] == 0) argb = true;
        if (object == Seat && opcode == 0 && args.size() == 1) {
            seatCapabilities = args[0];
            REPORT("seat capabilities=%u", seatCapabilities);
        }
        if (base == 0) return true;
        if (object == base + Toplevel && opcode == 0 && args.size() >= 3) {
            REPORT("toplevel configure width=%d height=%d", int32_t(args[0]), int32_t(args[1]));
            if (!committed) {
                width = std::max(0, int32_t(args[0]));
                height = std::max(0, int32_t(args[1]));
            }
        }
        if (object == base + Toplevel && opcode == 1) closed = true;
        if (object == base + XdgSurface && opcode == 0 && args.size() == 1) {
            return send(object, 4, args) && (committed || attach()); // ack_configure
        }
        if ((object == Pointer || object == Keyboard) && args.size() >= 2) {
            const bool keyboard = object == Keyboard;
            if (args[1] == base + Surface && (opcode == (keyboard ? 1 : 0)
                    || opcode == (keyboard ? 2 : 1))) {
                const bool entered = opcode == (keyboard ? 1 : 0);
                REPORT("%s focus %s", keyboard ? "keyboard" : "pointer", entered ? "enter" : "leave");
                if (entered) (keyboard ? keyboardFocus : pointerFocus) = true;
            }
            if (keyboard && opcode == 3 && !keyEvent) {
                keyEvent = true;
                REPORT("keyboard event received");
            }
            if (!keyboard && opcode == 3 && !buttonEvent) {
                buttonEvent = true;
                REPORT("pointer button event received");
            }
        }
        return true;
    }

    bool dispatch(Time deadline) {
        uint32_t header[2];
        if (!read(header, sizeof(header), deadline)) return false;
        const uint32_t size = header[1] >> 16;
        if (size < 8 || size % 4 != 0) return false;
        Words args((size - 8) / 4);
        return read(args.data(), size - 8, deadline) && event(header[0], header[1], args);
    }

    bool sync(uint32_t callback) {
        if (!send(Display, 0, {callback})) return false;
        const auto deadline = Clock::now() + 3s;
        while (completedSync != callback) {
            if (!dispatch(deadline)) {
                REPORT("round trip failed callback=%u", callback);
                return false;
            }
        }
        return true;
    }

    bool setup() {
        if (!send(Display, 1, {Registry}) || !sync(InitialSync)) return false; // get_registry
        if (!compositor || !shm || !shell || !seat) {
            REPORT("missing required global compositor=%u shm=%u xdg_wm_base=%u seat=%u",
                    compositor, shm, shell, seat);
            return false;
        }
        if (!bind(compositor, "wl_compositor", Compositor) || !bind(shm, "wl_shm", Shm)
                || !bind(shell, "xdg_wm_base", Shell) || !bind(seat, "wl_seat", Seat)
                || !sync(GlobalsSync)) return false;
        if (!argb || (seatCapabilities & 3) != 3) {
            REPORT("required capabilities unavailable argb=%d seat=%u", argb, seatCapabilities);
            return false;
        }
        return send(Seat, 0, {Pointer}) && send(Seat, 1, {Keyboard});
    }

    bool observe(Time deadline) {
        while (!closed) {
            const int readiness = ready(fd, POLLIN, deadline);
            if (readiness == 0) return true;
            if (readiness < 0 || !dispatch(Clock::now() + 1s)) return false;
        }
        REPORT("compositor requested close");
        return false;
    }

    bool show(uint32_t id, std::chrono::milliseconds duration, bool& focused) {
        base = id;
        width = height = 0;
        committed = keyboardFocus = pointerFocus = keyEvent = buttonEvent = false;
        Words title;
        appendString(title, "XtMapper Wayland Probe");
        Words appId;
        appendString(appId, "org.xtmapper.WaylandProbe");
        REPORT("show surface=%u", base);
        if (!send(Compositor, 0, {base + Surface}) // create_surface
                || !send(Shell, 2, {base + XdgSurface, base + Surface}) // get_xdg_surface
                || !send(base + XdgSurface, 1, {base + Toplevel}) // get_toplevel
                || !send(base + Toplevel, 2, title) // set_title
                || !send(base + Toplevel, 3, appId) // set_app_id
                || !send(base + Toplevel, 9) // set_maximized
                || !send(base + Surface, 6) // initial empty commit
                || !observe(Clock::now() + duration)) return false;
        REPORT("show result committed=%d keyboard_enter=%d pointer_enter=%d key=%d button=%d",
                committed, keyboardFocus, pointerFocus, keyEvent, buttonEvent);
        focused = keyboardFocus && pointerFocus;
        if (!committed) return false;
        if (!send(base + Toplevel, 0) || !send(base + XdgSurface, 0)
                || !send(base + Surface, 0) || !send(base + Buffer, 0)
                || !sync(base + HiddenSync)) return false;
        REPORT("surface destroyed; observe focus and placement in Waydroid");
        base = 0;
        return true;
    }
};

int run(const char* endpoint, int visibleMillis) {
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    if (endpoint[0] != '/' || std::strlen(endpoint) >= sizeof(address.sun_path)
            || visibleMillis < 1 || visibleMillis > 60000) {
        REPORT("invalid endpoint or duration");
        return 2;
    }
    std::strcpy(address.sun_path, endpoint);
    struct stat metadata{};
    if (stat(endpoint, &metadata) == 0) {
        REPORT("socket path=%s mode=%o owner=%u:%u uid=%u gid=%u", endpoint,
                metadata.st_mode, metadata.st_uid, metadata.st_gid, getuid(), getgid());
        if (!S_ISSOCK(metadata.st_mode)) return 2;
    } else {
        REPORT("endpoint stat failed path=%s errno=%d uid=%u gid=%u", endpoint, errno, getuid(), getgid());
    }
    Fd connection{socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0)};
    const int error = connection.value < 0 ? errno : connectSocket(connection.value, address);
    if (error != 0) {
        REPORT("socket connect failed errno=%d (%s)", error, std::strerror(error));
        return 3;
    }
    REPORT("socket connected");
    Client client{connection.value};
    if (!client.setup()) return 4;
    const std::chrono::milliseconds duration(visibleMillis);
    bool firstFocused = false;
    bool secondFocused = false;
    if (!client.show(FirstSurface, duration, firstFocused) || !client.observe(Clock::now() + 5s)
            || !client.show(SecondSurface, duration, secondFocused)) return 5;
    return firstFocused && secondFocused ? 0 : 6;
}
} // namespace

#ifdef __ANDROID__
extern "C" JNIEXPORT jint JNICALL
Java_xtr_keymapper_wayland_WaylandProbe_probe(JNIEnv* env, jclass, jstring endpoint, jint duration) {
    if (!endpoint) return 2;
    const char* path = env->GetStringUTFChars(endpoint, nullptr);
    if (!path) return 2;
    const int result = run(path, duration);
    env->ReleaseStringUTFChars(endpoint, path);
    return result;
}
#else
int main(int argc, char** argv) {
    return argc == 2 ? run(argv[1], 10000) : 2;
}
#endif
