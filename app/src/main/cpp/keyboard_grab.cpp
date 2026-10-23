#include <jni.h>
#include <linux/input.h>
#include <linux/input-event-codes.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/poll.h>
#include <dirent.h>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
#include <cstring>
#include <cstdio>
#include <cassert>
#include "evdev_common.h"
#include "linux_key_table.h"

using std::string;
using std::vector;

struct KeyboardGrabContext {
    JavaVM *javaVM = nullptr;
    jclass inputServiceClz = nullptr;
    jobject inputServiceObj = nullptr;
    jmethodID sendKeyboardEventMethod = nullptr;

    std::mutex lock;
    std::atomic<bool> running{false};
    std::atomic<int> keyboard_lock{1}; // 1 = grab (block raw input), 0 = ungrab (passthrough)
    int current_lock_state = 1;

    vector<int> device_fds;
    vector<string> device_paths;
    std::thread worker_thread;
    int wakeup_pipe[2] = {-1, -1};
};

static KeyboardGrabContext g_ctx;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_ctx.javaVM = vm;
    return JNI_VERSION_1_6;
}

static bool isKeyboardDevice(int fd, const char *dev_name) {
    if (strncmp(dev_name, "x-virtual-", 10) == 0 || strcmp(dev_name, "uinput_nav") == 0) {
        return false;
    }
    if (!HasEventType(fd, EV_KEY)) {
        return false;
    }
    // Must have standard alphanumeric keyboard keys
    if (!HasSpecificKey(fd, KEY_A) || !HasSpecificKey(fd, KEY_SPACE) || !HasSpecificKey(fd, KEY_ENTER)) {
        return false;
    }
    return true;
}

static void run_keyboard_grab_loop() {
    JNIEnv *env = nullptr;
    if (g_ctx.javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        printf("E: Failed to attach thread for keyboard grab\n");
        return;
    }

    g_ctx.current_lock_state = g_ctx.keyboard_lock.load();
    {
        std::lock_guard<std::mutex> guard(g_ctx.lock);
        for (int fd : g_ctx.device_fds) {
            ioctl(fd, EVIOCGRAB, g_ctx.current_lock_state);
        }
    }

    vector<pollfd> poll_fds;

    while (g_ctx.running.load()) {
        poll_fds.clear();
        // wakeup pipe at index 0
        poll_fds.push_back({g_ctx.wakeup_pipe[0], POLLIN, 0});

        {
            std::lock_guard<std::mutex> guard(g_ctx.lock);
            int target_lock = g_ctx.keyboard_lock.load();
            if (target_lock != g_ctx.current_lock_state) {
                g_ctx.current_lock_state = target_lock;
                for (int fd : g_ctx.device_fds) {
                    ioctl(fd, EVIOCGRAB, target_lock);
                }
                printf("I: Keyboard grab lock changed to %d\n", target_lock);
            }

            for (int fd : g_ctx.device_fds) {
                poll_fds.push_back({fd, POLLIN, 0});
            }
        }

        int ret = poll(poll_fds.data(), poll_fds.size(), 1000);
        if (ret <= 0) continue;

        // Check wakeup pipe
        if (poll_fds[0].revents & POLLIN) {
            char buf[16];
            while (read(g_ctx.wakeup_pipe[0], buf, sizeof(buf)) > 0) {}
            if (!g_ctx.running.load()) break;
        }

        // Check device fds
        struct input_event ie;
        for (size_t i = 1; i < poll_fds.size(); i++) {
            if (poll_fds[i].revents & (POLLIN | POLLPRI)) {
                int fd = poll_fds[i].fd;
                while (read(fd, &ie, sizeof(ie)) == sizeof(struct input_event)) {
                    if (ie.type == EV_KEY) {
                        // ie.value: 0 = UP, 1 = DOWN, 2 = REPEAT
                        if (ie.value == 0 || ie.value == 1) {
                            const char *keyName = get_linux_key_name(ie.code);
                            if (keyName != nullptr && g_ctx.inputServiceObj != nullptr && g_ctx.sendKeyboardEventMethod != nullptr) {
                                jstring jKeyName = env->NewStringUTF(keyName);
                                env->CallVoidMethod(g_ctx.inputServiceObj, g_ctx.sendKeyboardEventMethod, jKeyName, ie.value);
                                env->DeleteLocalRef(jKeyName);
                            }
                        }
                    }
                }
            }
        }
    }

    g_ctx.javaVM->DetachCurrentThread();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_xtr_keymapper_server_InputService_startKeyboardGrab(JNIEnv *env, jobject thiz) {
    if (g_ctx.running.load()) {
        return JNI_TRUE;
    }

    if (pipe2(g_ctx.wakeup_pipe, O_CLOEXEC | O_NONBLOCK) < 0) {
        perror("pipe2 wakeup_pipe");
        return JNI_FALSE;
    }

    g_ctx.device_fds.clear();
    g_ctx.device_paths.clear();

    vector<string> allDevices = ListInputDevices();
    for (const auto &devPath : allDevices) {
        int fd = open(devPath.c_str(), O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;

        char name[128];
        memset(name, 0, sizeof(name));
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) {
            close(fd);
            continue;
        }

        if (isKeyboardDevice(fd, name)) {
            if (ioctl(fd, EVIOCGRAB, 1) == 0) {
                printf("I: Grabbed keyboard: %s (%s)\n", name, devPath.c_str());
                g_ctx.device_fds.push_back(fd);
                g_ctx.device_paths.push_back(devPath);
            } else {
                perror("EVIOCGRAB failed");
                close(fd);
            }
        } else {
            close(fd);
        }
    }

    if (g_ctx.device_fds.empty()) {
        printf("I: No keyboard devices found to grab\n");
        close(g_ctx.wakeup_pipe[0]);
        close(g_ctx.wakeup_pipe[1]);
        g_ctx.wakeup_pipe[0] = -1;
        g_ctx.wakeup_pipe[1] = -1;
        return JNI_FALSE;
    }

    jclass clz = env->GetObjectClass(thiz);
    g_ctx.inputServiceClz = (jclass) env->NewGlobalRef(clz);
    g_ctx.inputServiceObj = env->NewGlobalRef(thiz);
    g_ctx.sendKeyboardEventMethod = env->GetMethodID(clz, "sendKeyboardEvent", "(Ljava/lang/String;I)V");
    assert(g_ctx.sendKeyboardEventMethod != nullptr);

    g_ctx.running.store(true);
    g_ctx.keyboard_lock.store(1);
    g_ctx.worker_thread = std::thread(run_keyboard_grab_loop);

    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_stopKeyboardGrab(JNIEnv *env, jobject thiz) {
    if (!g_ctx.running.load()) {
        return;
    }

    g_ctx.running.store(false);
    if (g_ctx.wakeup_pipe[1] != -1) {
        write(g_ctx.wakeup_pipe[1], "q", 1);
    }

    if (g_ctx.worker_thread.joinable()) {
        g_ctx.worker_thread.join();
    }

    if (g_ctx.wakeup_pipe[0] != -1) close(g_ctx.wakeup_pipe[0]);
    if (g_ctx.wakeup_pipe[1] != -1) close(g_ctx.wakeup_pipe[1]);
    g_ctx.wakeup_pipe[0] = -1;
    g_ctx.wakeup_pipe[1] = -1;

    {
        std::lock_guard<std::mutex> guard(g_ctx.lock);
        for (int fd : g_ctx.device_fds) {
            ioctl(fd, EVIOCGRAB, 0);
            close(fd);
        }
        g_ctx.device_fds.clear();
        g_ctx.device_paths.clear();
    }

    if (g_ctx.inputServiceClz != nullptr) {
        env->DeleteGlobalRef(g_ctx.inputServiceClz);
        g_ctx.inputServiceClz = nullptr;
    }
    if (g_ctx.inputServiceObj != nullptr) {
        env->DeleteGlobalRef(g_ctx.inputServiceObj);
        g_ctx.inputServiceObj = nullptr;
    }
    g_ctx.sendKeyboardEventMethod = nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_setKeyboardGrabLock(JNIEnv *env, jobject thiz, jboolean lock) {
    int new_lock = lock ? 1 : 0;
    g_ctx.keyboard_lock.store(new_lock);
    if (g_ctx.wakeup_pipe[1] != -1) {
        write(g_ctx.wakeup_pipe[1], "u", 1);
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_xtr_keymapper_server_InputService_grabKeyboardDevice(JNIEnv *env, jobject thiz, jstring device) {
    if (!g_ctx.running.load()) {
        return JNI_FALSE;
    }
    const char *dev = env->GetStringUTFChars(device, nullptr);
    string devPath(dev);
    env->ReleaseStringUTFChars(device, dev);

    std::lock_guard<std::mutex> guard(g_ctx.lock);
    for (const auto &p : g_ctx.device_paths) {
        if (p == devPath) return JNI_TRUE;
    }

    int fd = open(devPath.c_str(), O_RDONLY | O_NONBLOCK);
    if (fd < 0) return JNI_FALSE;

    char name[128];
    memset(name, 0, sizeof(name));
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) {
        close(fd);
        return JNI_FALSE;
    }

    if (isKeyboardDevice(fd, name)) {
        if (ioctl(fd, EVIOCGRAB, g_ctx.keyboard_lock.load()) == 0) {
            printf("I: Dynamically grabbed keyboard: %s (%s)\n", name, devPath.c_str());
            g_ctx.device_fds.push_back(fd);
            g_ctx.device_paths.push_back(devPath);
            if (g_ctx.wakeup_pipe[1] != -1) {
                write(g_ctx.wakeup_pipe[1], "u", 1);
            }
            return JNI_TRUE;
        } else {
            close(fd);
            return JNI_FALSE;
        }
    } else {
        close(fd);
        return JNI_FALSE;
    }
}
