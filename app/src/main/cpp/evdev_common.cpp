#include "evdev_common.h"

#include "mouse_cursor.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <linux/input-event-codes.h>
#include <linux/input.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <array>

using std::string;

namespace {
constexpr size_t kKeyBits = KEY_MAX / 8 + 1;
constexpr size_t kPropBits = INPUT_PROP_MAX / 8 + 1;

// Zero-initialised bit probe: a failed ioctl means the bit is absent (never
// read uninitialised stack as capability data). The optional `queried` out
// distinguishes "ioctl failed — capability unknown" from "ioctl succeeded —
// bit genuinely absent".
template <size_t N, typename Ioctl>
bool hasBit(Ioctl&& ioctl, int fd, unsigned long request, unsigned int bit, bool* queried = nullptr) {
	std::array<unsigned char, N> bits {};
	if (queried) *queried = true;
	if (bit / 8 >= N) return false;
	if (ioctl(fd, request, bits.data()) < 0) {
		if (queried) *queried = false;
		return false;
	}
	return (bits[bit / 8] & (1u << (bit % 8))) != 0;
}

int defaultOpen(const string& path) {
	// O_NONBLOCK keeps worker reads from ever blocking; O_CLOEXEC avoids fd leaks
	// across the getevent/process helpers.
	return ::open(path.c_str(), O_RDWR | O_NONBLOCK | O_CLOEXEC);
}

int defaultIoctl(int fd, unsigned long request, void* arg) {
	return ::ioctl(fd, request, arg);
}

int defaultClose(int fd) { return ::close(fd); }

// Owns one descriptor across the operations that can throw (the injectable
// ops seam and vector growth); closes via ops at scope exit unless released.
// The destructor swallows: it is noexcept, and a throwing std::function
// close must not terminate the process (mirrors cleanupResources' pattern).
struct FdGuard {
	int fd = -1;
	EvdevOps* ops = nullptr;
	~FdGuard() {
		if (fd >= 0 && ops) {
			try {
				ops->close(fd);
			} catch (...) {
			}
		}
	}
	void release() { fd = -1; }
};
} // namespace

DeviceList EnumerateInputDevices() {
	const string input_directory = "/dev/input";
	DeviceList out;
	DIR * directory = opendir(input_directory.c_str());
	if (!directory) {
		out.ok = false;
		return out;
	}

	bool failed = false;
	try {
		struct dirent *entry;
		for (;;) {
			errno = 0; // per-call reset: loop-body allocators may clobber errno
			entry = readdir(directory);
			if (entry == nullptr) break;
			if (entry->d_name[0] == 'e') // is eventX
			  out.paths.push_back(input_directory + "/" + entry->d_name);
		}
		if (errno != 0) failed = true; // readdir error ≠ end-of-directory
	} catch (...) {
		// Allocation failure mid-enumeration: no exception escapes this seam.
		failed = true;
	}
	closedir(directory);
	if (failed) {
		out.ok = false;
		out.paths.clear();
	}
	return out;
}

std::vector<string> ListInputDevices() { return EnumerateInputDevices().paths; }

TouchpadScanResult scanTouchpadDevices(EvdevOps ops) {
	if (!ops.list) ops.list = EnumerateInputDevices;
	if (!ops.open) ops.open = defaultOpen;
	if (!ops.ioctl) ops.ioctl = defaultIoctl;
	if (!ops.close) ops.close = defaultClose;

	TouchpadScanResult result;
	const DeviceList listing = ops.list();
	if (!listing.ok) {
		// The directory could not be enumerated: that is a scan failure,
		// not confirmed absence of devices.
		result.status = TouchpadScanStatus::SCAN_ERROR;
		return result;
	}
	const std::vector<string>& evdevNames = listing.paths;
	if (evdevNames.empty()) return result; // confirmed absence: NO_DEVICE

	printf("I: Searching for touchpad devices...\n");
	bool openFailed = false;
	// Candidates existed but could not be opened, inspected (query) or
	// acquired (grab): unknown, never a clean NO_DEVICE.
	bool queryFailed = false;
	bool grabFailed = false;
	try {
		for (auto & evdev : evdevNames) {
			int device_fd = ops.open(evdev);
			if (device_fd < 0) {
				// fd 0 is a valid descriptor; only a negative return is a failure.
				perror("opening device");
				openFailed = true;
				continue;
			}
			FdGuard guard {device_fd, &ops}; // closes on every rejection path

			// Get device name. EVIOCGNAME returns < 0 on failure (a successful
			// call returns the name length, so `== 0` would discard every device).
			char dev_name[24] {};
			if (ops.ioctl(device_fd, EVIOCGNAME(sizeof(dev_name) - 1), dev_name) < 0) {
				perror("get device name");
				queryFailed = true; // the device exists but cannot be identified
				continue;
			}

			// Queried defaults are optimistic: hasBit only flips them when its
			// ioctl actually runs, and C++ ||-short-circuit may skip the second probe.
			bool absXQueried = true;
			bool absYQueried = true;
			if (!hasBit<kKeyBits>(ops.ioctl, device_fd, EVIOCGBIT(EV_ABS, kKeyBits), ABS_X, &absXQueried) ||
			    !hasBit<kKeyBits>(ops.ioctl, device_fd, EVIOCGBIT(EV_ABS, kKeyBits), ABS_Y, &absYQueried)) {
				queryFailed = queryFailed || !absXQueried || !absYQueried;
				printf("%s: no ABS_X or ABS_Y events found\n", dev_name);
				printf("Not a touch device\n");
				continue;
			}

			bool propQueried = true;
			if (!hasBit<kPropBits>(ops.ioctl, device_fd, EVIOCGPROP(kPropBits), INPUT_PROP_POINTER, &propQueried)) {
				queryFailed = queryFailed || !propQueried;
				printf("%s: INPUT_PROP_POINTER not set\n", dev_name);
				bool btnQueried = true;
				if (!hasBit<kKeyBits>(ops.ioctl, device_fd, EVIOCGBIT(EV_KEY, kKeyBits), BTN_MOUSE, &btnQueried)) {
					queryFailed = queryFailed || !btnQueried;
					printf("BTN_MOUSE not found\n");
					printf("Not a touchpad device\n");
					continue;
				}
			}

			// Skip this process's own virtual devices so they are never re-grabbed.
			if (strcmp(x_virtual_tablet, dev_name) == 0 ||
			    strcmp(x_virtual_mouse, dev_name) == 0 ||
			    strcmp(x_virtual_touch, dev_name) == 0) {
				printf("skipping %s\n", dev_name);
				continue;
			}

			if (ops.ioctl(device_fd, EVIOCGRAB, reinterpret_cast<void*>(1)) < 0) {
				printf("%s: EVIOCGRAB failed\n", dev_name);
				grabFailed = true;
				continue;
			}

			printf("I: Add touchpad device: %s %s\n", dev_name, evdev.c_str());
			result.fds.push_back(device_fd); // guard reclaims current fd if this throws
			guard.release();
		}
	} catch (...) {
		// Contract: on non-OK status every opened fd is closed. The current
		// device was reclaimed by its FdGuard during unwinding; close the
		// collected ones and report the acquisition as failed.
		for (int fd : result.fds) {
			try {
				ops.close(fd);
			} catch (...) {
			}
		}
		result.fds.clear();
		result.status = TouchpadScanStatus::SCAN_ERROR;
		return result;
	}

	if (!result.fds.empty()) {
		// Partial success policy: keep the grabbed subset, ignore earlier
		// per-device failures (they are logged above).
		result.status = TouchpadScanStatus::OK;
	} else if (queryFailed) {
		// Candidates existed but could not be inspected: unknown, not absent.
		result.status = TouchpadScanStatus::SCAN_ERROR;
	} else if (grabFailed || openFailed) {
		// Present but not acquirable: grab denied or some candidate could
		// not even be opened — absence is unconfirmed.
		result.status = TouchpadScanStatus::ACCESS_ERROR;
	} else {
		// Every candidate opened, was identified and cleanly rejected.
		result.status = TouchpadScanStatus::NO_DEVICE;
	}
	return result;
}

// bionic declares ioctl() as two __overloadable variants, so ::ioctl is an
// ambiguous overload set for the deduced Ioctl parameter; defaultIoctl is the
// single-signature wrapper (host glibc's single declaration hid this).
bool HasSpecificAbs(int device_fd, unsigned int abs) {
	return hasBit<kKeyBits>(defaultIoctl, device_fd, EVIOCGBIT(EV_ABS, kKeyBits), abs);
}

bool HasSpecificKey(int device_fd, unsigned int key) {
	return hasBit<kKeyBits>(defaultIoctl, device_fd, EVIOCGBIT(EV_KEY, kKeyBits), key);
}

bool HasInputProp(int device_fd, unsigned int input_prop) {
	return hasBit<kPropBits>(defaultIoctl, device_fd, EVIOCGPROP(kPropBits), input_prop);
}

bool HasEventType(int device_fd, unsigned int type) {
	unsigned long evbit = 0;
	// Get the bit field of available event types.
	if (::ioctl(device_fd, EVIOCGBIT(0, sizeof(evbit)), &evbit) < 0) return false;
	return (evbit & (1ul << type)) != 0;
}
