// Host tests for touchpad_lifecycle.cpp. poll/read run for real against
// nonblocking pipes and eventfd (the same contract production evdev fds have:
// scanner opens O_NONBLOCK, EAGAIN ends the read drain); everything
// destructive (scan, virtual create/destroy, wake, release, spawn/join) is
// injectable for failure placement. The JNI owner layer (touchpad_jni.h) is
// deliberately uncovered here; it needs a device/CheckJNI run.
#include "touchpad_lifecycle.h"

#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <map>
#include <mutex>
#include <poll.h>
#include <stdexcept>
#include <sys/eventfd.h>
#include <thread>
#include <unistd.h>
#include <vector>

using namespace touchpad;

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

std::mutex g_ownerMu;
std::map<const void*, int> g_releases;

bool sameOwner(const void* a, const void* b) { return a == b; }

void releaseOwner(void* token) {
	std::lock_guard<std::mutex> lk(g_ownerMu);
	g_releases[token]++;
}

int releasesOf(const void* token) {
	std::lock_guard<std::mutex> lk(g_ownerMu);
	auto it = g_releases.find(token);
	return it == g_releases.end() ? 0 : it->second;
}

TouchpadLifecycle::OwnerOps ownerOps() { return {sameOwner, releaseOwner}; }

bool isClosed(int fd) {
	return fcntl(fd, F_GETFD) == -1 && errno == EBADF;
}

struct Pipe {
	int r = -1;
	int w = -1;
};

Pipe makePipe() {
	Pipe p;
	int fds[2];
	if (pipe(fds) != 0) {
		std::perror("pipe");
		std::abort();
	}
	// Production evdev fds are O_NONBLOCK; the worker's read drain relies on it.
	fcntl(fds[0], F_SETFL, O_NONBLOCK);
	p.r = fds[0];
	p.w = fds[1];
	return p;
}

int makeEventFd() {
	int fd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
	if (fd < 0) {
		std::perror("eventfd");
		std::abort();
	}
	return fd;
}

// One-device fixture: device pipe (evdev), virtual pipe (uinput), eventfd wake.
struct Fixture {
	Pipe device = makePipe();
	Pipe virt = makePipe();
	int wake = makeEventFd();
	// Heap tokens: stack addresses repeat across sequential tests and would
	// share release bookkeeping.
	int* owner = new int(0);
	int* otherOwner = new int(0);
	LifecycleOps ops;

	Fixture() {
		ops.scan = [this]() {
			TouchpadScanResult r;
			r.status = ScanStatus::OK;
			r.fds = {device.r};
			return r;
		};
		ops.createVirtual = [this](int) { return virt.w; };
		ops.createWake = [this]() { return wake; };
		ops.closeWake = [](int fd) { return ::close(fd) == 0; };
		ops.writeWake = [](int fd) {
			uint64_t one = 1;
			return ::write(fd, &one, sizeof(one)) ==
			       static_cast<ssize_t>(sizeof(one));
		};
		ops.releaseDevice = [](int fd) { return ::close(fd) == 0; };
		ops.destroyVirtual = [](int fd) { return ::close(fd) == 0; };
		ops.transform = [](int, const input_event& in) {
			return std::vector<input_event> {in};
		};
		// spawnWorker/joinWorker left unset: the core's default (real thread) runs.
	}
};

bool feedAndReadBack(Fixture& f, const input_event& in, input_event& out) {
	if (::write(f.device.w, &in, sizeof(in)) !=
	    static_cast<ssize_t>(sizeof(in)))
		return false;
	for (int i = 0; i < 200; i++) {
		ssize_t r = ::read(f.virt.r, &out, sizeof(out));
		if (r == static_cast<ssize_t>(sizeof(out))) return true;
		std::this_thread::sleep_for(std::chrono::milliseconds(1));
	}
	return false;
}

// ---- scan outcomes -------------------------------------------------------

void testNoDevice() {
	Fixture f;
	f.ops.scan = []() {
		return TouchpadScanResult {ScanStatus::NO_DEVICE, {}};
	};
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::NO_DEVICE);
	CHECK_EQ(releasesOf(f.owner), 1);
	CHECK(life.stop(f.owner) == StopOutcome::ALREADY_STOPPED);
}

void testScanError() {
	Fixture f;
	f.ops.scan = []() {
		return TouchpadScanResult {ScanStatus::SCAN_ERROR, {}};
	};
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::SCAN_ERROR);
	CHECK_EQ(releasesOf(f.owner), 1);
}

void testAccessError() {
	Fixture f;
	f.ops.scan = []() {
		return TouchpadScanResult {ScanStatus::ACCESS_ERROR, {}};
	};
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::ACCESS_ERROR);
	CHECK_EQ(releasesOf(f.owner), 1);
}

void testScanThrows() {
	Fixture f;
	f.ops.scan = []() -> TouchpadScanResult {
		throw std::runtime_error("scanner exploded");
	};
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::SCAN_ERROR);
	CHECK_EQ(releasesOf(f.owner), 1);
}

// ---- happy path + event forwarding --------------------------------------

void testStartStopHappyPath() {
	Fixture f;
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::STARTED);

	input_event in {};
	in.type = EV_REL;
	in.code = REL_X;
	in.value = 5;
	input_event out {};
	CHECK(feedAndReadBack(f, in, out));
	CHECK_EQ(out.type, EV_REL);
	CHECK_EQ(out.code, REL_X);
	CHECK_EQ(out.value, 5);

	CHECK(life.stop(f.owner) == StopOutcome::COMPLETED);
	CHECK_EQ(releasesOf(f.owner), 1);
	CHECK(isClosed(f.device.r));
	CHECK(isClosed(f.virt.w));
	CHECK(isClosed(f.wake));

	CHECK(life.stop(f.owner) == StopOutcome::ALREADY_STOPPED);
}

// ---- owner identity ------------------------------------------------------

void testBusyAndStaleStop() {
	Fixture f;
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::STARTED);

	CHECK(life.start(f.otherOwner, f.ops) == StartOutcome::BUSY);
	CHECK(life.start(f.owner, f.ops) == StartOutcome::BUSY);

	// A stale stop from another owner can never stop this run.
	CHECK(life.stop(f.otherOwner) == StopOutcome::NOT_OWNER);
	CHECK_EQ(releasesOf(f.otherOwner), 0);

	CHECK(life.stop(f.owner) == StopOutcome::COMPLETED);
	CHECK_EQ(releasesOf(f.owner), 1);
}

// ---- acquisition failures ------------------------------------------------

void testVirtualCreateFailure() {
	Fixture f;
	f.ops.createVirtual = [](int) { return -1; };
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::ACQUISITION_FAILED);
	CHECK(isClosed(f.device.r)); // scan fd released by rollback
	CHECK_EQ(releasesOf(f.owner), 1);
	CHECK(life.stop(f.owner) == StopOutcome::ALREADY_STOPPED);
}

void testWakeCreateFailure() {
	Fixture f;
	f.ops.createWake = []() { return -1; };
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::ACQUISITION_FAILED);
	CHECK(isClosed(f.virt.w));
	CHECK(isClosed(f.device.r));
	CHECK_EQ(releasesOf(f.owner), 1);
}

void testSpawnFailure() {
	Fixture f;
	f.ops.spawnWorker = [](std::function<void()>) { return false; };
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::THREAD_FAILED);
	CHECK(isClosed(f.virt.w));
	CHECK(isClosed(f.device.r));
	CHECK(isClosed(f.wake));
	CHECK_EQ(releasesOf(f.owner), 1);
}

// fd 0 is a valid descriptor; only a negative value is failure. No dup2 games:
// the scan result carries 0 and the injected release records it.
void testFdZeroIsAValidDeviceFd() {
	Fixture f;
	std::vector<int> released;
	f.ops.scan = []() {
		return TouchpadScanResult {ScanStatus::OK, {0}};
	};
	f.ops.createVirtual = [](int) { return -1; }; // fail after fd 0 accepted
	f.ops.releaseDevice = [&released](int fd) {
		released.push_back(fd);
		return true;
	};
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::ACQUISITION_FAILED);
	CHECK_EQ(released.size(), 1);
	CHECK_EQ(released[0], 0);
}

// ---- START_INCOMPLETE retention ------------------------------------------

void testStartIncompleteRetainsOwnerAndBlocksNewRuns() {
	Fixture f;
	Pipe device2 = makePipe();
	f.ops.scan = [&]() {
		return TouchpadScanResult {ScanStatus::OK, {f.device.r, device2.r}};
	};
	int virtualCalls = 0;
	f.ops.createVirtual = [&](int) {
		// First device succeeds, second fails: rollback must release both fds.
		return ++virtualCalls == 1 ? f.virt.w : -1;
	};
	bool failRelease = true;
	f.ops.releaseDevice = [&](int fd) {
		if (failRelease) return false;
		return ::close(fd) == 0;
	};
	f.ops.destroyVirtual = [](int fd) { return ::close(fd) == 0; };

	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::START_INCOMPLETE);
	CHECK_EQ(releasesOf(f.owner), 0); // owner retained for retry

	// No new run while cleanup is unresolved — even from another owner.
	CHECK(life.start(f.otherOwner, f.ops) == StartOutcome::BUSY);

	failRelease = false;
	CHECK(life.stop(f.owner) == StopOutcome::COMPLETED);
	CHECK_EQ(releasesOf(f.owner), 1);
	CHECK(isClosed(f.device.r));
	CHECK(isClosed(device2.r)); // unpaired scan fd also released
}

// ---- stop-time retention + retry -----------------------------------------

void testJoinFailureRetainsThenRetryCompletes() {
	Fixture f;
	std::thread realThread;
	f.ops.spawnWorker = [&realThread](std::function<void()> main) {
		realThread = std::thread(std::move(main));
		return true;
	};
	bool failJoin = true;
	f.ops.joinWorker = [&]() {
		if (failJoin) return false;
		if (realThread.joinable()) realThread.join();
		return true;
	};

	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::STARTED);
	CHECK(life.stop(f.owner) == StopOutcome::INCOMPLETE);
	CHECK_EQ(releasesOf(f.owner), 0); // owner + thread + records retained

	// Retried stop (e.g. from the registry obligation path) now succeeds.
	failJoin = false;
	CHECK(life.stop(f.owner) == StopOutcome::COMPLETED);
	CHECK_EQ(releasesOf(f.owner), 1);
	CHECK(isClosed(f.device.r));
	CHECK(isClosed(f.virt.w));
	CHECK(isClosed(f.wake));
}

void testReleaseDeviceFailureRetainsThenRetryCompletes() {
	Fixture f;
	bool failRelease = true;
	f.ops.releaseDevice = [&](int fd) {
		if (failRelease) return false;
		return ::close(fd) == 0;
	};
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::STARTED);
	CHECK(life.stop(f.owner) == StopOutcome::INCOMPLETE);
	CHECK_EQ(releasesOf(f.owner), 0);
	CHECK(!isClosed(f.device.r)); // retained for retry

	failRelease = false;
	CHECK(life.stop(f.owner) == StopOutcome::COMPLETED);
	CHECK_EQ(releasesOf(f.owner), 1);
	CHECK(isClosed(f.device.r));
}

void testDestroyVirtualFailureRetainsThenRetryCompletes() {
	Fixture f;
	bool failDestroy = true;
	f.ops.destroyVirtual = [&](int fd) {
		if (failDestroy) return false;
		return ::close(fd) == 0;
	};
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::STARTED);
	CHECK(life.stop(f.owner) == StopOutcome::INCOMPLETE);
	CHECK_EQ(releasesOf(f.owner), 0);

	failDestroy = false;
	CHECK(life.stop(f.owner) == StopOutcome::COMPLETED);
	CHECK_EQ(releasesOf(f.owner), 1);
	CHECK(isClosed(f.virt.w));
}

void testCloseWakeFailureRetainsThenRetryCompletes() {
	Fixture f;
	bool failClose = true;
	f.ops.closeWake = [&](int fd) {
		if (failClose) return false;
		return ::close(fd) == 0;
	};
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::STARTED);
	CHECK(life.stop(f.owner) == StopOutcome::INCOMPLETE);

	failClose = false;
	CHECK(life.stop(f.owner) == StopOutcome::COMPLETED);
	CHECK_EQ(releasesOf(f.owner), 1);
	CHECK(isClosed(f.wake));
}

// ---- worker wakeup -------------------------------------------------------

void testStopWakeWriteFailureStillCompletes() {
	Fixture f;
	f.ops.writeWake = [](int) { return false; };
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::STARTED);
	// Poll backstop (1s) ends the worker even without the wake write.
	CHECK(life.stop(f.owner) == StopOutcome::COMPLETED);
	CHECK_EQ(releasesOf(f.owner), 1);
}

void testWorkerExitsWhenAllDevicesDie() {
	Fixture f;
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::STARTED);
	::close(f.device.w); // EOF: worker marks the device dead and exits early
	CHECK(life.stop(f.owner) == StopOutcome::COMPLETED);
	CHECK_EQ(releasesOf(f.owner), 1);
}

void testTransformExceptionDoesNotKillWorker() {
	Fixture f;
	f.ops.transform = [](int, const input_event&) -> std::vector<input_event> {
		throw std::runtime_error("transform exploded");
	};
	TouchpadLifecycle life(ownerOps());
	CHECK(life.start(f.owner, f.ops) == StartOutcome::STARTED);
	input_event in {};
	in.type = EV_KEY;
	::write(f.device.w, &in, sizeof(in)); // swallowed by the worker
	CHECK(life.stop(f.owner) == StopOutcome::COMPLETED);
	CHECK_EQ(releasesOf(f.owner), 1);
}

// ---- destructor ----------------------------------------------------------

void testDestructorJoinsRunningWorker() {
	Fixture f;
	auto* life = new TouchpadLifecycle(ownerOps());
	CHECK(life->start(f.owner, f.ops) == StartOutcome::STARTED);
	delete life; // must join: a joinable std::thread would terminate here
	CHECK_EQ(releasesOf(f.owner), 1);
	CHECK(isClosed(f.device.r));
	CHECK(isClosed(f.virt.w));
	CHECK(isClosed(f.wake));
}

} // namespace

int main() {
	testNoDevice();
	testScanError();
	testAccessError();
	testScanThrows();
	testStartStopHappyPath();
	testBusyAndStaleStop();
	testVirtualCreateFailure();
	testWakeCreateFailure();
	testSpawnFailure();
	testFdZeroIsAValidDeviceFd();
	testStartIncompleteRetainsOwnerAndBlocksNewRuns();
	testJoinFailureRetainsThenRetryCompletes();
	testReleaseDeviceFailureRetainsThenRetryCompletes();
	testDestroyVirtualFailureRetainsThenRetryCompletes();
	testCloseWakeFailureRetainsThenRetryCompletes();
	testStopWakeWriteFailureStillCompletes();
	testWorkerExitsWhenAllDevicesDie();
	testTransformExceptionDoesNotKillWorker();
	testDestructorJoinsRunningWorker();

	if (g_failures != 0) {
		std::fprintf(stderr, "%d check(s) FAILED\n", g_failures);
		return 1;
	}
	std::printf("touchpad_lifecycle_host_tests: all checks passed\n");
	return 0;
}
