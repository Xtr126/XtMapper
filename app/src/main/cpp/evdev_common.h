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

struct DeviceList {
	bool ok = true;
	std::vector<std::string> paths;
};

DeviceList EnumerateInputDevices();

struct EvdevOps {
	std::function<DeviceList()> list;
	std::function<int(const std::string& path)> open;
	std::function<int(int fd, unsigned long request, void* arg)> ioctl;
	std::function<int(int fd)> close;
};

TouchpadScanResult scanTouchpadDevices(EvdevOps ops);

inline TouchpadScanResult scanTouchpadDevices() {
	return scanTouchpadDevices(EvdevOps{});
}
