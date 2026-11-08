#pragma once

#include <condition_variable>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

#include <linux/input.h>

namespace touchpad {

enum class StartOutcome : int {
	STARTED = 0,
	NO_DEVICE = 1,
	BUSY = 2,
	SCAN_ERROR = 3,
	ACCESS_ERROR = 4,
	ACQUISITION_FAILED = 5,
	OWNER_REF_FAILED = 6,
	THREAD_FAILED = 7,
	CANCELLED = 8,
	INTERNAL_ERROR = 9,
	START_INCOMPLETE = 10,
};

enum class StopOutcome : int {
	COMPLETED = 0,
	ALREADY_STOPPED = 1,
	NOT_OWNER = 2,
	INCOMPLETE = 3,
};

enum class ScanStatus { OK, NO_DEVICE, SCAN_ERROR, ACCESS_ERROR };

struct TouchpadScanResult {
	ScanStatus status = ScanStatus::NO_DEVICE;
	std::vector<int> fds;
};

struct LifecycleOps {
	std::function<TouchpadScanResult()> scan;
	std::function<int(int evdevFd)> createVirtual;       // fd >= 0, or -1
	std::function<bool(int fd)> destroyVirtual;          // true = released
	std::function<bool(int fd)> releaseDevice;           // ungrab + close
	std::function<int()> createWake;                      // wake fd, or -1
	std::function<bool(int fd)> closeWake;
	std::function<bool(int fd)> writeWake;
	std::function<bool(std::function<void()> workerMain)> spawnWorker;
	std::function<bool()> joinWorker;                     // false = INCOMPLETE; never detached
	std::function<std::vector<input_event>(int evdevIndex, const input_event& in)> transform;
};

class TouchpadLifecycle {
public:
	struct OwnerOps {
		std::function<bool(const void*, const void*)> same;
		std::function<void(void*)> release;
	};

	explicit TouchpadLifecycle(OwnerOps ownerOps);
	~TouchpadLifecycle();

	TouchpadLifecycle(const TouchpadLifecycle&) = delete;
	TouchpadLifecycle& operator=(const TouchpadLifecycle&) = delete;

	StartOutcome start(void* owner, LifecycleOps ops);
	StopOutcome stop(void* owner);

private:
	enum class State { STOPPED, STARTING, RUNNING, STOPPING };

	struct DeviceRecord {
		int evdev = -1;
		int virt = -1;
	};

	bool shouldStop();
	void notifyExited();
	// Contains the poll/drain loop; exceptions are absorbed by workerMain.
	void workerLoop();
	void workerMain();
	bool cleanupResources();
	// Mutex NOT held. Full rollback after resources were published.
	StartOutcome rollbackStart(StartOutcome mapped);
	// Mutex NOT held. Nothing acquired yet: drop the owner and return to STOPPED.
	StartOutcome abortStart(StartOutcome mapped);
	StopOutcome stopInternal(void* owner, bool enforceOwner);

	OwnerOps ownerOps_;
	std::mutex mu_;
	std::condition_variable cv_;
	State state_ = State::STOPPED;
	void* owner_ = nullptr;
	LifecycleOps ops_;
	std::vector<DeviceRecord> devices_;
	int wakeFd_ = -1;
	bool cancel_ = false;
	bool exited_ = true;         // worker not running
	bool cleanupActive_ = false; // a stop/rollback currently owns the teardown
	bool joinable_ = false;      // a spawned worker still needs a successful join
	std::thread workerThread_;   // used only by the default spawn/join
};

} // namespace touchpad
