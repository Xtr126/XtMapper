#pragma once

#include <functional>
#include <string>
#include <vector>

inline constexpr const char x_virtual_touch[] = "x-virtual-touch";
inline constexpr const char x_virtual_mouse[] = "x-virtual-mouse";

std::vector<std::string> ListInputDevices();

bool HasSpecificAbs(int device_fd, unsigned int abs);

bool HasSpecificKey(int device_fd, unsigned int key);

bool HasInputProp(int device_fd, unsigned int input_prop);

bool HasEventType(int device_fd, unsigned int type);

enum class TouchpadScanStatus { OK, NO_DEVICE, SCAN_ERROR, ACCESS_ERROR };

struct TouchpadScanResult {
	TouchpadScanStatus status = TouchpadScanStatus::NO_DEVICE;
	std::vector<int> fds;
};

// Enumeration result: ok=false means the directory could not be fully
// listed (opendir failure, readdir error, or allocation failure mid-scan);
// paths is empty then. A genuinely empty directory is ok=true, zero paths.
struct DeviceList {
	bool ok = true;
	std::vector<std::string> paths;
};

DeviceList EnumerateInputDevices();

// Injectable seam for host tests; production callers use the defaults
// (readdir, open(O_RDWR|O_NONBLOCK|O_CLOEXEC), ioctl, close).
struct EvdevOps {
	std::function<DeviceList()> list;
	std::function<int(const std::string& path)> open;
	std::function<int(int fd, unsigned long request, void* arg)> ioctl;
	std::function<int(int fd)> close;
};

// Scanner contract: on non-OK status every opened fd has been closed; on OK
// the caller owns every fd in `fds` (grabbed, ready to poll). Unset ops entries
// are filled with the real implementations before scanning.
TouchpadScanResult scanTouchpadDevices(EvdevOps ops);

inline TouchpadScanResult scanTouchpadDevices() {
	return scanTouchpadDevices(EvdevOps{});
}
