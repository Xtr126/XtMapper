#include "fake_jni_env.h"
#include <sys/ioctl.h>
#include <linux/input.h>
#include <thread>
#include <vector>

// Key translation is unrelated to diagnostics; all injected events use KEY_A.
#define LINUX_KEY_TABLE_H
static const char *get_linux_key_name(int) { return "KEY_A"; }
#include "../../main/cpp/keyboard_grab.cpp"

static bool pipeFails = false;
static bool noDevices = false;
static bool callbacks = false;
static bool grabFails = false;
static bool flipLock = false;
static int grabCalls = 0;
static int reads = 0;
static std::vector<int> closed;
static std::vector<std::u16string> messages;

std::vector<std::string> ListInputDevices() {
    return noDevices ? std::vector<std::string>{} : std::vector<std::string>{"fake-keyboard"};
}
bool HasEventType(int, unsigned int type) { return type == EV_KEY; }
bool HasSpecificKey(int, unsigned int key) { return key == KEY_A; }

extern "C" int __wrap_pipe2(int *fds, int) {
    if (pipeFails) {
        errno = EMFILE;
        return -1;
    }
    fds[0] = 40;
    fds[1] = 41;
    return 0;
}
extern "C" int __wrap_open(const char *, int, ...) { return 42; }
extern "C" int __wrap_ioctl(int, unsigned long request, ...) {
    if (request != EVIOCGRAB) {
        va_list args;
        va_start(args, request);
        std::strcpy(va_arg(args, char *), "fake keyboard");
        va_end(args);
        return 0;
    }
    grabCalls++;
    if (grabFails) {
        errno = EIO;
        return -1;
    }
    return 0;
}
extern "C" int __wrap_close(int fd) {
    closed.push_back(fd);
    return 0;
}
extern "C" int __wrap_poll(pollfd *fds, nfds_t count, int) {
    for (nfds_t i = 0; i < count; i++) fds[i].revents = fds[i].fd == 42 ? POLLIN : 0;
    return 1;
}
extern "C" ssize_t __wrap_read(int, void *buffer, size_t) {
    if (callbacks && reads++ < 3) {
        auto *event = static_cast<input_event *>(buffer);
        *event = {};
        event->type = EV_KEY;
        event->code = KEY_A;
        event->value = 1;
        return sizeof(*event);
    }
    if (flipLock) {
        // The next loop iteration sees a lock change mid-session; the read after it stops.
        g_ctx.keyboard_lock.store(0);
        flipLock = false;
        errno = EAGAIN;
        return -1;
    }
    g_ctx.running.store(false);
    errno = EAGAIN;
    return -1;
}

static void check_unlocked_submission() {
    messages.push_back(g_bridge.messageArg);
    std::thread probe([] {
        const bool device = g_ctx.lock.try_lock();
        if (device) g_ctx.lock.unlock();
        const bool lifecycle = g_lifecycle_mutex.try_lock();
        if (lifecycle) g_lifecycle_mutex.unlock();
        CHECK(device, "diagnostic submitted while Keyboard device lock is held");
        CHECK(lifecycle, "diagnostic submitted while Keyboard lifecycle lock is held");
    });
    probe.join();
}

static void reset() {
    g_bridge.reset();
    g_bridge.onStaticCall = check_unlocked_submission;
    pipeFails = false;
    noDevices = false;
    callbacks = false;
    flipLock = false;
    grabFails = false;
    grabCalls = 0;
    reads = 0;
    closed.clear();
    messages.clear();
    g_ctx.running.store(false);
    g_ctx.keyboard_lock.store(1);
    g_ctx.current_lock_state = 1;
    g_ctx.device_fds.clear();
    g_ctx.device_paths.clear();
    g_ctx.staged_submit.clear();
    g_ctx.wakeup_pipe[0] = -1;
    g_ctx.wakeup_pipe[1] = -1;
    g_ctx.inputServiceObj = nullptr;
    g_ctx.sendKeyboardEventMethod = nullptr;
}

int main() {
    JNIEnv *env = make_fake_env();
    CHECK(JNI_OnLoad(make_fake_vm(), nullptr) == JNI_VERSION_1_6, "library initialization failed");

    reset();
    pipeFails = true;
    CHECK(!Java_xtr_keymapper_server_InputService_startKeyboardGrab(env, fakeClassHandle()), "pipe failure admitted");
    CHECK(g_bridge.messageArg == u"keyboard grab wakeup pipe failed errno=24", "wrong pipe errno message");

    reset();
    noDevices = true;
    CHECK(!Java_xtr_keymapper_server_InputService_startKeyboardGrab(env, fakeClassHandle()), "empty scan admitted");
    CHECK(closed.size() == 2, "empty scan did not close both pipe fds");
    CHECK(g_bridge.staticCallCount == 1, "an empty scan must submit only the generic warning");

    reset();
    g_bridge.objectMethodLookupFails = true;
    CHECK(!Java_xtr_keymapper_server_InputService_startKeyboardGrab(env, fakeClassHandle()), "missing callback admitted");
    CHECK(g_bridge.messageArg == u"keyboard unable to resolve InputService.sendKeyboardEvent", "wrong callback message");
    CHECK(!g_bridge.pending, "callback lookup exception escaped");
    CHECK(closed.size() == 3 && g_ctx.device_fds.empty(), "lookup failure did not release input fds");
    CHECK(g_ctx.wakeup_pipe[0] == -1 && g_ctx.wakeup_pipe[1] == -1, "lookup failure left a live pipe");
    CHECK(g_bridge.deleteGlobalRefCount == 2, "lookup failure leaked callback refs");

    reset();
    callbacks = true;
    g_bridge.voidCallFails = true;
    g_ctx.running.store(true);
    g_ctx.device_fds = {42};
    g_ctx.device_paths = {"fake-keyboard"};
    g_ctx.inputServiceObj = fakeClassHandle();
    g_ctx.sendKeyboardEventMethod = fakeMethodHandle();
    run_keyboard_grab_loop();
    CHECK(g_bridge.voidCallCount == 3, "producer did not execute all injected callbacks");
    CHECK(g_bridge.staticCallCount == 2, "callback burst must report first plus exit residual only");
    CHECK(messages.size() == 2, "unexpected callback message count");
    if (messages.size() == 2) {
        CHECK(messages[0] == u"InputService.sendKeyboardEvent failed; suppressed=0", "wrong first failure text");
        CHECK(messages[1] == u"InputService.sendKeyboardEvent failures suppressed at worker exit count=2", "wrong residual text");
    }
    CHECK(!g_bridge.pending, "callback exception ownership changed");
    g_ctx.device_fds.clear();
    g_ctx.device_paths.clear();
    g_ctx.inputServiceObj = nullptr;
    g_ctx.sendKeyboardEventMethod = nullptr;

    reset();
    scanAndGrabKeyboardDevices();
    CHECK(g_ctx.device_fds.size() == 1 && g_ctx.device_paths.front() == "fake-keyboard",
          "initial scan lost the grabbed keyboard");
    CHECK(g_ctx.staged_submit.size() == 1 && g_ctx.staged_submit.front().first == XT_DIAG_INFO,
          "initial grab did not stage exactly one info");
    CHECK(g_ctx.staged_submit.front().second == "keyboard grabbed fake keyboard (fake-keyboard)",
          "wrong initial grab text");
    CHECK(grabCalls == 1 && closed.empty(), "initial scan grabbed calls mismatch");

    reset();
    grabFails = true;
    scanAndGrabKeyboardDevices();
    CHECK(g_ctx.device_fds.empty() && g_ctx.wakeup_pipe[0] == -1 && g_ctx.wakeup_pipe[1] == -1,
          "failed initial scan kept devices or pipes");
    CHECK(g_ctx.staged_submit.size() == 1 && g_ctx.staged_submit.front().first == XT_DIAG_ERROR,
          "initial grab failure did not stage exactly one error");
    CHECK(g_ctx.staged_submit.front().second == "keyboard initial EVIOCGRAB failed errno=5",
          "wrong initial grab failure text");
    CHECK(closed.size() == 3 && closed.front() == 42, "failed grab did not close its fd");

    reset();
    grabFails = true;
    CHECK(!Java_xtr_keymapper_server_InputService_startKeyboardGrab(env, fakeClassHandle()),
          "failed scan admitted");
    CHECK(g_bridge.staticCallCount == 2,
          "staged EVIOCGRAB failures plus the generic warning must reach the bridge");
    CHECK(messages.size() == 2, "unexpected failed-scan message count");
    if (messages.size() == 2) {
        CHECK(messages[0] == u"keyboard initial EVIOCGRAB failed errno=5",
              "staged failure was discarded by the empty branch");
        CHECK(messages[1] == u"keyboard grab found no keyboard devices",
              "staged failure broke the generic warning order");
    }

    reset();
    scanAndGrabKeyboardDevices();
    g_ctx.running.store(true);
    run_keyboard_grab_loop();
    CHECK(g_bridge.staticCallCount == 1, "worker did not submit the staged startup diagnostic");
    CHECK(g_bridge.messageArg == u"keyboard grabbed fake keyboard (fake-keyboard)", "wrong staged startup text");

    reset();
    flipLock = true;
    grabFails = true;
    g_ctx.running.store(true);
    g_ctx.device_fds = {42};
    g_ctx.device_paths = {"fake-keyboard"};
    run_keyboard_grab_loop();
    CHECK(g_bridge.staticCallCount == 1, "incomplete lock change did not submit once");
    CHECK(messages.size() == 1 &&
              messages[0] == u"keyboard grab lock change incomplete state=0 devices_failed=1 errno=5",
          "wrong lock-change failure text");
    CHECK(grabCalls == 2, "lock change did not re-grab every device");

    reset();
    flipLock = true;
    g_ctx.running.store(true);
    g_ctx.device_fds = {42};
    g_ctx.device_paths = {"fake-keyboard"};
    run_keyboard_grab_loop();
    CHECK(g_bridge.staticCallCount == 1, "lock change did not submit once");
    CHECK(messages.size() == 1 && messages[0] == u"keyboard grab lock changed to state 0",
          "successful lock change kept the info message");

    reset();
    g_ctx.running.store(true);
    g_bridge.utfCharsValue = "fake-keyboard";
    CHECK(Java_xtr_keymapper_server_InputService_grabKeyboardDevice(env, fakeClassHandle(), fakeStringHandle()),
          "dynamic grab was refused");
    CHECK(g_bridge.utfCharsCount == 1 && g_bridge.utfReleaseCount == 1, "dynamic grab leaked its device string");
    CHECK(g_bridge.priorityArg == XT_DIAG_INFO, "dynamic grab used the wrong priority");
    CHECK(g_bridge.messageArg == u"keyboard dynamically grabbed fake keyboard (fake-keyboard)",
          "wrong dynamic grab text");
    CHECK(g_ctx.device_paths.size() == 1 && g_ctx.device_paths.front() == "fake-keyboard",
          "dynamic grab did not register the device");

    reset();
    g_ctx.running.store(true);
    g_ctx.device_paths = {"fake-keyboard"};
    g_bridge.utfCharsValue = "fake-keyboard";
    CHECK(Java_xtr_keymapper_server_InputService_grabKeyboardDevice(env, fakeClassHandle(), fakeStringHandle()),
          "already grabbed device was refused");
    CHECK(g_bridge.staticCallCount == 0, "already grabbed device submitted a diagnostic");

    reset();
    grabFails = true;
    g_ctx.running.store(true);
    g_bridge.utfCharsValue = "fake-keyboard";
    CHECK(!Java_xtr_keymapper_server_InputService_grabKeyboardDevice(env, fakeClassHandle(), fakeStringHandle()),
          "failed dynamic grab was admitted");
    CHECK(g_bridge.priorityArg == XT_DIAG_ERROR, "dynamic failure used the wrong priority");
    CHECK(g_bridge.messageArg == u"keyboard dynamic grab failed errno=5", "wrong dynamic failure text");
    CHECK(closed.size() == 1, "failed dynamic grab did not close its fd");

    std::printf("keyboard_diagnostics_host_tests: %d failed checks\n", g_failed_checks);
    return g_failed_checks == 0 ? 0 : 1;
}
