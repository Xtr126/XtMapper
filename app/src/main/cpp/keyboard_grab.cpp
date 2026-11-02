#include <jni.h>
#include <linux/input.h>
#include <linux/input-event-codes.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/poll.h>
#include <dirent.h>
#include <string>
#include <utility>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
#include <cstring>
#include <cstdio>
#include <cassert>
#include <cerrno>
#include "evdev_common.h"
#include "linux_key_table.h"

extern "C" {
#include "xt_diagnostics.h"
}

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

    // Diagnostics staged until the worker runs: XtDiag_Submit must not execute while
    // the producer holds any of its locks.
    vector<std::pair<int, string>> staged_submit;
};

static KeyboardGrabContext g_ctx;
static std::mutex g_lifecycle_mutex;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    g_ctx.javaVM = vm;
    XtDiag_Init(env); // a failed diagnostics bridge must not fail library load
    return JNI_VERSION_1_6;
}

static bool isKeyboardDevice(int fd, const char *dev_name) {
    if (strncmp(dev_name, "x-virtual-", 10) == 0 || strcmp(dev_name, "uinput_nav") == 0) {
        return false;
    }
    if (!HasEventType(fd, EV_KEY)) {
        return false;
    }
    // Pointer devices expose mouse buttons; grabbing one would freeze it.
    if (HasSpecificKey(fd, BTN_MOUSE) || HasSpecificKey(fd, BTN_TOUCH)) {
        return false;
    }
    return HasSpecificKey(fd, KEY_A) || HasSpecificKey(fd, KEY_SPACE) || HasSpecificKey(fd, KEY_ENTER) ||
           HasSpecificKey(fd, KEY_1) || HasSpecificKey(fd, KEY_0) ||
           HasSpecificKey(fd, KEY_KP0) || HasSpecificKey(fd, KEY_KPENTER);
}

static void run_keyboard_grab_loop() {
    JNIEnv *env = nullptr;
    if (g_ctx.javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        printf("E: Failed to attach thread for keyboard grab\n");
        return;
    }

    g_ctx.current_lock_state = g_ctx.keyboard_lock.load();
    XtDiagEventGroup callback_failures;
    XtDiagEvent_Init(&callback_failures);
    vector<std::pair<int, string>> staged;
    {
        std::lock_guard<std::mutex> guard(g_ctx.lock);
        staged.swap(g_ctx.staged_submit);
        for (int fd : g_ctx.device_fds) {
            ioctl(fd, EVIOCGRAB, g_ctx.current_lock_state);
        }
    }
    // Startup outcomes wait until no producer lock is held before crossing the bridge.
    for (const auto &item : staged) {
        XtDiag_Submit(env, item.first, XT_DIAG_SOURCE_KEYBOARD, item.second.c_str(), -1);
    }

    vector<pollfd> poll_fds;

    while (g_ctx.running.load()) {
        poll_fds.clear();
        bool lock_changed = false;
        int new_lock_state = 0;
        int lock_change_failures = 0;
        int lock_change_errno = 0;
        int pipe_fd = -1;

        {
            std::lock_guard<std::mutex> guard(g_ctx.lock);
            pipe_fd = g_ctx.wakeup_pipe[0];
            if (pipe_fd != -1) {
                poll_fds.push_back({pipe_fd, POLLIN, 0});
            }

            int target_lock = g_ctx.keyboard_lock.load();
            if (target_lock != g_ctx.current_lock_state) {
                g_ctx.current_lock_state = target_lock;
                for (int fd : g_ctx.device_fds) {
                    if (ioctl(fd, EVIOCGRAB, target_lock) != 0) {
                        // Every device counts; the last errno is the message's sample.
                        lock_change_failures++;
                        lock_change_errno = errno;
                    }
                }
                lock_changed = true;
                new_lock_state = target_lock;
            }

            for (int fd : g_ctx.device_fds) {
                poll_fds.push_back({fd, POLLIN, 0});
            }
        }

        if (lock_changed) {
            if (lock_change_failures == 0) {
                XtDiag_Submit(env, XT_DIAG_INFO, XT_DIAG_SOURCE_KEYBOARD,
                              "keyboard grab lock changed to state ", new_lock_state);
            } else {
                char detail[128];
                snprintf(detail, sizeof(detail),
                         "keyboard grab lock change incomplete state=%d devices_failed=%d errno=%d",
                         new_lock_state, lock_change_failures, lock_change_errno);
                XtDiag_Submit(env, XT_DIAG_ERROR, XT_DIAG_SOURCE_KEYBOARD, detail, -1);
            }
        }

        if (poll_fds.empty()) {
            usleep(50000);
            continue;
        }

        int ret = poll(poll_fds.data(), poll_fds.size(), 1000);
        if (ret <= 0) continue;

        size_t dev_start_idx = 0;
        if (pipe_fd != -1 && !poll_fds.empty() && poll_fds[0].fd == pipe_fd) {
            dev_start_idx = 1;
            if (poll_fds[0].revents & POLLIN) {
                char buf[16];
                while (read(pipe_fd, buf, sizeof(buf)) > 0) {}
                if (!g_ctx.running.load()) break;
            }
            if (poll_fds[0].revents & (POLLHUP | POLLERR | POLLNVAL)) {
                if (!g_ctx.running.load()) break;
            }
        }

        // Check device fds
        struct input_event ie;
        vector<int> dead_fds;

        for (size_t i = dev_start_idx; i < poll_fds.size(); i++) {
            int fd = poll_fds[i].fd;
            short revents = poll_fds[i].revents;

            if (revents & (POLLHUP | POLLERR | POLLNVAL)) {
                dead_fds.push_back(fd);
                continue;
            }

            if (revents & (POLLIN | POLLPRI)) {
                bool dev_dead = false;
                while (true) {
                    ssize_t bytes = read(fd, &ie, sizeof(ie));
                    if (bytes == sizeof(struct input_event)) {
                        if (ie.type == EV_KEY) {
                            // ie.value: 0 = UP, 1 = DOWN, 2 = REPEAT
                            if (ie.value == 0 || ie.value == 1) {
                                const char *keyName = get_linux_key_name(ie.code);
                                if (keyName != nullptr && g_ctx.inputServiceObj != nullptr && g_ctx.sendKeyboardEventMethod != nullptr) {
                                    jstring jKeyName = env->NewStringUTF(keyName);
                                    if (jKeyName != nullptr) {
                                        env->CallVoidMethod(g_ctx.inputServiceObj, g_ctx.sendKeyboardEventMethod, jKeyName, ie.value);
                                        if (env->ExceptionCheck()) {
                                            env->ExceptionClear();
                                            XtDiag_SubmitEvent(env, XT_DIAG_ERROR, XT_DIAG_SOURCE_KEYBOARD,
                                                               &callback_failures,
                                                               "InputService.sendKeyboardEvent failed; suppressed=");
                                        }
                                        env->DeleteLocalRef(jKeyName);
                                    }
                                }
                            }
                        }
                    } else if (bytes < 0) {
                        if (errno != EAGAIN && errno != EWOULDBLOCK) {
                            dev_dead = true;
                        }
                        break;
                    } else {
                        // EOF - device disconnected
                        dev_dead = true;
                        break;
                    }
                }
                if (dev_dead) {
                    dead_fds.push_back(fd);
                }
            }
        }

        if (!dead_fds.empty()) {
            int remaining_devices = -1;
            {
                std::lock_guard<std::mutex> guard(g_ctx.lock);
                for (int dead_fd : dead_fds) {
                    for (size_t j = 0; j < g_ctx.device_fds.size(); j++) {
                        if (g_ctx.device_fds[j] == dead_fd) {
                            ioctl(dead_fd, EVIOCGRAB, 0);
                            close(dead_fd);
                            g_ctx.device_fds.erase(g_ctx.device_fds.begin() + j);
                            g_ctx.device_paths.erase(g_ctx.device_paths.begin() + j);
                            break;
                        }
                    }
                }
                remaining_devices = (int) g_ctx.device_fds.size();
            }
            XtDiag_Submit(env, XT_DIAG_WARN, XT_DIAG_SOURCE_KEYBOARD,
                          "keyboard device removed; remaining=", remaining_devices);
        }
    }

    uint64_t residual = XtDiagEvent_TakeResidual(&callback_failures);
    if (residual > 0) {
        XtDiag_Submit(env, XT_DIAG_ERROR, XT_DIAG_SOURCE_KEYBOARD,
                      "InputService.sendKeyboardEvent failures suppressed at worker exit count=", (long)residual);
    }
    g_ctx.javaVM->DetachCurrentThread();
}

// Scans /dev/input and EVIOCGRABs every keyboard device found; caller owns g_lifecycle_mutex.
// Outcomes stage until the worker drains g_ctx.staged_submit, so submissions never run
// with a producer lock held.
static void scanAndGrabKeyboardDevices() {
    std::lock_guard<std::mutex> guard(g_ctx.lock);
    g_ctx.device_fds.clear();
    g_ctx.device_paths.clear();
    g_ctx.staged_submit.clear();

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
                char message[256];
                snprintf(message, sizeof(message), "keyboard grabbed %s (%s)",
                         name, devPath.c_str());
                g_ctx.staged_submit.push_back({XT_DIAG_INFO, message});
                g_ctx.device_fds.push_back(fd);
                g_ctx.device_paths.push_back(devPath);
            } else {
                char message[96];
                snprintf(message, sizeof(message), "keyboard initial EVIOCGRAB failed errno=%d", errno);
                g_ctx.staged_submit.push_back({XT_DIAG_ERROR, message});
                close(fd);
            }
        } else {
            close(fd);
        }
    }

    if (g_ctx.device_fds.empty()) {
        close(g_ctx.wakeup_pipe[0]);
        close(g_ctx.wakeup_pipe[1]);
        g_ctx.wakeup_pipe[0] = -1;
        g_ctx.wakeup_pipe[1] = -1;
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_xtr_keymapper_server_InputService_startKeyboardGrab(JNIEnv *env, jobject thiz) {
    std::unique_lock<std::mutex> lifecycle_lock(g_lifecycle_mutex);
    if (g_ctx.running.load()) {
        return JNI_TRUE;
    }

    if (pipe2(g_ctx.wakeup_pipe, O_CLOEXEC | O_NONBLOCK) < 0) {
        int pipe_errno = errno;
        lifecycle_lock.unlock();
        XtDiag_Submit(env, XT_DIAG_ERROR, XT_DIAG_SOURCE_KEYBOARD,
                      "keyboard grab wakeup pipe failed errno=", pipe_errno);
        return JNI_FALSE;
    }

    scanAndGrabKeyboardDevices();

    if (g_ctx.device_fds.empty()) {
        lifecycle_lock.unlock();
        XtDiag_Submit(env, XT_DIAG_WARN, XT_DIAG_SOURCE_KEYBOARD,
                      "keyboard grab found no keyboard devices", -1);
        return JNI_FALSE;
    }

    jclass clz = env->GetObjectClass(thiz);
    g_ctx.inputServiceClz = (jclass) env->NewGlobalRef(clz);
    g_ctx.inputServiceObj = env->NewGlobalRef(thiz);
    g_ctx.sendKeyboardEventMethod = env->GetMethodID(clz, "sendKeyboardEvent", "(Ljava/lang/String;I)V");
    env->DeleteLocalRef(clz);

    if (g_ctx.sendKeyboardEventMethod == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteGlobalRef(g_ctx.inputServiceClz);
        env->DeleteGlobalRef(g_ctx.inputServiceObj);
        g_ctx.inputServiceClz = nullptr;
        g_ctx.inputServiceObj = nullptr;

        {
            std::lock_guard<std::mutex> guard(g_ctx.lock);
            if (g_ctx.wakeup_pipe[0] != -1) close(g_ctx.wakeup_pipe[0]);
            if (g_ctx.wakeup_pipe[1] != -1) close(g_ctx.wakeup_pipe[1]);
            g_ctx.wakeup_pipe[0] = -1;
            g_ctx.wakeup_pipe[1] = -1;

            for (int fd : g_ctx.device_fds) {
                ioctl(fd, EVIOCGRAB, 0);
                close(fd);
            }
            g_ctx.device_fds.clear();
            g_ctx.device_paths.clear();
        }
        lifecycle_lock.unlock();
        XtDiag_Submit(env, XT_DIAG_ERROR, XT_DIAG_SOURCE_KEYBOARD,
                      "keyboard unable to resolve InputService.sendKeyboardEvent", -1);
        return JNI_FALSE;
    }

    g_ctx.running.store(true);
    g_ctx.keyboard_lock.store(1);
    g_ctx.worker_thread = std::thread(run_keyboard_grab_loop);

    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_stopKeyboardGrab(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lifecycle_guard(g_lifecycle_mutex);
    if (!g_ctx.running.load()) {
        return;
    }

    g_ctx.running.store(false);
    {
        std::lock_guard<std::mutex> guard(g_ctx.lock);
        if (g_ctx.wakeup_pipe[1] != -1) {
            write(g_ctx.wakeup_pipe[1], "q", 1);
        }
    }

    if (g_ctx.worker_thread.joinable()) {
        g_ctx.worker_thread.join();
    }

    {
        std::lock_guard<std::mutex> guard(g_ctx.lock);
        if (g_ctx.wakeup_pipe[0] != -1) close(g_ctx.wakeup_pipe[0]);
        if (g_ctx.wakeup_pipe[1] != -1) close(g_ctx.wakeup_pipe[1]);
        g_ctx.wakeup_pipe[0] = -1;
        g_ctx.wakeup_pipe[1] = -1;

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
    std::lock_guard<std::mutex> guard(g_ctx.lock);
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
    if (dev == nullptr) {
        return JNI_FALSE;
    }
    string devPath(dev);
    env->ReleaseStringUTFChars(device, dev);

    // The device name must outlive the mutex: the message is submitted after the guard.
    char name[128];
    name[0] = '\0';
    bool dynamic_grabbed = false;
    int dynamic_errno = 0; /* 0 = EVIOCGRAB succeeded or was never reached */

    jboolean result = JNI_FALSE;
    {
        std::lock_guard<std::mutex> guard(g_ctx.lock);
        if (!g_ctx.running.load()) {
            return JNI_FALSE;
        }
        for (const auto &p : g_ctx.device_paths) {
            if (p == devPath) return JNI_TRUE;
        }

        int fd = open(devPath.c_str(), O_RDONLY | O_NONBLOCK);
        if (fd < 0) return JNI_FALSE;

        memset(name, 0, sizeof(name));
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) {
            close(fd);
            return JNI_FALSE;
        }

        if (isKeyboardDevice(fd, name)) {
            if (ioctl(fd, EVIOCGRAB, g_ctx.keyboard_lock.load()) == 0) {
                g_ctx.device_fds.push_back(fd);
                g_ctx.device_paths.push_back(devPath);
                if (g_ctx.wakeup_pipe[1] != -1) {
                    write(g_ctx.wakeup_pipe[1], "u", 1);
                }
                dynamic_grabbed = true;
                result = JNI_TRUE;
            } else {
                dynamic_errno = errno;
                close(fd);
            }
        } else {
            close(fd);
        }
    }

    // XtDiag_Submit must run outside native locks.
    if (dynamic_grabbed) {
        char message[256];
        snprintf(message, sizeof(message), "keyboard dynamically grabbed %s (%s)",
                 name, devPath.c_str());
        XtDiag_Submit(env, XT_DIAG_INFO, XT_DIAG_SOURCE_KEYBOARD, message, -1);
    } else if (dynamic_errno != 0) {
        XtDiag_Submit(env, XT_DIAG_ERROR, XT_DIAG_SOURCE_KEYBOARD,
                      "keyboard dynamic grab failed errno=", dynamic_errno);
    }
    return result;
}
