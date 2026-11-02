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
    reads = 0;
    closed.clear();
    messages.clear();
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

    std::printf("keyboard_diagnostics_host_tests: %d failed checks\n", g_failed_checks);
    return g_failed_checks == 0 ? 0 : 1;
}
