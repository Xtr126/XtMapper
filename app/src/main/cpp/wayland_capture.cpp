#include <algorithm>
#include <android/log.h>
#include <array>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <jni.h>
#include <linux/memfd.h>
#include <memory>
#include <mutex>
#include <poll.h>
#include <pthread.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

#include "wayland-client.h"
#include "xdg-shell-client.h"
#include "pointer-constraints-client.h"
#include "relative-pointer-client.h"

namespace {
constexpr char Tag[] = "xtmapper-wayland";
constexpr uint32_t RequiredSeatCapabilities = WL_SEAT_CAPABILITY_POINTER | WL_SEAT_CAPABILITY_KEYBOARD;
using Clock = std::chrono::steady_clock;
using namespace std::chrono_literals;

enum State {
    Connected = 1, Mapped = 2, KeyboardFocus = 4, PointerFocus = 8,
    PointerLocked = 16, RelativeAvailable = 32,
};
enum Input { Key = 1, Button, Absolute, Relative, Scroll };

struct Fd {
    explicit Fd(int descriptor = -1) : value(descriptor) {}
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
    ~Fd() { reset(); }
    void reset(int descriptor = -1) {
        if (value >= 0) close(value);
        value = descriptor;
    }

    int value;
};

bool isConnectedUnixStream(int fd) {
    sockaddr_un address{};
    socklen_t addressSize = sizeof(address);
    int type = 0;
    socklen_t typeSize = sizeof(type);
    return getpeername(fd, reinterpret_cast<sockaddr*>(&address), &addressSize) == 0
            && address.sun_family == AF_UNIX
            && getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &typeSize) == 0
            && type == SOCK_STREAM;
}

struct Command {
    bool visible = false;
    bool lockPointer = false;
    jlong generation = 0;
    jint width = 0;
    jint height = 0;
};

struct Global {
    const wl_interface& interface;
    uint32_t maxVersion = 1;
    uint32_t name = 0;
    uint32_t version = 0;
};

struct Overlay {
    wl_surface* surface = nullptr;
    xdg_surface* xdgSurface = nullptr;
    xdg_toplevel* toplevel = nullptr;
    zwp_locked_pointer_v1* lock = nullptr;
    uint32_t width = 0;
    uint32_t height = 0;
    bool mapped = false;
    bool pointerFocus = false;
    bool keyboardFocus = false;
    bool locked = false;
    double pointerX = 0;
    double pointerY = 0;
    Clock::time_point configureDeadline;
};

struct WheelAxis {
    double value = 0;
    double discrete = 0;
    bool hasDiscrete = false;
};

struct Session {
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    jobject callback = nullptr;
    jmethodID onState = nullptr;
    jmethodID onEvent = nullptr;
    pthread_t thread{};
    Fd socket;
    Fd wake{eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK)};
    std::mutex mutex;
    Command desired;
    bool pendingCommand = false;
    bool stopping = false;

    // Owned by the dispatch thread after start.
    Command active;
    wl_display* display = nullptr;
    wl_registry* registry = nullptr;
    wl_callback* registrySync = nullptr;
    wl_callback* capabilitySync = nullptr;
    wl_compositor* compositor = nullptr;
    wl_shm* shm = nullptr;
    xdg_wm_base* shell = nullptr;
    wl_seat* seat = nullptr;
    zwp_relative_pointer_manager_v1* relativeManager = nullptr;
    zwp_pointer_constraints_v1* constraints = nullptr;
    wl_pointer* pointer = nullptr;
    wl_keyboard* keyboard = nullptr;
    zwp_relative_pointer_v1* relativePointer = nullptr;
    uint32_t seatCapabilities = 0;
    bool argb = false;
    bool connected = false;
    bool callbackFailed = false;
    bool protocolFailed = false;
    int lastState = -1;
    jlong lastGeneration = -1;
    Global compositorGlobal{wl_compositor_interface};
    Global shmGlobal{wl_shm_interface};
    Global shellGlobal{xdg_wm_base_interface};
    Global seatGlobal{wl_seat_interface, 5};
    Global relativeGlobal{zwp_relative_pointer_manager_v1_interface};
    Global constraintsGlobal{zwp_pointer_constraints_v1_interface};
    std::array<Global*, 6> globals{
        &compositorGlobal, &shmGlobal, &shellGlobal, &seatGlobal, &relativeGlobal, &constraintsGlobal
    };
    Overlay overlay;
    std::array<wl_buffer*, 4> buffers{};
    std::array<WheelAxis, 2> wheel{};
    double wheelX = 0;
    double wheelY = 0;
    double relativeX = 0;
    double relativeY = 0;

    explicit Session(int fd) : socket(fd) {}

    int state() const {
        int result = connected ? Connected : 0;
        if (connected && relativePointer && constraints) result |= RelativeAvailable;
        if (overlay.mapped) result |= Mapped;
        if (overlay.pointerFocus) result |= PointerFocus;
        if (overlay.keyboardFocus) result |= KeyboardFocus;
        if (overlay.locked) result |= PointerLocked;
        return result;
    }

    void checkCallback() {
        if (!env->ExceptionCheck()) return;
        env->ExceptionDescribe();
        env->ExceptionClear();
        callbackFailed = true;
    }

    void notifyState() {
        const int current = state();
        if (callbackFailed || (current == lastState && active.generation == lastGeneration)) return;
        lastState = current;
        lastGeneration = active.generation;
        __android_log_print(ANDROID_LOG_INFO, Tag, "Capture generation=%lld state=%d",
                static_cast<long long>(lastGeneration), current);
        env->CallVoidMethod(callback, onState, active.generation, current);
        checkCallback();
    }

    bool capturing() const {
        return !protocolFailed && !callbackFailed && overlay.mapped && overlay.pointerFocus &&
                overlay.keyboardFocus;
    }

    void notifyEvent(Input type, uint32_t code, double x = 0, double y = 0) {
        if (!capturing()) return;
        env->CallVoidMethod(callback, onEvent, active.generation, static_cast<jint>(type),
                static_cast<jint>(code), x, y);
        checkCallback();
    }

    void notifyAbsolute() {
        if (active.lockPointer || !overlay.width || !overlay.height) return;
        notifyEvent(Absolute, 0, std::clamp(overlay.pointerX / overlay.width, 0.0, 1.0),
                std::clamp(overlay.pointerY / overlay.height, 0.0, 1.0));
    }

    void resetDeltas() {
        wheel = {};
        wheelX = wheelY = relativeX = relativeY = 0;
    }

    void accumulate(Input type, double x, double y, double& remainderX, double& remainderY) {
        if (!capturing()) return;
        remainderX += x;
        remainderY += y;
        const double wholeX = std::trunc(remainderX);
        const double wholeY = std::trunc(remainderY);
        remainderX -= wholeX;
        remainderY -= wholeY;
        if (wholeX || wholeY) notifyEvent(type, 0, wholeX, wholeY);
    }

    template <typename Interface>
    Interface* bind(const Global& global) {
        if (!global.version) return nullptr;
        return static_cast<Interface*>(wl_registry_bind(registry, global.name, &global.interface,
                std::min(global.version, global.maxVersion)));
    }

    static void announceGlobal(void* data, wl_registry*, uint32_t name, const char* interface,
            uint32_t version) {
        auto& self = *static_cast<Session*>(data);
        for (Global* global : self.globals) {
            if (!global->version && !std::strcmp(global->interface.name, interface)) {
                global->name = name;
                global->version = version;
            }
        }
    }

    static void removeGlobal(void* data, wl_registry*, uint32_t name) {
        auto& self = *static_cast<Session*>(data);
        for (const Global* global : self.globals) {
            if (global->version && global->name == name) {
                __android_log_print(ANDROID_LOG_ERROR, Tag, "Wayland global removed: %s",
                        global->interface.name);
                self.protocolFailed = true;
                return;
            }
        }
    }

    static void syncDone(void* data, wl_callback* sync, uint32_t) {
        auto& self = *static_cast<Session*>(data);
        const bool initialSync = sync == self.registrySync;
        wl_callback_destroy(sync);
        if (initialSync) {
            self.registrySync = nullptr;
            self.protocolFailed = !self.bindGlobals();
        } else {
            self.capabilitySync = nullptr;
            self.protocolFailed = !self.initializeDevices();
        }
    }

    bool bindGlobals() {
        if (protocolFailed) return false;
        compositor = bind<wl_compositor>(compositorGlobal);
        shm = bind<wl_shm>(shmGlobal);
        shell = bind<xdg_wm_base>(shellGlobal);
        seat = bind<wl_seat>(seatGlobal);
        relativeManager = bind<zwp_relative_pointer_manager_v1>(relativeGlobal);
        constraints = bind<zwp_pointer_constraints_v1>(constraintsGlobal);
        if (!compositor || !shm || !shell || !seat) {
            __android_log_print(ANDROID_LOG_ERROR, Tag, "Missing required Wayland globals");
            return false;
        }
        static const wl_shm_listener shmListener{[](void* data, wl_shm*, uint32_t format) {
            if (format == WL_SHM_FORMAT_ARGB8888) static_cast<Session*>(data)->argb = true;
        }};
        static const xdg_wm_base_listener shellListener{[](void*, xdg_wm_base* base, uint32_t serial) {
            xdg_wm_base_pong(base, serial);
        }};
        static const wl_seat_listener seatListener{
            [](void* data, wl_seat*, uint32_t capabilities) {
                auto& self = *static_cast<Session*>(data);
                self.seatCapabilities = capabilities;
                if (self.connected &&
                        (capabilities & RequiredSeatCapabilities) != RequiredSeatCapabilities) {
                    __android_log_print(ANDROID_LOG_ERROR, Tag, "Wayland seat lost keyboard or pointer");
                    self.protocolFailed = true;
                }
            },
            [](void*, wl_seat*, const char*) {},
        };
        if (wl_shm_add_listener(shm, &shmListener, this) ||
                xdg_wm_base_add_listener(shell, &shellListener, this) ||
                wl_seat_add_listener(seat, &seatListener, this)) return false;
        capabilitySync = wl_display_sync(display);
        return capabilitySync && !wl_callback_add_listener(capabilitySync, &syncListener, this);
    }

    bool initializeDevices() {
        if (protocolFailed) return false;
        if (!argb || (seatCapabilities & RequiredSeatCapabilities) != RequiredSeatCapabilities) {
            __android_log_print(ANDROID_LOG_ERROR, Tag, "Missing ARGB, keyboard or pointer capability");
            return false;
        }
        pointer = wl_seat_get_pointer(seat);
        keyboard = wl_seat_get_keyboard(seat);
        if (!pointer || !keyboard || wl_pointer_add_listener(pointer, &pointerListener, this)
                || wl_keyboard_add_listener(keyboard, &keyboardListener, this)) return false;
        if (relativeManager && constraints) {
            relativePointer = zwp_relative_pointer_manager_v1_get_relative_pointer(relativeManager, pointer);
            static const zwp_relative_pointer_v1_listener relativeListener{
                [](void* data, zwp_relative_pointer_v1*, uint32_t, uint32_t, wl_fixed_t, wl_fixed_t,
                        wl_fixed_t dx, wl_fixed_t dy) {
                    auto& self = *static_cast<Session*>(data);
                    if (self.overlay.locked) {
                        self.accumulate(Relative, wl_fixed_to_double(dx), wl_fixed_to_double(dy),
                                self.relativeX, self.relativeY);
                    }
                },
            };
            if (!relativePointer ||
                    zwp_relative_pointer_v1_add_listener(relativePointer, &relativeListener, this))
                return false;
        }
        connected = true;
        notifyState();
        return !active.visible || show();
    }

    bool attach(uint32_t width, uint32_t height) {
        auto slot = std::find(buffers.begin(), buffers.end(), nullptr);
        if (slot == buffers.end()) return false;
        const uint32_t size = width * height * 4;
        Fd memory(static_cast<int>(syscall(SYS_memfd_create, "xtmapper-wayland", MFD_CLOEXEC)));
        if (memory.value < 0 || ftruncate(memory.value, size) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, Tag, "Cannot prepare overlay buffer: errno=%d", errno);
            return false;
        }
        // A new memfd reads as zero, producing a transparent ARGB buffer.
        wl_shm_pool* pool = wl_shm_create_pool(shm, memory.value, size);
        if (!pool) return false;
        wl_buffer* buffer = wl_shm_pool_create_buffer(pool, 0, width, height, width * 4,
                WL_SHM_FORMAT_ARGB8888);
        wl_shm_pool_destroy(pool);
        if (!buffer) return false;
        *slot = buffer;
        static const wl_buffer_listener listener{[](void* data, wl_buffer* released) {
            auto& self = *static_cast<Session*>(data);
            auto bufferSlot = std::find(self.buffers.begin(), self.buffers.end(), released);
            wl_buffer_destroy(released);
            if (bufferSlot != self.buffers.end()) *bufferSlot = nullptr;
        }};
        if (wl_buffer_add_listener(buffer, &listener, this)) return false;
        overlay.width = width;
        overlay.height = height;
        xdg_surface_set_window_geometry(overlay.xdgSurface, 0, 0, width, height);
        wl_surface_attach(overlay.surface, buffer, 0, 0);
        wl_surface_damage(overlay.surface, 0, 0, width, height);
        wl_surface_commit(overlay.surface);
        return true;
    }

    bool show() {
        if (active.lockPointer && (!relativePointer || !constraints)) {
            __android_log_print(ANDROID_LOG_ERROR, Tag, "Pointer lock protocols unavailable");
            return true;
        }
        if (active.width <= 0 || active.height <= 0 || active.width > 8192 ||
                active.height > 8192 || uint64_t(active.width) * active.height * 4 > 128u * 1024 * 1024) {
            __android_log_print(ANDROID_LOG_ERROR, Tag, "Unsupported overlay size %dx%d",
                    active.width, active.height);
            return true;
        }
        static const xdg_surface_listener surfaceListener{
            [](void* data, xdg_surface* surface, uint32_t serial) {
                auto& self = *static_cast<Session*>(data);
                if (self.protocolFailed) return;
                xdg_surface_ack_configure(surface, serial);
                if (self.overlay.mapped) return;
                if (!self.attach(self.active.width, self.active.height)) {
                    self.protocolFailed = true;
                    return;
                }
                self.overlay.mapped = true;
                self.notifyState();
            },
        };
        static const xdg_toplevel_listener toplevelListener{
            [](void*, xdg_toplevel*, int32_t, int32_t, wl_array*) {},
            [](void* data, xdg_toplevel*) { static_cast<Session*>(data)->protocolFailed = true; },
        };
        overlay.surface = wl_compositor_create_surface(compositor);
        if (!overlay.surface) return false;
        overlay.xdgSurface = xdg_wm_base_get_xdg_surface(shell, overlay.surface);
        if (!overlay.xdgSurface ||
                xdg_surface_add_listener(overlay.xdgSurface, &surfaceListener, this)) return false;
        overlay.toplevel = xdg_surface_get_toplevel(overlay.xdgSurface);
        if (!overlay.toplevel ||
                xdg_toplevel_add_listener(overlay.toplevel, &toplevelListener, this)) return false;
        xdg_toplevel_set_title(overlay.toplevel, "XtMapper");
        xdg_toplevel_set_app_id(overlay.toplevel, "org.xtmapper.WaylandCapture");
        xdg_toplevel_set_max_size(overlay.toplevel, active.width, active.height);
        xdg_toplevel_set_min_size(overlay.toplevel, active.width, active.height);
        wl_surface_commit(overlay.surface);
        overlay.configureDeadline = Clock::now() + 4s;
        return true;
    }

    void hide() {
        if (overlay.lock) zwp_locked_pointer_v1_destroy(overlay.lock);
        if (overlay.toplevel) xdg_toplevel_destroy(overlay.toplevel);
        if (overlay.xdgSurface) xdg_surface_destroy(overlay.xdgSurface);
        if (overlay.surface) wl_surface_destroy(overlay.surface);
        overlay = {};
        resetDeltas();
    }

    void syncPointerLock() {
        const bool wantsLock = active.lockPointer && capturing() && relativePointer && constraints;
        if (wantsLock && !overlay.lock) {
            overlay.lock = zwp_pointer_constraints_v1_lock_pointer(constraints, overlay.surface,
                    pointer, nullptr, ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_PERSISTENT);
            static const zwp_locked_pointer_v1_listener listener{
                [](void* data, zwp_locked_pointer_v1*) {
                    auto& self = *static_cast<Session*>(data);
                    self.overlay.locked = self.overlay.pointerFocus;
                    self.notifyState();
                },
                [](void* data, zwp_locked_pointer_v1*) {
                    auto& self = *static_cast<Session*>(data);
                    self.overlay.locked = false;
                    self.resetDeltas();
                    self.notifyState();
                },
            };
            if (!overlay.lock || zwp_locked_pointer_v1_add_listener(overlay.lock, &listener, this))
                protocolFailed = true;
        }
        if (!wantsLock && overlay.lock) {
            zwp_locked_pointer_v1_destroy(overlay.lock);
            overlay.lock = nullptr;
            overlay.locked = false;
            resetDeltas();
        }
    }

    void emitWheel() {
        const WheelAxis& horizontal = wheel[WL_POINTER_AXIS_HORIZONTAL_SCROLL];
        const WheelAxis& vertical = wheel[WL_POINTER_AXIS_VERTICAL_SCROLL];
        const double x = horizontal.hasDiscrete ? horizontal.discrete : horizontal.value / 10;
        const double y = vertical.hasDiscrete ? vertical.discrete : vertical.value / 10;
        accumulate(Scroll, x, y, wheelX, wheelY);
        wheel = {};
    }

    inline static const wl_callback_listener syncListener{syncDone};
    inline static const wl_registry_listener registryListener{announceGlobal, removeGlobal};
    static const wl_pointer_listener pointerListener;
    static const wl_keyboard_listener keyboardListener;

    void wakeDispatch() {
        const uint64_t value = 1;
        while (write(wake.value, &value, sizeof(value)) < 0 && errno == EINTR) {}
    }

    bool applyCommand() {
        uint64_t value;
        while (read(wake.value, &value, sizeof(value)) < 0 && errno == EINTR) {}
        Command requested;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (stopping) return false;
            if (!pendingCommand) return true;
            requested = desired;
            pendingCommand = false;
        }
        if (requested.generation == active.generation && requested.visible == active.visible &&
                requested.lockPointer == active.lockPointer && requested.width == active.width &&
                requested.height == active.height) return true;
        if (requested.generation == active.generation && requested.visible && active.visible &&
                requested.width == active.width && requested.height == active.height && overlay.surface) {
            active = requested;
            syncPointerLock();
            notifyState();
            notifyAbsolute();
            return true;
        }
        active = requested;
        hide();
        notifyState();
        return !connected || !active.visible || show();
    }

    void disconnect() {
        if (!display) return;
        hide();
        for (wl_buffer* buffer : buffers) {
            if (buffer) wl_buffer_destroy(buffer);
        }
        if (relativePointer) zwp_relative_pointer_v1_destroy(relativePointer);
        if (constraints) zwp_pointer_constraints_v1_destroy(constraints);
        if (relativeManager) zwp_relative_pointer_manager_v1_destroy(relativeManager);
        if (pointer) wl_pointer_destroy(pointer);
        if (keyboard) wl_keyboard_destroy(keyboard);
        if (seat) wl_seat_destroy(seat);
        if (shell) xdg_wm_base_destroy(shell);
        if (shm) wl_shm_destroy(shm);
        if (compositor) wl_compositor_destroy(compositor);
        if (capabilitySync) wl_callback_destroy(capabilitySync);
        if (registrySync) wl_callback_destroy(registrySync);
        if (registry) wl_registry_destroy(registry);
        wl_display_disconnect(display);
    }

    bool dispatch() {
        const int fd = socket.value;
        socket.value = -1;
        // libwayland owns fd from here, including when connection setup fails.
        display = wl_display_connect_to_fd(fd);
        if (!display) return false;
        wl_display_set_max_buffer_size(display, 64 * 1024);
        registry = wl_display_get_registry(display);
        registrySync = wl_display_sync(display);
        if (!registry || !registrySync || wl_registry_add_listener(registry, &registryListener, this)
                || wl_callback_add_listener(registrySync, &syncListener, this)) return false;

        const auto setupDeadline = Clock::now() + 4s;
        while (!callbackFailed && !protocolFailed) {
            if (wl_display_dispatch_pending(display) < 0 || protocolFailed || callbackFailed)
                return false;
            int timeout = -1;
            if (!connected || (overlay.surface && !overlay.mapped)) {
                const auto deadline = connected ? overlay.configureDeadline : setupDeadline;
                const auto remaining = std::chrono::ceil<std::chrono::milliseconds>(deadline - Clock::now());
                if (remaining <= 0ms) {
                    __android_log_print(ANDROID_LOG_ERROR, Tag, "Wayland setup or configure timed out");
                    return false;
                }
                timeout = remaining.count();
            }

            if (wl_display_prepare_read(display) != 0) continue;
            const int flushResult = wl_display_flush(display);
            const bool wantsWrite = flushResult < 0 && errno == EAGAIN;
            if (flushResult < 0 && !wantsWrite) {
                wl_display_cancel_read(display);
                return false;
            }
            pollfd fds[]{
                {wake.value, POLLIN, 0},
                {fd, static_cast<short>(POLLIN | (wantsWrite ? POLLOUT : 0)), 0},
            };
            const int ready = poll(fds, 2, timeout);
            const int pollError = errno;
            // Process hide/stop before queued input when both descriptors wake.
            if (fds[0].revents) {
                wl_display_cancel_read(display);
                if (!applyCommand()) return false;
                continue;
            }
            if (ready > 0 && (fds[1].revents & POLLIN)) {
                if (wl_display_read_events(display) < 0) return false;
            } else {
                wl_display_cancel_read(display);
            }
            if (ready < 0 && pollError != EINTR) return false;
            if (fds[1].revents & (POLLHUP | POLLERR | POLLNVAL)) return false;
        }
        return false;
    }

    static void* run(void* parameter) {
        auto& session = *static_cast<Session*>(parameter);
        if (session.vm->AttachCurrentThread(&session.env, nullptr) != JNI_OK) {
            __android_log_print(ANDROID_LOG_ERROR, Tag, "Cannot attach Wayland dispatch thread to Java");
            session.socket.reset();
            return nullptr;
        }
        session.dispatch();
        if (session.display) {
            const int error = wl_display_get_error(session.display);
            if (error == EPROTO) {
                const wl_interface* interface = nullptr;
                uint32_t objectId = 0;
                const uint32_t code = wl_display_get_protocol_error(session.display, &interface, &objectId);
                __android_log_print(ANDROID_LOG_ERROR, Tag, "Protocol error interface=%s object=%u code=%u",
                        interface ? interface->name : "(unknown)", objectId, code);
            }
        }
        session.disconnect();
        session.overlay = {};
        session.connected = false;
        session.notifyState();
        session.vm->DetachCurrentThread();
        return nullptr;
    }
};

const wl_pointer_listener Session::pointerListener{
    [](void* data, wl_pointer*, uint32_t, wl_surface* surface, wl_fixed_t x, wl_fixed_t y) {
        auto& self = *static_cast<Session*>(data);
        if (!surface || surface != self.overlay.surface) return;
        self.overlay.pointerFocus = true;
        self.overlay.pointerX = wl_fixed_to_double(x);
        self.overlay.pointerY = wl_fixed_to_double(y);
        self.syncPointerLock();
        self.notifyState();
        self.notifyAbsolute();
    },
    [](void* data, wl_pointer*, uint32_t, wl_surface* surface) {
        auto& self = *static_cast<Session*>(data);
        if (!surface || surface != self.overlay.surface) return;
        self.overlay.pointerFocus = self.overlay.locked = false;
        self.resetDeltas();
        self.syncPointerLock();
        self.notifyState();
    },
    [](void* data, wl_pointer*, uint32_t, wl_fixed_t x, wl_fixed_t y) {
        auto& self = *static_cast<Session*>(data);
        if (!self.overlay.pointerFocus) return;
        self.overlay.pointerX = wl_fixed_to_double(x);
        self.overlay.pointerY = wl_fixed_to_double(y);
        self.notifyAbsolute();
    },
    [](void* data, wl_pointer*, uint32_t, uint32_t, uint32_t button, uint32_t state) {
        if (state <= WL_POINTER_BUTTON_STATE_PRESSED)
            static_cast<Session*>(data)->notifyEvent(Button, button, state);
    },
    [](void* data, wl_pointer* pointer, uint32_t, uint32_t axis, wl_fixed_t value) {
        auto& self = *static_cast<Session*>(data);
        if (axis >= self.wheel.size()) return;
        self.wheel[axis].value += wl_fixed_to_double(value);
        if (wl_pointer_get_version(pointer) < WL_POINTER_FRAME_SINCE_VERSION) self.emitWheel();
    },
    [](void* data, wl_pointer*) { static_cast<Session*>(data)->emitWheel(); },
    [](void*, wl_pointer*, uint32_t) {},
    [](void*, wl_pointer*, uint32_t, uint32_t) {},
    [](void* data, wl_pointer*, uint32_t axis, int32_t discrete) {
        auto& self = *static_cast<Session*>(data);
        if (axis >= self.wheel.size()) return;
        self.wheel[axis].discrete += discrete;
        self.wheel[axis].hasDiscrete = true;
    },
};

const wl_keyboard_listener Session::keyboardListener{
    [](void*, wl_keyboard*, uint32_t, int32_t fd, uint32_t) {
        // Physical evdev codes require no keymap.
        close(fd);
    },
    [](void* data, wl_keyboard*, uint32_t, wl_surface* surface, wl_array*) {
        auto& self = *static_cast<Session*>(data);
        if (!surface || surface != self.overlay.surface) return;
        // Keys already down when focus enters do not start a mapped action.
        self.overlay.keyboardFocus = true;
        self.syncPointerLock();
        self.notifyState();
        self.notifyAbsolute();
    },
    [](void* data, wl_keyboard*, uint32_t, wl_surface* surface) {
        auto& self = *static_cast<Session*>(data);
        if (!surface || surface != self.overlay.surface) return;
        self.overlay.keyboardFocus = false;
        self.resetDeltas();
        self.syncPointerLock();
        self.notifyState();
    },
    [](void* data, wl_keyboard*, uint32_t, uint32_t, uint32_t key, uint32_t state) {
        if (state <= WL_KEYBOARD_KEY_STATE_PRESSED)
            static_cast<Session*>(data)->notifyEvent(Key, key, state);
    },
    [](void*, wl_keyboard*, uint32_t, uint32_t, uint32_t, uint32_t, uint32_t) {},
    [](void*, wl_keyboard*, int32_t, int32_t) {},
};

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_xtr_keymapper_wayland_WaylandCaptureNative_start(JNIEnv* env, jclass, jint fd, jobject callback) {
    if (fd < 3) return 0;
    auto session = std::make_unique<Session>(fd);
    if (!callback || session->wake.value < 0 || !isConnectedUnixStream(fd) ||
            fcntl(fd, F_SETFD, FD_CLOEXEC) != 0) return 0;
    const int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) return 0;
    if (env->GetJavaVM(&session->vm) != JNI_OK) return 0;
    jclass callbackClass = env->GetObjectClass(callback);
    session->onState = env->GetMethodID(callbackClass, "onState", "(JI)V");
    if (!session->onState) return 0;
    session->onEvent = env->GetMethodID(callbackClass, "onEvent", "(JIIDD)V");
    if (!session->onEvent) return 0;
    session->callback = env->NewGlobalRef(callback);
    if (!session->callback) return 0;
    if (pthread_create(&session->thread, nullptr, Session::run, session.get()) != 0) {
        env->DeleteGlobalRef(session->callback);
        return 0;
    }
    return reinterpret_cast<jlong>(session.release());
}

extern "C" JNIEXPORT void JNICALL
Java_xtr_keymapper_wayland_WaylandCaptureNative_request(JNIEnv*, jclass, jlong handle, jboolean visible,
        jlong generation, jboolean lockPointer, jint width, jint height) {
    auto& session = *reinterpret_cast<Session*>(handle);
    {
        std::lock_guard<std::mutex> lock(session.mutex);
        session.desired = {visible == JNI_TRUE, lockPointer == JNI_TRUE, generation, width, height};
        session.pendingCommand = true;
    }
    session.wakeDispatch();
}

extern "C" JNIEXPORT void JNICALL
Java_xtr_keymapper_wayland_WaylandCaptureNative_stop(JNIEnv* env, jclass, jlong handle) {
    std::unique_ptr<Session> session(reinterpret_cast<Session*>(handle));
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        session->stopping = true;
    }
    session->wakeDispatch();
    pthread_join(session->thread, nullptr);
    env->DeleteGlobalRef(session->callback);
}
