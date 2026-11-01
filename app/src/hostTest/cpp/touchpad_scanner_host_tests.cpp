// Host tests for the touchpad scanner (evdev_common.cpp) using fully injected
// EvdevOps — readdir/open/ioctl/close never touch the real system, so edge
// cases like fd 0, EVIOCGNAME failure, and EVIOCGRAB failure are deterministic.
#include "evdev_common.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <linux/input-event-codes.h>
#include <linux/input.h>
#include <map>
#include <set>
#include <string>
#include <sys/ioctl.h>
#include <vector>

static int g_failures = 0;

#define CHECK(cond)                                                            \
	do {                                                                       \
		if (!(cond)) {                                                         \
			std::fprintf(stderr, "CHECK failed: %s (%s:%d)\n", #cond, __FILE__, \
			             __LINE__);                                            \
			g_failures++;                                                      \
		}                                                                      \
	} while (0)

#define CHECK_EQ(a, b)                                                         \
	do {                                                                       \
		auto va = (a);                                                         \
		auto vb = (b);                                                         \
		if (!(va == vb)) {                                                     \
			std::fprintf(stderr,                                               \
			             "CHECK_EQ failed: %s == %s (%lld vs %lld) (%s:%d)\n",  \
			             #a, #b, (long long)va, (long long)vb, __FILE__,       \
			             __LINE__);                                            \
			g_failures++;                                                      \
		}                                                                      \
	} while (0)

namespace {

struct Profile {
	std::string name;
	std::set<unsigned> absBits;
	std::set<unsigned> keyBits;
	bool propPointer = false;
	bool grabOk = true;
};

std::vector<std::string> g_paths;
std::set<std::string> g_openFailures;
std::map<std::string, Profile> g_profiles; // path -> profile
std::map<int, Profile> g_fdProfiles;       // fd -> profile
std::vector<int> g_grabbed;
std::vector<int> g_closed;
int g_nextFd = 100;

void reset() {
	g_paths.clear();
	g_openFailures.clear();
	g_profiles.clear();
	g_fdProfiles.clear();
	g_grabbed.clear();
	g_closed.clear();
	g_nextFd = 100;
}

void addDevice(const std::string& path, Profile profile) {
	g_paths.push_back(path);
	g_profiles[path] = std::move(profile);
}

int fakeOpen(const std::string& path) {
	if (g_openFailures.count(path) != 0) return -1;
	auto it = g_profiles.find(path);
	if (it == g_profiles.end()) return -1;
	int fd = g_nextFd++;
	g_fdProfiles[fd] = it->second;
	return fd;
}

int fakeClose(int fd) {
	g_closed.push_back(fd);
	g_fdProfiles.erase(fd);
	return 0;
}

void setBits(void* arg, unsigned size, const std::set<unsigned>& bits) {
	auto* bytes = static_cast<unsigned char*>(arg);
	for (unsigned bit : bits) {
		if (bit / 8 >= size) continue;
		bytes[bit / 8] |= static_cast<unsigned char>(1u << (bit % 8));
	}
}

int fakeIoctl(int fd, unsigned long request, void* arg) {
	if (_IOC_TYPE(request) != 'E') return -1;
	auto it = g_fdProfiles.find(fd);
	if (it == g_fdProfiles.end()) return -1;
	const Profile& profile = it->second;
	const unsigned nr = _IOC_NR(request);
	const unsigned size = _IOC_SIZE(request);

	if (nr == _IOC_NR(EVIOCGNAME(1))) { // 0x06
		std::memcpy(arg, profile.name.data(),
		            std::min(static_cast<size_t>(size), profile.name.size()));
		return static_cast<int>(profile.name.size());
	}
	if (nr == 0x90) { // EVIOCGRAB
		if (!profile.grabOk) return -1;
		g_grabbed.push_back(fd);
		return 0;
	}
	if (nr >= 0x20 && nr <= 0x20 + EV_MAX) { // EVIOCGBIT(eventType, len)
		std::memset(arg, 0, size);
		const unsigned eventType = nr - 0x20;
		if (eventType == EV_KEY) setBits(arg, size, profile.keyBits);
		else if (eventType == EV_ABS) setBits(arg, size, profile.absBits);
		return 0;
	}
	if (nr == _IOC_NR(EVIOCGPROP(1))) { // 0x09
		std::memset(arg, 0, size);
		if (profile.propPointer) {
			auto* bytes = static_cast<unsigned char*>(arg);
			if (INPUT_PROP_POINTER / 8 < size)
				bytes[INPUT_PROP_POINTER / 8] |=
				    static_cast<unsigned char>(1u << (INPUT_PROP_POINTER % 8));
		}
		return 0;
	}
	return -1;
}

EvdevOps makeOps() {
	EvdevOps ops;
	ops.list = []() { return DeviceList {true, g_paths}; };
	ops.open = fakeOpen;
	ops.ioctl = fakeIoctl;
	ops.close = fakeClose;
	return ops;
}

Profile touchpadProfile(const std::string& name) {
	Profile p;
	p.name = name;
	p.absBits = {ABS_X, ABS_Y};
	p.keyBits = {BTN_TOUCH};
	p.propPointer = true;
	return p;
}

bool contains(const std::vector<int>& v, int value) {
	return std::find(v.begin(), v.end(), value) != v.end();
}

// ---- tests ---------------------------------------------------------------

void testEmptyListIsNoDevice() {
	reset();
	auto r = scanTouchpadDevices(makeOps());
	CHECK(r.status == TouchpadScanStatus::NO_DEVICE);
	CHECK(r.fds.empty());
}

// fd 0 is valid; only a negative return is a failure (no dup2 games: the
// injected open hands out 0 directly).
void testFdZeroIsAccepted() {
	reset();
	Profile p = touchpadProfile("Zero Touchpad");
	addDevice("/dev/input/event0", p);
	g_nextFd = 0;
	auto r = scanTouchpadDevices(makeOps());
	CHECK(r.status == TouchpadScanStatus::OK);
	CHECK_EQ(r.fds.size(), 1);
	CHECK_EQ(r.fds[0], 0);
	CHECK(contains(g_grabbed, 0));
	CHECK(g_closed.empty());
}

// EVIOCGNAME < 0 is a failure (device skipped); 0 is a successful call.
void testEvioCgnameFailureSkipsDevice() {
	reset();
	addDevice("/dev/input/event0", touchpadProfile("Bad Name"));
	addDevice("/dev/input/event1", touchpadProfile("Good Touchpad"));
	// Break event0's name lookup: remove its profile-backed name match by
	// making the ioctl fail through an empty name -> return 0-length is
	// success, so instead drop the fd from the profile map after open.
	auto ops = makeOps();
	// Simpler: scan only the failing device by flagging its open to hand out
	// an fd whose profile lookup fails (removed mid-flight).
	int openCalls = 0;
	ops.open = [&openCalls](const std::string& path) {
		int fd = fakeOpen(path);
		if (++openCalls == 1) g_fdProfiles.erase(fd); // EVIOCGNAME now fails
		return fd;
	};
	auto r = scanTouchpadDevices(ops);
	// event0 rejected (ioctl -1), event1 accepted.
	CHECK(r.status == TouchpadScanStatus::OK);
	CHECK_EQ(r.fds.size(), 1);
	CHECK_EQ(r.fds[0], 101);
}

// A zero-length name return (success) must not reject the device: the old
// `!ioctl(...)` check treated return 0 as failure.
void testEvioCgnameZeroReturnAccepted() {
	reset();
	Profile p = touchpadProfile(""); // fakeIoctl returns 0 (empty name)
	addDevice("/dev/input/event0", p);
	auto ops = makeOps();
	auto r = scanTouchpadDevices(ops);
	CHECK(r.status == TouchpadScanStatus::OK);
	CHECK_EQ(r.fds.size(), 1);
}

// A failed open must skip only that device (the old code kept going on fd -1).
void testOpenFailureContinuesToNextDevice() {
	reset();
	addDevice("/dev/input/event0", touchpadProfile("Locked Touchpad"));
	addDevice("/dev/input/event1", touchpadProfile("Free Touchpad"));
	g_openFailures.insert("/dev/input/event0");
	auto r = scanTouchpadDevices(makeOps());
	CHECK(r.status == TouchpadScanStatus::OK);
	CHECK_EQ(r.fds.size(), 1);
	CHECK_EQ(r.fds[0], 100); // only event1 opened
}

void testAllOpensFailIsAccessError() {
	reset();
	addDevice("/dev/input/event0", touchpadProfile("A"));
	addDevice("/dev/input/event1", touchpadProfile("B"));
	g_openFailures.insert("/dev/input/event0");
	g_openFailures.insert("/dev/input/event1");
	auto r = scanTouchpadDevices(makeOps());
	CHECK(r.status == TouchpadScanStatus::ACCESS_ERROR);
	CHECK(r.fds.empty());
}

// The old pointer-compare bug never matched virtual names, and the
// x-virtual-touch branch cleared the whole list; now each virtual device is
// skipped individually and scanning continues.
void testVirtualDevicesSkippedWithoutClearingResults() {
	reset();
	addDevice("/dev/input/event0", touchpadProfile("Real Touchpad"));
	addDevice("/dev/input/event1", touchpadProfile(x_virtual_touch));
	addDevice("/dev/input/event2", touchpadProfile(x_virtual_mouse));
	addDevice("/dev/input/event3", touchpadProfile("x-virtual-tablet"));
	addDevice("/dev/input/event4", touchpadProfile("Second Real Touchpad"));
	auto r = scanTouchpadDevices(makeOps());
	CHECK(r.status == TouchpadScanStatus::OK);
	CHECK_EQ(r.fds.size(), 2);
	CHECK_EQ(r.fds[0], 100);
	CHECK_EQ(r.fds[1], 104);
	CHECK_EQ(g_grabbed.size(), 2);
	CHECK_EQ(g_closed.size(), 3); // the three virtual fds were closed
}

void testGrabFailureSkipsDevice() {
	reset();
	Profile bad = touchpadProfile("Busy Touchpad");
	bad.grabOk = false;
	addDevice("/dev/input/event0", bad);
	addDevice("/dev/input/event1", touchpadProfile("Free Touchpad"));
	auto r = scanTouchpadDevices(makeOps());
	CHECK(r.status == TouchpadScanStatus::OK);
	CHECK_EQ(r.fds.size(), 1);
	CHECK_EQ(r.fds[0], 101);
	CHECK(contains(g_closed, 100)); // ungrabbed device closed, not leaked
}

void testNonTouchDevicesAreNoDevice() {
	reset();
	Profile keyboard;
	keyboard.name = "Some Keyboard";
	keyboard.keyBits = {KEY_A};
	addDevice("/dev/input/event0", keyboard);
	auto r = scanTouchpadDevices(makeOps());
	CHECK(r.status == TouchpadScanStatus::NO_DEVICE);
	CHECK(r.fds.empty());
	CHECK_EQ(g_closed.size(), 1);
}

void testPointerFallbackRequiresBtnMouse() {
	reset();
	Profile noProp = touchpadProfile("No Prop Touchpad");
	noProp.propPointer = false;
	noProp.keyBits = {}; // no BTN_MOUSE either -> rejected
	addDevice("/dev/input/event0", noProp);

	Profile withBtnMouse = touchpadProfile("BtnMouse Touchpad");
	withBtnMouse.propPointer = false;
	withBtnMouse.keyBits = {BTN_MOUSE, BTN_TOUCH};
	addDevice("/dev/input/event1", withBtnMouse);

	auto r = scanTouchpadDevices(makeOps());
	CHECK(r.status == TouchpadScanStatus::OK);
	CHECK_EQ(r.fds.size(), 1);
	CHECK_EQ(r.fds[0], 101);
	CHECK(contains(g_closed, 100));
}

// Enumeration failure (opendir on /dev/input) is not confirmed absence:
// it must surface as SCAN_ERROR, not a clean NO_DEVICE.
void testEnumerationFailureIsScanError() {
	reset();
	auto ops = makeOps();
	ops.list = []() { return DeviceList {false, {}}; };
	auto r = scanTouchpadDevices(ops);
	CHECK(r.status == TouchpadScanStatus::SCAN_ERROR);
	CHECK(r.fds.empty());
}

// A real touchpad that exists but cannot be grabbed (EBUSY/EACCES) is not
// confirmed absence either: ACCESS_ERROR, never a clean NO_DEVICE.
void testAllGrabsFailIsNotNoDevice() {
	reset();
	Profile busy = touchpadProfile("Busy Touchpad");
	busy.grabOk = false;
	addDevice("/dev/input/event0", busy);
	auto r = scanTouchpadDevices(makeOps());
	CHECK(r.status == TouchpadScanStatus::ACCESS_ERROR);
	CHECK(r.fds.empty());
	CHECK_EQ(g_closed.size(), 1);
}

// A required capability query that fails means "unknown", not "absent":
// the scanner could not inspect the device, so NO_DEVICE would be a lie.
void testRequiredQueryFailureIsNotNoDevice() {
	reset();
	addDevice("/dev/input/event0", touchpadProfile("Query Broken"));
	auto ops = makeOps();
	ops.ioctl = [](int fd, unsigned long request, void* arg) -> int {
		const unsigned nr = _IOC_NR(request);
		// Every EVIOCGBIT(EV_*) query fails; name/grab ioctls behave normally.
		if (nr >= 0x20 && nr <= 0x20 + EV_MAX) return -1;
		return fakeIoctl(fd, request, arg);
	};
	auto r = scanTouchpadDevices(ops);
	CHECK(r.status == TouchpadScanStatus::SCAN_ERROR);
	CHECK(r.fds.empty());
	CHECK_EQ(g_closed.size(), 1);
}

} // namespace

int main() {
	testEmptyListIsNoDevice();
	testFdZeroIsAccepted();
	testEvioCgnameFailureSkipsDevice();
	testEvioCgnameZeroReturnAccepted();
	testOpenFailureContinuesToNextDevice();
	testAllOpensFailIsAccessError();
	testVirtualDevicesSkippedWithoutClearingResults();
	testGrabFailureSkipsDevice();
	testNonTouchDevicesAreNoDevice();
	testPointerFallbackRequiresBtnMouse();
	testEnumerationFailureIsScanError();
	testAllGrabsFailIsNotNoDevice();
	testRequiredQueryFailureIsNotNoDevice();

	if (g_failures != 0) {
		std::fprintf(stderr, "%d check(s) FAILED\n", g_failures);
		return 1;
	}
	std::printf("touchpad_scanner_host_tests: all checks passed\n");
	return 0;
}
