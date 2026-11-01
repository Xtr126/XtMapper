// Shared JNI glue for the two touchpad modules. JNI stays out of the lifecycle
// core and the scanner; this header only adapts owner tokens and common ops.
#pragma once

#include <jni.h>

#include <cstdint>
#include <functional>
#include <memory>
#include <utility>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <linux/uinput.h>
#include <unistd.h>

#include "evdev_common.h"
#include "touchpad_lifecycle.h"

namespace touchpad {

// Owner token = heap JniOwner holding a NewGlobalRef to the InputService
// instance that started the run. Identity is compared with IsSameObject, so a
// stop from any other instance (or a newer session) can never stop this run.
struct JniOwner {
	JavaVM* vm = nullptr;
	jobject ref = nullptr;
};

inline JniOwner* acquireOwner(JNIEnv* env, jobject thiz) {
	// Allocate the token before taking the GlobalRef: a bad_alloc must not
	// leak the reference acquired below.
	auto owner = std::make_unique<JniOwner>();
	jobject ref = env->NewGlobalRef(thiz);
	if (ref == nullptr) return nullptr; // OutOfMemoryError already pending
	JavaVM* vm = nullptr;
	env->GetJavaVM(&vm);
	if (vm == nullptr) {
		env->DeleteGlobalRef(ref);
		return nullptr;
	}
	owner->vm = vm;
	owner->ref = ref;
	return owner.release();
}

inline bool sameOwner(const void* a, const void* b) {
	if (a == b) return true;
	if (a == nullptr || b == nullptr) return false;
	auto* oa = static_cast<const JniOwner*>(a);
	auto* ob = static_cast<const JniOwner*>(b);
	if (oa->vm != ob->vm) return false;
	JNIEnv* env = nullptr;
	if (oa->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr)
		return false;
	return env->IsSameObject(oa->ref, ob->ref);
}

inline void releaseOwner(void* token) {
	auto* owner = static_cast<JniOwner*>(token);
	if (owner == nullptr) return;
	JNIEnv* env = nullptr;
	if (owner->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK && env != nullptr)
		env->DeleteGlobalRef(owner->ref);
	// Not attached: the global ref dies with the process; free the token itself.
	delete owner;
}

// The lifecycle takes ownership of the token on every outcome that reaches
// it and releases it exactly once via releaseOwner — including BUSY, whose
// rejected token is released immediately inside start(). Only
// OWNER_REF_FAILED returns before any token exists.
inline jint startTouchpad(JNIEnv* env, jobject thiz, TouchpadLifecycle& lifecycle, LifecycleOps ops) {
	JniOwner* owner = acquireOwner(env, thiz);
	if (owner == nullptr) return static_cast<jint>(StartOutcome::OWNER_REF_FAILED);
	try {
		return static_cast<jint>(lifecycle.start(owner, std::move(ops)));
	} catch (...) {
		// Exceptions must not cross the JNI boundary (Android aborts).
		// start() resolves token adoption internally, so this is a
		// mapping-only backstop and must never release here.
		return static_cast<jint>(StartOutcome::INTERNAL_ERROR);
	}
}

// Stop identity is a stack probe holding a local ref; IsSameObject works with
// local refs, and nothing is acquired so nothing needs releasing.
inline jint stopTouchpad(JNIEnv* env, jobject thiz, TouchpadLifecycle& lifecycle) {
	JavaVM* vm = nullptr;
	env->GetJavaVM(&vm);
	JniOwner probe {vm, thiz};
	try {
		return static_cast<jint>(lifecycle.stop(&probe));
	} catch (...) {
		// INCOMPLETE retains resources: the safe interrupted-teardown
		// outcome never claims the run was cleaned up.
		return static_cast<jint>(StopOutcome::INCOMPLETE);
	}
}

// Fills the ops entries both modules share verbatim.
inline LifecycleOps withCommonOps(LifecycleOps ops) {
	// The scanner's result type and the lifecycle's are separate by design;
	// the status values are declared in the same order (asserted below).
	static_assert(static_cast<int>(touchpad::ScanStatus::ACCESS_ERROR) ==
	              static_cast<int>(TouchpadScanStatus::ACCESS_ERROR));
	ops.scan = []() {
		::TouchpadScanResult r = scanTouchpadDevices();
		return touchpad::TouchpadScanResult {
			static_cast<touchpad::ScanStatus>(r.status),
			std::move(r.fds),
		};
	};
	ops.createWake = []() { return eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC); };
	ops.closeWake = [](int fd) {
		// Linux frees the fd even when close() reports an error (EINTR,
		// EBADF): after any close attempt the number is never ours again,
		// so reporting "retained for retry" could later close an unrelated
		// descriptor that reused it. Same contract as destroyVirtual.
		::close(fd);
		return true;
	};
	ops.writeWake = [](int fd) {
		uint64_t one = 1;
		return ::write(fd, &one, sizeof(one)) == static_cast<ssize_t>(sizeof(one));
	};
	ops.releaseDevice = [](int fd) {
		::ioctl(fd, EVIOCGRAB, reinterpret_cast<void*>(0));
		// Linux releases the grab and the fd on close; always forget the
		// record — a retry could EVIOCGRAB/close a reused descriptor number.
		::close(fd);
		return true;
	};
	ops.destroyVirtual = [](int fd) {
		::ioctl(fd, UI_DEV_DESTROY);
		::close(fd);
		return true;
	};
	return ops;
}

} // namespace touchpad
