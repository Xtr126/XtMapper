#include "touchpad_lifecycle.h"

#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <poll.h>
#include <unistd.h>

namespace touchpad {
namespace {
constexpr int kPollBackstopMs = 1000;
// Upper bound of events drained per wake: keeps stop() latency bounded even
// when a device emits at line rate (the cancel check is the primary exit).
constexpr int kMaxDrainPerWake = 128;
constexpr auto kStopWait = std::chrono::milliseconds(5000);
} // namespace

TouchpadLifecycle::TouchpadLifecycle(OwnerOps ownerOps) : ownerOps_(std::move(ownerOps)) {}

TouchpadLifecycle::~TouchpadLifecycle() {
	// Must join: destroying a joinable std::thread terminates the process.
	// The worker is guaranteed to exit once cancel_ is set (poll backstop).
	stopInternal(nullptr, false);
	if (joinable_) {
		try {
			if (ops_.joinWorker()) joinable_ = false;
		} catch (...) {
		}
	}
	if (joinable_ && workerThread_.joinable()) workerThread_.join();
}

bool TouchpadLifecycle::shouldStop() {
	std::lock_guard<std::mutex> lk(mu_);
	return cancel_;
}

void TouchpadLifecycle::notifyExited() {
	std::lock_guard<std::mutex> lk(mu_);
	exited_ = true;
	cv_.notify_all();
}

void TouchpadLifecycle::workerMain() {
	try {
		workerLoop();
	} catch (...) {
		// Containment: an escaping exception (allocation failure inside the
		// loop) would std::terminate the server process; the exit signal
		// below is what stop() and the destructor reap on every path.
	}
	notifyExited();
}

void TouchpadLifecycle::workerLoop() {
	struct Dev {
		int evdev;
		int virt;
		bool dead;
	};
	std::vector<Dev> local;
	int wake;
	{
		std::lock_guard<std::mutex> lk(mu_);
		local.reserve(devices_.size());
		for (const auto& d : devices_) local.push_back({d.evdev, d.virt, false});
		wake = wakeFd_;
	}

	std::vector<pollfd> pfds;
	auto rebuild = [&]() {
		pfds.clear();
		pfds.push_back(pollfd{wake, POLLIN, 0});
		for (const auto& d : local)
			if (!d.dead) pfds.push_back(pollfd{d.evdev, POLLIN, 0});
	};
	rebuild();

	for (;;) {
		if (shouldStop()) break;

		int n = ::poll(pfds.data(), static_cast<nfds_t>(pfds.size()), kPollBackstopMs);
		if (n < 0) {
			if (errno == EINTR) continue;
			break; // unexpected poll failure: exit and let stop() reap
		}
		if (n == 0) continue; // backstop timeout: re-check cancel

		if (pfds[0].revents != 0) break; // wake fd: shutdown requested

		for (size_t i = 1; i < pfds.size(); i++) {
			short re = pfds[i].revents;
			if (re == 0) continue;
			Dev* dev = nullptr;
			size_t index = 0;
			for (size_t j = 0; j < local.size(); j++) {
				if (local[j].evdev == pfds[i].fd) {
					dev = &local[j];
					index = j;
					break;
				}
			}
			if (dev == nullptr) continue;

			if (re & POLLIN) {
				input_event in {};
				ssize_t r = 0;
				bool readFailed = false;
				// Cancel-aware and bounded: a continuously readable fd must
				// not starve the wake/poll cycle that stop() and the
				// destructor rely on.
				for (int budget = kMaxDrainPerWake; budget-- > 0 && !shouldStop();) {
					r = ::read(dev->evdev, &in, sizeof(in));
					if (r != static_cast<ssize_t>(sizeof(in))) {
						readFailed = true;
						break;
					}
					std::vector<input_event> outs;
					try {
						outs = ops_.transform(static_cast<int>(index), in);
					} catch (...) {
						outs.clear();
					}
					for (const auto& out : outs)
						::write(dev->virt, &out, sizeof(out));
				}
				// EOF or a hard read error (ENODEV, ...): the device is gone.
				// Do not close here; the record stays for stop() to release.
				// Budget/cancel exits skip this: their read did not fail.
				if (readFailed) {
					if (r == 0) dev->dead = true;
					else if (errno != EAGAIN && errno != EINTR) dev->dead = true;
				}
			}
			if (re & (POLLERR | POLLHUP | POLLNVAL)) dev->dead = true;
		}

		bool anyAlive = false;
		for (const auto& d : local)
			if (!d.dead) anyAlive = true;
		if (!anyAlive) break; // all devices dead: exit early, state stays RUNNING until reaped

		rebuild();
	}
}

bool TouchpadLifecycle::cleanupResources() {
	if (joinable_) {
		bool joined = false;
		try {
			joined = ops_.joinWorker();
		} catch (...) {
			joined = false;
		}
		// Never destroy resources while the worker may still touch them.
		if (!joined) return false;
		joinable_ = false;
	}

	bool ok = true;
	for (auto& d : devices_) {
		// Each fd tracks independently: a retry after a partial cleanup must
		// still release whatever was retained.
		if (d.virt >= 0) {
			bool destroyed = false;
			try {
				destroyed = ops_.destroyVirtual(d.virt);
			} catch (...) {
			}
			if (destroyed) d.virt = -1;
			else ok = false;
		}
		if (d.evdev >= 0) {
			bool released = false;
			try {
				released = ops_.releaseDevice(d.evdev);
			} catch (...) {
			}
			if (released) d.evdev = -1;
			else ok = false;
		}
	}
	if (wakeFd_ >= 0) {
		bool closed = false;
		try {
			closed = ops_.closeWake(wakeFd_);
		} catch (...) {
		}
		if (closed) wakeFd_ = -1;
		else ok = false;
	}
	if (ok) devices_.clear();
	return ok;
}

StartOutcome TouchpadLifecycle::abortStart(StartOutcome mapped) {
	void* old;
	{
		std::lock_guard<std::mutex> lk(mu_);
		old = owner_;
		owner_ = nullptr;
		cancel_ = false;
		state_ = State::STOPPED;
		cv_.notify_all();
	}
	if (old != nullptr) ownerOps_.release(old);
	return mapped;
}

StartOutcome TouchpadLifecycle::rollbackStart(StartOutcome mapped) {
	{
		std::lock_guard<std::mutex> lk(mu_);
		cleanupActive_ = true;
	}
	bool clean = cleanupResources();
	std::unique_lock<std::mutex> lk(mu_);
	cleanupActive_ = false;
	if (clean) {
		void* old = owner_;
		owner_ = nullptr;
		cancel_ = false;
		state_ = State::STOPPED;
		devices_.clear();
		wakeFd_ = -1;
		cv_.notify_all();
		lk.unlock();
		if (old != nullptr) ownerOps_.release(old);
		return mapped;
	}
	// Retain owner + resource records so a later stop()/start() can retry.
	state_ = State::STOPPING;
	cv_.notify_all();
	return StartOutcome::START_INCOMPLETE;
}

StartOutcome TouchpadLifecycle::start(void* owner, LifecycleOps ops) {
	std::unique_lock<std::mutex> lk(mu_);
	// BUSY covers STARTING, RUNNING and unresolved STOPPING (cleanup pending):
	// no new run while cleanup is unresolved.
	if (state_ != State::STOPPED) {
		// The token was never adopted; start() owns every token it is handed.
		// Released outside mu_: the op may call back into the JVM.
		lk.unlock();
		if (owner != nullptr) ownerOps_.release(owner);
		return StartOutcome::BUSY;
	}
	state_ = State::STARTING;
	owner_ = owner;
	cancel_ = false;
	try {
		ops_ = std::move(ops);
		if (!ops_.spawnWorker)
			ops_.spawnWorker = [this](std::function<void()> main) {
				workerThread_ = std::thread(std::move(main));
				return true;
			};
		if (!ops_.joinWorker)
			ops_.joinWorker = [this]() {
				if (workerThread_.joinable()) workerThread_.join();
				return true;
			};
	} catch (...) {
		// std::function assignment can allocate; nothing is acquired yet,
		// so a throw must not wedge the state in STARTING.
		lk.unlock();
		return abortStart(StartOutcome::INTERNAL_ERROR);
	}
	lk.unlock();

	TouchpadScanResult scan;
	try {
		scan = ops_.scan();
	} catch (...) {
		scan.status = ScanStatus::SCAN_ERROR;
		scan.fds.clear();
	}
	switch (scan.status) {
		case ScanStatus::NO_DEVICE: return abortStart(StartOutcome::NO_DEVICE);
		case ScanStatus::SCAN_ERROR: return abortStart(StartOutcome::SCAN_ERROR);
		case ScanStatus::ACCESS_ERROR: return abortStart(StartOutcome::ACCESS_ERROR);
		case ScanStatus::OK: break;
	}

	// Adopt every scan fd up front so a mid-way createVirtual failure still
	// releases the evdev fds that were never paired with a virtual device.
	std::vector<DeviceRecord> acquired;
	try {
		// reserve is the only throwing operation: DeviceRecord is trivially
		// copyable, so the push_back loop below cannot throw.
		acquired.reserve(scan.fds.size());
	} catch (...) {
		// Still the sole owner of the scan fds: release them, then drop the
		// adopted token instead of wedging STARTING forever.
		for (int fd : scan.fds) {
			try {
				ops_.releaseDevice(fd);
			} catch (...) {
			}
		}
		return abortStart(StartOutcome::INTERNAL_ERROR);
	}
	for (int fd : scan.fds) acquired.push_back(DeviceRecord {fd, -1});
	int wake = -1;
	bool failed = false;
	for (auto& record : acquired) {
		int virt = -1;
		try {
			virt = ops_.createVirtual(record.evdev);
		} catch (...) {
		}
		if (virt < 0) {
			failed = true;
			break;
		}
		record.virt = virt;
	}
	if (!failed) {
		try {
			wake = ops_.createWake();
		} catch (...) {
		}
		if (wake < 0) failed = true;
	}

	{
		std::lock_guard<std::mutex> lk2(mu_);
		devices_ = std::move(acquired);
		wakeFd_ = wake;
	}
	if (failed) return rollbackStart(StartOutcome::ACQUISITION_FAILED);

	// Cancel check + spawn + RUNNING publish in one critical section so a stop
	// either observes a fully-published RUNNING worker or a clean rollback.
	bool spawned = false;
	lk.lock();
	if (cancel_) {
		lk.unlock();
		return rollbackStart(StartOutcome::CANCELLED);
	}
	try {
		spawned = ops_.spawnWorker([this]() { workerMain(); });
	} catch (...) {
	}
	if (!spawned) {
		lk.unlock();
		return rollbackStart(StartOutcome::THREAD_FAILED);
	}
	joinable_ = true;
	exited_ = false;
	state_ = State::RUNNING;
	cv_.notify_all();
	return StartOutcome::STARTED;
}

StopOutcome TouchpadLifecycle::stopInternal(void* owner, bool enforceOwner) {
	std::unique_lock<std::mutex> lk(mu_);
	for (;;) {
		if (state_ == State::STOPPED) return StopOutcome::ALREADY_STOPPED;

		// Identity before any side effect, re-checked after every wait via
		// `continue`: a stale owner must never cancel or wait on a run (or a
		// startup) that belongs to someone else. owner_ and state_ always
		// transition together under mu_, so this check is exact.
		if (enforceOwner && !ownerOps_.same(owner_, owner)) return StopOutcome::NOT_OWNER;

		if (state_ == State::STARTING) {
			cancel_ = true;
			if (!cv_.wait_for(lk, kStopWait, [this]() { return state_ != State::STARTING; }))
				return StopOutcome::INCOMPLETE; // start wedged mid-acquisition; it retains ownership
			continue;
		}
		if (state_ == State::RUNNING) break;

		// STOPPING
		if (cleanupActive_) {
			// Another stopper owns the teardown; wait bounded for it to finish.
			if (!cv_.wait_for(lk, kStopWait, [this]() { return state_ != State::STOPPING; }))
				return StopOutcome::INCOMPLETE;
			continue;
		}
		break; // unresolved cleanup from a previous INCOMPLETE: retry below
	}

	state_ = State::STOPPING;
	cleanupActive_ = true;
	// The contract: the worker must leave within one poll backstop after this,
	// even when the wake write fails.
	cancel_ = true;
	int wake = wakeFd_;
	lk.unlock();

	if (wake >= 0) {
		// Best effort: even on failure the poll backstop ends the worker.
		try {
			ops_.writeWake(wake);
		} catch (...) {
		}
	}

	lk.lock();
	bool exited = cv_.wait_for(lk, kStopWait, [this]() { return exited_; });
	lk.unlock();

	// Join + destroy + release run outside the mutex: the worker needs it.
	bool clean = exited && cleanupResources();

	lk.lock();
	if (clean) {
		void* old = owner_;
		owner_ = nullptr;
		cancel_ = false;
		state_ = State::STOPPED;
		cleanupActive_ = false;
		devices_.clear();
		wakeFd_ = -1;
		cv_.notify_all();
		lk.unlock();
		if (old != nullptr) ownerOps_.release(old);
		return StopOutcome::COMPLETED;
	}
	// Retain owner + thread + resource records for a later retry.
	cleanupActive_ = false;
	cv_.notify_all();
	return StopOutcome::INCOMPLETE;
}

StopOutcome TouchpadLifecycle::stop(void* owner) {
	return stopInternal(owner, true);
}

} // namespace touchpad
