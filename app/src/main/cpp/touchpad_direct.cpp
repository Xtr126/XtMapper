#include "touchpad_jni.h"

#include <cstring>
#include <fcntl.h>
#include <linux/input-event-codes.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <unistd.h>

#include <vector>

#include "evdev_common.h"
#include "mouse_cursor.h"

namespace {

// Builds the per-device virtual touchpad. Returns the uinput fd, or -1 on
// failure (no exit(): a failed device must not kill the server process).
int SetupUinputDevice(int device_fd) {
	struct uinput_setup uinputSetup {};
	int uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);

	if (uinput_fd < 0) return -1;

	for(int ev_i = EV_SYN; ev_i <= EV_MAX; ev_i++) {
		if (HasEventType(device_fd, ev_i)) {
			ioctl(uinput_fd, UI_SET_EVBIT, ev_i);
		}
	}
	for(int key_i = BTN_MOUSE; key_i <= KEY_MAX; key_i++) {
		if (HasSpecificKey(device_fd, key_i)) {
			ioctl(uinput_fd, UI_SET_KEYBIT, key_i);
		}
	}
	if(!HasSpecificKey(device_fd, BTN_TOUCH)) {
		ioctl(uinput_fd, UI_SET_KEYBIT, BTN_TOUCH);
	}
	for(int abs_i = ABS_X; abs_i <= ABS_MAX; abs_i++) {
		if(HasSpecificAbs(device_fd, abs_i)) {
			struct input_absinfo absinfo {};
			if (ioctl(device_fd, EVIOCGABS(abs_i), &absinfo) == 0) {
				struct uinput_abs_setup uinputAbsInfo {};
				memset(&uinputAbsInfo, 0, sizeof(uinputAbsInfo));
				uinputAbsInfo.code = abs_i;
				uinputAbsInfo.absinfo = absinfo;
				ioctl(uinput_fd, UI_ABS_SETUP, &uinputAbsInfo);
			}
		}
	}
	ioctl(uinput_fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

	memset(&uinputSetup, 0, sizeof(uinputSetup));

	strncpy(uinputSetup.name, x_virtual_touch, strlen(x_virtual_touch));
	uinputSetup.id.version = 1;
	uinputSetup.id.bustype = BUS_VIRTUAL;
	ioctl(uinput_fd, UI_DEV_SETUP, &uinputSetup);

	if(ioctl(uinput_fd, UI_DEV_CREATE)) {
		close(uinput_fd);
		return -1;
	}
	return uinput_fd;
}

// Direct mode rewrites every key event to BTN_TOUCH and passes the rest through.
std::vector<input_event> directTransform(int /*evdevIndex*/, const input_event& in) {
	input_event out = in;
	if (in.type == EV_KEY) out.code = BTN_TOUCH;
	return {out};
}

touchpad::TouchpadLifecycle& lifecycle() {
	static touchpad::TouchpadLifecycle instance({
		/*same=*/touchpad::sameOwner,
		/*release=*/touchpad::releaseOwner,
	});
	return instance;
}

} // namespace

extern "C"
JNIEXPORT jint JNICALL
Java_xtr_keymapper_server_InputService_startTouchpadDirect(JNIEnv *env, jobject thiz) {
	try {
		touchpad::LifecycleOps ops = touchpad::withCommonOps({});
		ops.createVirtual = [](int device_fd) { return SetupUinputDevice(device_fd); };
		ops.transform = [](int index, const input_event& in) { return directTransform(index, in); };
		return touchpad::startTouchpad(env, thiz, lifecycle(), std::move(ops));
	} catch (...) {
		// Nothing may cross the JNI boundary: ops construction and the
		// lifecycle() static can allocate before any owner token exists.
		return static_cast<jint>(touchpad::StartOutcome::INTERNAL_ERROR);
	}
}

extern "C"
JNIEXPORT jint JNICALL
Java_xtr_keymapper_server_InputService_stopTouchpadDirect(JNIEnv *env, jobject thiz) {
	try {
		return touchpad::stopTouchpad(env, thiz, lifecycle());
	} catch (...) {
		// The probe never ran: report the retained-resource outcome.
		return static_cast<jint>(touchpad::StopOutcome::INCOMPLETE);
	}
}
