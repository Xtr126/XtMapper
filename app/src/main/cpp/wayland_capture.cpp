#include <algorithm>
#include <android/log.h>
#include <array>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <deque>
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
#include <vector>

#include "wayland-wire.h"

namespace {
namespace wire = wayland_wire;
namespace lockProtocol = wire::zwp_pointer_constraints_v1;
namespace lockedPointer = wire::zwp_locked_pointer_v1;
namespace relativeManagerProtocol = wire::zwp_relative_pointer_manager_v1;
namespace relativeProtocol = wire::zwp_relative_pointer_v1;

constexpr char Tag[] = "xtmapper-wayland";
constexpr uint32_t Display = 1;
constexpr uint32_t MaxMessageSize = 65532;
constexpr size_t MaxQueueSize = 128;
constexpr uint32_t RequiredSeatCapabilities = 3;
using Words = std::vector<uint32_t>;
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

Words encodeString(const char* text) {
    const size_t length = std::strlen(text) + 1;
    Words words(1 + (length + 3) / 4);
    words[0] = length;
    std::memcpy(words.data() + 1, text, length);
    return words;
}

double fixed(uint32_t value) {
    return static_cast<int32_t>(value) / 256.0;
}

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

struct Message {
    Words words;
    size_t sent = 0;
    Fd fd;

    Message(uint32_t id, uint32_t opcode, const Words& args)
            : words{id, (static_cast<uint32_t>(args.size() + 2) * 4 << 16) | opcode} {
        words.insert(words.end(), args.begin(), args.end());
    }
};

struct Command {
    bool visible = false;
    bool lockPointer = false;
    jlong generation = 0;
    jint width = 0;
    jint height = 0;
};

// Core requests and events follow wayland.xml. Use version 1 except for
// wl_seat + wl_pointer (up to 5, for framed and discrete scrolling).
// Extension opcodes follow the version 1 xdg-shell, relative-pointer and
// pointer-constraints XML. The client uses no incoming descriptor contents.
struct Global {
    const char* name;
    uint32_t maxVersion = 1;
    uint32_t advertisedName = 0;
    uint32_t advertisedVersion = 0;
    uint32_t id = 0;
};

struct Overlay {
    uint32_t surface = 0;
    uint32_t xdgSurface = 0;
    uint32_t toplevel = 0;
    uint32_t lock = 0;
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
    std::deque<Message> output;
    std::vector<uint8_t> input;
    std::vector<uint32_t> freeIds;
    uint32_t nextId = Display + 1;
    uint32_t registry = 0;
    uint32_t registrySync = 0;
    uint32_t capabilitySync = 0;
    uint32_t pointer = 0;
    uint32_t keyboard = 0;
    uint32_t relativePointer = 0;
    uint32_t seatCapabilities = 0;
    bool argb = false;
    bool connected = false;
    bool callbackFailed = false;
    int lastState = -1;
    jlong lastGeneration = -1;
    Global compositor{"wl_compositor"};
    Global shm{"wl_shm"};
    Global shell{"xdg_wm_base"};
    Global seat{"wl_seat", 5};
    Global relativeManager{"zwp_relative_pointer_manager_v1"};
    Global constraints{"zwp_pointer_constraints_v1"};
    std::array<Global*, 6> globals{&compositor, &shm, &shell, &seat, &relativeManager, &constraints};
    Overlay overlay;
    std::vector<uint32_t> buffers;
    std::array<WheelAxis, 2> wheel{};
    double wheelX = 0;
    double wheelY = 0;
    double relativeX = 0;
    double relativeY = 0;

    explicit Session(int fd) : socket(fd) {}

    uint32_t newId() {
        if (freeIds.empty()) return nextId++;
        const uint32_t id = freeIds.back();
        freeIds.pop_back();
        return id;
    }

    bool send(uint32_t id, uint32_t opcode, const Words& args = {}, int fd = -1) {
        if (output.size() >= MaxQueueSize) return false;
        Message& message = output.emplace_back(id, opcode, args);
        if (fd < 0) return true;
        message.fd.reset(fcntl(fd, F_DUPFD_CLOEXEC, 0));
        return message.fd.value >= 0;
    }

    bool flush() {
        while (!output.empty()) {
            Message& message = output.front();
            auto* data = reinterpret_cast<uint8_t*>(message.words.data());
            iovec bytes{data + message.sent, message.words.size() * 4 - message.sent};
            msghdr header{};
            header.msg_iov = &bytes;
            header.msg_iovlen = 1;
            alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
            if (message.fd.value >= 0) {
                header.msg_control = control;
                header.msg_controllen = sizeof(control);
                cmsghdr* rights = CMSG_FIRSTHDR(&header);
                rights->cmsg_level = SOL_SOCKET;
                rights->cmsg_type = SCM_RIGHTS;
                rights->cmsg_len = CMSG_LEN(sizeof(int));
                std::memcpy(CMSG_DATA(rights), &message.fd.value, sizeof(int));
            }
            const ssize_t count = sendmsg(socket.value, &header, MSG_NOSIGNAL);
            if (count < 0 && errno == EAGAIN) return true;
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) return false;
            message.fd.reset();
            message.sent += count;
            if (message.sent == message.words.size() * 4) output.pop_front();
        }
        return true;
    }

    int state() const {
        int result = connected ? Connected : 0;
        if (connected && relativePointer && constraints.id) result |= RelativeAvailable;
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
        return !callbackFailed && overlay.mapped && overlay.pointerFocus && overlay.keyboardFocus;
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

    bool bind(Global& global) {
        if (!global.advertisedName) return true;
        global.id = newId();
        Words args{global.advertisedName};
        const Words name = encodeString(global.name);
        args.insert(args.end(), name.begin(), name.end());
        args.insert(args.end(), {std::min(global.advertisedVersion, global.maxVersion), global.id});
        return send(registry, wire::wl_registry::request::bind, args);
    }

    bool announceGlobal(const Words& args) {
        if (args.size() < 4 || args[1] == 0 || args.back() == 0) return false;
        if ((static_cast<size_t>(args[1]) + 3) / 4 != args.size() - 3) return false;
        const auto* name = reinterpret_cast<const char*>(args.data() + 2);
        if (name[args[1] - 1]) return false;
        for (Global* global : globals) {
            if (!global->advertisedName && !std::strcmp(global->name, name)) {
                global->advertisedName = args[0];
                global->advertisedVersion = args.back();
            }
        }
        return true;
    }

    bool bindGlobals() {
        if (!compositor.advertisedName || !shm.advertisedName || !shell.advertisedName ||
                !seat.advertisedName) {
            __android_log_print(ANDROID_LOG_ERROR, Tag, "Missing required Wayland globals");
            return false;
        }
        for (Global* global : globals) {
            if (!bind(*global)) return false;
        }
        capabilitySync = newId();
        return send(Display, wire::wl_display::request::sync, {capabilitySync});
    }

    bool initializeDevices() {
        if (!argb || (seatCapabilities & RequiredSeatCapabilities) != RequiredSeatCapabilities) {
            __android_log_print(ANDROID_LOG_ERROR, Tag, "Missing ARGB, keyboard or pointer capability");
            return false;
        }
        pointer = newId();
        keyboard = newId();
        if (!send(seat.id, wire::wl_seat::request::get_pointer, {pointer}) || !send(seat.id, wire::wl_seat::request::get_keyboard, {keyboard})) return false;
        if (relativeManager.id && constraints.id) {
            relativePointer = newId();
            if (!send(relativeManager.id, relativeManagerProtocol::request::get_relative_pointer,
                    {relativePointer, pointer})) return false;
        }
        connected = true;
        notifyState();
        return !active.visible || show();
    }

    bool attach(uint32_t width, uint32_t height) {
        if (buffers.size() >= 4) return false;
        const uint32_t size = width * height * 4;
        Fd memory(static_cast<int>(syscall(SYS_memfd_create, "xtmapper-wayland", MFD_CLOEXEC)));
        if (memory.value < 0 || ftruncate(memory.value, size) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, Tag, "Cannot prepare overlay buffer: errno=%d", errno);
            return false;
        }
        const uint32_t pool = newId();
        const uint32_t buffer = newId();
        buffers.push_back(buffer);
        overlay.width = width;
        overlay.height = height;
        // A new memfd reads as zero, producing a transparent ARGB buffer;
        // the default input region still covers the surface.
        return send(shm.id, wire::wl_shm::request::create_pool, {pool, size}, memory.value)
                && send(pool, wire::wl_shm_pool::request::create_buffer,
                        {buffer, 0, width, height, width * 4, 0})
                && send(pool, wire::wl_shm_pool::request::destroy)
                && send(overlay.xdgSurface, wire::xdg_surface::request::set_window_geometry,
                        {0, 0, width, height})
                && send(overlay.surface, wire::wl_surface::request::attach, {buffer, 0, 0})
                && send(overlay.surface, wire::wl_surface::request::damage, {0, 0, width, height})
                && send(overlay.surface, wire::wl_surface::request::commit);
    }

    bool show() {
        if (active.lockPointer && (!relativePointer || !constraints.id)) {
            __android_log_print(ANDROID_LOG_ERROR, Tag, "Pointer lock protocols unavailable");
            return true;
        }
        if (active.width <= 0 || active.height <= 0 || active.width > 8192 ||
                active.height > 8192 || uint64_t(active.width) * active.height * 4 > 128u * 1024 * 1024) {
            __android_log_print(ANDROID_LOG_ERROR, Tag, "Unsupported overlay size %dx%d",
                    active.width, active.height);
            return true;
        }
        const Words size{static_cast<uint32_t>(active.width), static_cast<uint32_t>(active.height)};
        overlay.surface = newId();
        overlay.xdgSurface = newId();
        overlay.toplevel = newId();
        overlay.configureDeadline = Clock::now() + 4s;
        return send(compositor.id, wire::wl_compositor::request::create_surface, {overlay.surface})
                && send(shell.id, wire::xdg_wm_base::request::get_xdg_surface,
                        {overlay.xdgSurface, overlay.surface})
                && send(overlay.xdgSurface, wire::xdg_surface::request::get_toplevel, {overlay.toplevel})
                && send(overlay.toplevel, wire::xdg_toplevel::request::set_title, encodeString("XtMapper"))
                && send(overlay.toplevel, wire::xdg_toplevel::request::set_app_id,
                        encodeString("org.xtmapper.WaylandCapture"))
                && send(overlay.toplevel, wire::xdg_toplevel::request::set_max_size, size)
                && send(overlay.toplevel, wire::xdg_toplevel::request::set_min_size, size)
                && send(overlay.surface, wire::wl_surface::request::commit);
    }

    bool hide() {
        const Overlay previous = overlay;
        overlay = {};
        resetDeltas();
        if (!previous.surface) return true;
        return (!previous.lock || send(previous.lock, lockedPointer::request::destroy))
                && send(previous.toplevel, wire::xdg_toplevel::request::destroy)
                && send(previous.xdgSurface, wire::xdg_surface::request::destroy)
                && send(previous.surface, wire::wl_surface::request::destroy);
    }

    bool configure(const Words& args) {
        if (args.size() != 1 || !send(overlay.xdgSurface, wire::xdg_surface::request::ack_configure, args)) return false;
        if (!overlay.mapped) {
            if (!attach(active.width, active.height)) return false;
            overlay.mapped = true;
            notifyState();
        }
        return true;
    }

    bool syncPointerLock() {
        const bool wantsLock = active.lockPointer && capturing() && relativePointer && constraints.id;
        if (wantsLock && !overlay.lock) {
            overlay.lock = newId();
            return send(constraints.id, lockProtocol::request::lock_pointer,
                    {overlay.lock, overlay.surface, pointer, 0, 2});
        }
        if (!wantsLock && overlay.lock) {
            const uint32_t oldLock = overlay.lock;
            overlay.lock = 0;
            overlay.locked = false;
            resetDeltas();
            return send(oldLock, lockedPointer::request::destroy);
        }
        return true;
    }

    bool pointerEvent(uint32_t opcode, const Words& args) {
        if (opcode == wire::wl_pointer::event::enter && args.size() == 4 && args[1] == overlay.surface) {
            overlay.pointerFocus = true;
            overlay.pointerX = fixed(args[2]);
            overlay.pointerY = fixed(args[3]);
            if (!syncPointerLock()) return false;
            notifyState();
            notifyAbsolute();
        }
        if (opcode == wire::wl_pointer::event::leave && args.size() == 2 && args[1] == overlay.surface) {
            overlay.pointerFocus = overlay.locked = false;
            resetDeltas();
            if (!syncPointerLock()) return false;
            notifyState();
        }
        if (opcode == wire::wl_pointer::event::motion && args.size() == 3 && overlay.pointerFocus) {
            overlay.pointerX = fixed(args[1]);
            overlay.pointerY = fixed(args[2]);
            notifyAbsolute();
        }
        if (opcode == wire::wl_pointer::event::button && args.size() == 4 && args[3] <= 1) notifyEvent(Button, args[2], args[3]);
        if (opcode == wire::wl_pointer::event::axis && args.size() == 3 && args[1] < wheel.size()) {
            wheel[args[1]].value += fixed(args[2]);
            if (seat.advertisedVersion < 5) emitWheel();
        }
        if (opcode == wire::wl_pointer::event::frame && args.empty()) emitWheel();
        if (opcode == wire::wl_pointer::event::axis_discrete && args.size() == 2 && args[0] < wheel.size()) {
            wheel[args[0]].discrete += static_cast<int32_t>(args[1]);
            wheel[args[0]].hasDiscrete = true;
        }
        return true;
    }

    void emitWheel() {
        const WheelAxis& horizontal = wheel[1];
        const WheelAxis& vertical = wheel[0];
        const double x = horizontal.hasDiscrete ? horizontal.discrete : horizontal.value / 10;
        const double y = vertical.hasDiscrete ? vertical.discrete : vertical.value / 10;
        accumulate(Scroll, x, y, wheelX, wheelY);
        wheel = {};
    }

    bool keyboardEvent(uint32_t opcode, const Words& args) {
        if (opcode == wire::wl_keyboard::event::enter && args.size() >= 3 && args[1] == overlay.surface) {
            // Keys already down when focus enters do not start a mapped action.
            overlay.keyboardFocus = true;
            if (!syncPointerLock()) return false;
            notifyState();
            notifyAbsolute();
        }
        if (opcode == wire::wl_keyboard::event::leave && args.size() == 2 && args[1] == overlay.surface) {
            overlay.keyboardFocus = false;
            resetDeltas();
            if (!syncPointerLock()) return false;
            notifyState();
        }
        if (opcode == wire::wl_keyboard::event::key && args.size() == 4 && args[3] <= 1) notifyEvent(Key, args[2], args[3]);
        return true;
    }

    bool event(uint32_t object, uint16_t opcode, const Words& args) {
        if (object == Display) {
            if (opcode == wire::wl_display::event::error) {
                __android_log_print(ANDROID_LOG_ERROR, Tag, "Compositor protocol error object=%u code=%u",
                        args.size() >= 2 ? args[0] : 0, args.size() >= 2 ? args[1] : 0);
                return false;
            }
            if (opcode == wire::wl_display::event::delete_id && args.size() == 1 &&
                    args[0] >= Display + 1 && args[0] < nextId) {
                freeIds.push_back(args[0]);
            }
            return true;
        }
        if (object == registry) {
            if (opcode == wire::wl_registry::event::global) return announceGlobal(args);
            if (opcode == wire::wl_registry::event::global_remove && args.size() == 1) {
                for (const Global* global : globals) {
                    if (global->advertisedName == args[0]) {
                        __android_log_print(ANDROID_LOG_ERROR, Tag, "Wayland global removed: %s", global->name);
                        return false;
                    }
                }
            }
            return true;
        }
        if (object == registrySync && opcode == wire::wl_callback::event::done) {
            registrySync = 0;
            return bindGlobals();
        }
        if (object == capabilitySync && opcode == wire::wl_callback::event::done) {
            capabilitySync = 0;
            return initializeDevices();
        }
        if (object == shell.id && opcode == wire::xdg_wm_base::event::ping && args.size() == 1) {
            return send(shell.id, wire::xdg_wm_base::request::pong, args);
        }
        if (object == shm.id && opcode == wire::wl_shm::event::format && args.size() == 1 && args[0] == 0) argb = true;
        if (object == seat.id && opcode == wire::wl_seat::event::capabilities && args.size() == 1) {
            seatCapabilities = args[0];
            if (connected && (seatCapabilities & RequiredSeatCapabilities) != RequiredSeatCapabilities) {
                __android_log_print(ANDROID_LOG_ERROR, Tag, "Wayland seat lost keyboard or pointer");
                return false;
            }
        }
        const auto buffer = std::find(buffers.begin(), buffers.end(), object);
        if (buffer != buffers.end() && opcode == wire::wl_buffer::event::release) {
            buffers.erase(buffer);
            return send(object, wire::wl_buffer::request::destroy);
        }
        if (!overlay.surface) return true;
        if (object == overlay.toplevel && opcode == wire::xdg_toplevel::event::close) return false;
        if (object == overlay.xdgSurface && opcode == wire::xdg_surface::event::configure) return configure(args);
        if (object == pointer) return pointerEvent(opcode, args);
        if (object == keyboard) return keyboardEvent(opcode, args);
        if (object == overlay.lock && (opcode == lockedPointer::event::locked ||
                opcode == lockedPointer::event::unlocked)) {
            overlay.locked = opcode == lockedPointer::event::locked && overlay.pointerFocus;
            if (!overlay.locked) resetDeltas();
            notifyState();
        }
        if (object == relativePointer && opcode == relativeProtocol::event::relative_motion &&
                args.size() == 6 && overlay.locked) {
            accumulate(Relative, fixed(args[4]), fixed(args[5]), relativeX, relativeY);
        }
        return true;
    }

    bool readEvents() {
        uint8_t bytes[8192];
        // Omitting ancillary data lets the kernel close incoming file descriptors,
        // such as the wl_keyboard keymap. Physical evdev codes require no keymap.
        const ssize_t count = recv(socket.value, bytes, sizeof(bytes), 0);
        if (count < 0 && (errno == EAGAIN || errno == EINTR)) return true;
        if (count <= 0) return false;
        input.insert(input.end(), bytes, bytes + count);
        size_t consumed = 0;
        while (input.size() - consumed >= 8) {
            uint32_t header[2];
            std::memcpy(header, input.data() + consumed, sizeof(header));
            const uint32_t size = header[1] >> 16;
            if (size < 8 || size > MaxMessageSize || size % 4 != 0) return false;
            if (input.size() - consumed < size) break;
            Words args((size - 8) / 4);
            if (!args.empty()) std::memcpy(args.data(), input.data() + consumed + 8, size - 8);
            if (!event(header[0], header[1], args)) return false;
            consumed += size;
        }
        input.erase(input.begin(), input.begin() + consumed);
        return true;
    }

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
            if (!syncPointerLock()) return false;
            notifyState();
            notifyAbsolute();
            return true;
        }
        active = requested;
        if (!hide()) return false;
        notifyState();
        return !connected || !active.visible || show();
    }

    bool dispatch() {
        registry = newId();
        registrySync = newId();
        if (!send(Display, wire::wl_display::request::get_registry, {registry}) ||
                !send(Display, wire::wl_display::request::sync, {registrySync})) return false;
        const auto setupDeadline = Clock::now() + 4s;
        while (!callbackFailed) {
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
            pollfd fds[]{
                {wake.value, POLLIN, 0},
                {socket.value, static_cast<short>(POLLIN | (output.empty() ? 0 : POLLOUT)), 0},
            };
            int ready = poll(fds, 2, timeout);
            if (ready < 0 && errno == EINTR) continue;
            if (ready < 0) return false;
            if (fds[0].revents && !applyCommand()) return false;
            if ((fds[1].revents & POLLIN) && !readEvents()) return false;
            if ((fds[1].revents & POLLOUT) && !flush()) return false;
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
        session.socket.reset();
        session.output.clear();
        session.overlay = {};
        session.connected = false;
        session.notifyState();
        session.vm->DetachCurrentThread();
        return nullptr;
    }
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
