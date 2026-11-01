#include "evdev_common.h"

#include "mouse_cursor.h"

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
// read uninitialised stack as capability data).
template <size_t N, typename Ioctl>
bool hasBit(Ioctl&& ioctl, int fd, unsigned long request, unsigned int bit) {
	std::array<unsigned char, N> bits {};
	if (bit / 8 >= N) return false;
	if (ioctl(fd, request, bits.data()) < 0) return false;
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
} // namespace

std::vector<string> ListInputDevices() {
	const string input_directory = "/dev/input";
	std::vector<string> filenames;
	DIR * directory = opendir(input_directory.c_str());
	if (!directory) return filenames;

	struct dirent *entry;
	while ((entry = readdir(directory))) {
		if (entry->d_name[0] == 'e') // is eventX
		  filenames.push_back(input_directory + "/" + entry->d_name);
	}
	closedir(directory);
	return filenames;
}

TouchpadScanResult scanTouchpadDevices(EvdevOps ops) {
	if (!ops.list) ops.list = ListInputDevices;
	if (!ops.open) ops.open = defaultOpen;
	if (!ops.ioctl) ops.ioctl = defaultIoctl;
	if (!ops.close) ops.close = defaultClose;

	TouchpadScanResult result;
	const std::vector<string> evdevNames = ops.list();
	if (evdevNames.empty()) return result; // NO_DEVICE

	printf("I: Searching for touchpad devices...\n");
	bool openedAny = false;
	bool openFailed = false;
	for (auto & evdev : evdevNames) {
		int device_fd = ops.open(evdev);
		if (device_fd < 0) {
			// fd 0 is a valid descriptor; only a negative return is a failure.
			perror("opening device");
			openFailed = true;
			continue;
		}
		openedAny = true;

		// Get device name. EVIOCGNAME returns < 0 on failure (a successful
		// call returns the name length, so `== 0` would discard every device).
		char dev_name[24] {};
		if (ops.ioctl(device_fd, EVIOCGNAME(sizeof(dev_name) - 1), dev_name) < 0) {
			perror("get device name");
			ops.close(device_fd);
			continue;
		}

		if (!hasBit<kKeyBits>(ops.ioctl, device_fd, EVIOCGBIT(EV_ABS, kKeyBits), ABS_X) ||
		    !hasBit<kKeyBits>(ops.ioctl, device_fd, EVIOCGBIT(EV_ABS, kKeyBits), ABS_Y)) {
			printf("%s: no ABS_X or ABS_Y events found\n", dev_name);
			printf("Not a touch device\n");
			ops.close(device_fd);
			continue;
		}

		if (!hasBit<kPropBits>(ops.ioctl, device_fd, EVIOCGPROP(kPropBits), INPUT_PROP_POINTER)) {
			printf("%s: INPUT_PROP_POINTER not set\n", dev_name);
			if (!hasBit<kKeyBits>(ops.ioctl, device_fd, EVIOCGBIT(EV_KEY, kKeyBits), BTN_MOUSE)) {
				printf("BTN_MOUSE not found\n");
				printf("Not a touchpad device\n");
				ops.close(device_fd);
				continue;
			}
		}

		// Skip this process's own virtual devices so they are never re-grabbed.
		if (strcmp(x_virtual_tablet, dev_name) == 0 ||
		    strcmp(x_virtual_mouse, dev_name) == 0 ||
		    strcmp(x_virtual_touch, dev_name) == 0) {
			printf("skipping %s\n", dev_name);
			ops.close(device_fd);
			continue;
		}

		if (ops.ioctl(device_fd, EVIOCGRAB, reinterpret_cast<void*>(1)) < 0) {
			printf("%s: EVIOCGRAB failed\n", dev_name);
			ops.close(device_fd);
			continue;
		}

		printf("I: Add touchpad device: %s %s\n", dev_name, evdev.c_str());
		result.fds.push_back(device_fd);
	}

	if (!result.fds.empty()) {
		result.status = TouchpadScanStatus::OK;
	} else if (openedAny || !openFailed) {
		result.status = TouchpadScanStatus::NO_DEVICE;
	} else {
		// Every candidate existed but none could be opened.
		result.status = TouchpadScanStatus::ACCESS_ERROR;
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
