#include "touchpad_jni.h"

#include <cstring>
#include <fcntl.h>
#include <linux/input-event-codes.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <unistd.h>

#include <vector>

#include "evdev_common.h"

namespace {

int SetupUinputDevice() {
	struct uinput_setup uinputSetup {};
	int uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);

	if (uinput_fd < 0) return -1;

	/* enable mouse buttons and relative events */
	ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY);
	ioctl(uinput_fd, UI_SET_EVBIT, EV_REL);

	ioctl(uinput_fd, UI_SET_KEYBIT, BTN_MOUSE);
	ioctl(uinput_fd, UI_SET_KEYBIT, BTN_RIGHT);
	ioctl(uinput_fd, UI_SET_KEYBIT, BTN_MIDDLE);

	ioctl(uinput_fd, UI_SET_RELBIT, REL_X);
	ioctl(uinput_fd, UI_SET_RELBIT, REL_Y);

	memset(&uinputSetup, 0, sizeof(uinputSetup));

	strncpy(uinputSetup.name, x_virtual_mouse, strlen(x_virtual_mouse));
	uinputSetup.id.version = 1;
	uinputSetup.id.bustype = BUS_VIRTUAL;
	uinputSetup.id.vendor = 0x1234; /* sample vendor */
   	uinputSetup.id.product = 0x5678; /* sample product */

	ioctl(uinput_fd, UI_DEV_SETUP, &uinputSetup);

	if(ioctl(uinput_fd, UI_DEV_CREATE)) {
		close(uinput_fd);
		return -1;
	}
	return uinput_fd;
}

struct RelativeBaseline {
	int lastX = 0;
	int lastY = 0;
	bool touchDown = false;
	bool hasX = false;
	bool hasY = false;
};

thread_local std::vector<RelativeBaseline> baselines;

std::vector<input_event> relativeTransform(int evdevIndex, const input_event& event) {
	std::vector<input_event> out;
	if (evdevIndex >= 0 && static_cast<size_t>(evdevIndex) >= baselines.size())
		baselines.resize(static_cast<size_t>(evdevIndex) + 1);
	RelativeBaseline& state = baselines[evdevIndex];

	if (event.type == EV_ABS) {
		int* last = nullptr;
		bool* has = nullptr;
		if (event.code == ABS_X) { last = &state.lastX; has = &state.hasX; }
		else if (event.code == ABS_Y) { last = &state.lastY; has = &state.hasY; }

		if (last != nullptr && state.touchDown && event.value > 1) {
			input_event ie {};
			ie.type = EV_REL;
			ie.code = (last == &state.lastX) ? REL_X : REL_Y;
			if (!*has) {
				// Seed the baseline; emit nothing for the first sample.
				*last = event.value;
				*has = true;
			} else {
				ie.value = event.value - *last;
				*last = event.value;
				out.push_back(ie);
			}
		}
	} else if (event.type == EV_KEY) {
		switch (event.code) {
			case BTN_MOUSE:
			case BTN_RIGHT:
			case BTN_MIDDLE:
				out.push_back(event);
			break;

			case BTN_TOUCH:
				state.touchDown = event.value == 1;
				state.hasX = false;
				state.hasY = false;
			break;
		}
	}

	input_event syn {};
	syn.type = EV_SYN;
	syn.code = SYN_REPORT;
	out.push_back(syn);
	return out;
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
Java_xtr_keymapper_server_InputService_startTouchpadRelative(JNIEnv *env, jobject thiz) {
	try {
		touchpad::LifecycleOps ops = touchpad::withCommonOps({});
		// One virtual mouse for all devices; the evdev fd is unused by the fixed setup.
		ops.createVirtual = [](int /*device_fd*/) { return SetupUinputDevice(); };
		ops.transform = [](int index, const input_event& in) { return relativeTransform(index, in); };
		return touchpad::startTouchpad(env, thiz, lifecycle(), std::move(ops));
	} catch (...) {
		return static_cast<jint>(touchpad::StartOutcome::INTERNAL_ERROR);
	}
}

extern "C"
JNIEXPORT jint JNICALL
Java_xtr_keymapper_server_InputService_stopTouchpadRelative(JNIEnv *env, jobject thiz) {
	try {
		return touchpad::stopTouchpad(env, thiz, lifecycle());
	} catch (...) {
		// The probe never ran: report the retained-resource outcome.
		return static_cast<jint>(touchpad::StopOutcome::INCOMPLETE);
	}
}
