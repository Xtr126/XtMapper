#include <jni.h>

#include <linux/input.h>
#include <linux/uinput.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <cstdio>
#include "mouse_cursor.h"

int uinput_fd = -1;
struct input_event ie {};
const char* device_name = x_virtual_tablet;

void setAbsMinMax(int width, int height) {
	struct uinput_abs_setup uinputAbsSetup {};

	memset(&uinputAbsSetup, 0x00, sizeof(uinputAbsSetup));
	uinputAbsSetup.code = ABS_X;
	uinputAbsSetup.absinfo = input_absinfo {0, 0, width, 0 , 0};
	ioctl(uinput_fd, UI_ABS_SETUP, &uinputAbsSetup);

	uinputAbsSetup.code = ABS_Y;
	uinputAbsSetup.absinfo = input_absinfo {0, 0, height, 0 , 0};
	ioctl(uinput_fd, UI_ABS_SETUP, &uinputAbsSetup);
}

extern "C" JNIEXPORT jint JNICALL
Java_xtr_keymapper_server_InputService_initMouseCursor
(JNIEnv * /*env*/, jobject /*obj*/, jint width, jint height) {
	struct uinput_setup uinputSetup {};
	uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);

	if (uinput_fd <= 0) return uinput_fd;

	memset(&ie, 0, sizeof(struct input_event));
	memset(&uinputSetup, 0x00, sizeof(uinputSetup));

	strncpy(uinputSetup.name, device_name, strlen(device_name));
	uinputSetup.id.version = 1;
	uinputSetup.id.bustype = BUS_VIRTUAL;
	setAbsMinMax(width, height);

	ioctl(uinput_fd, UI_SET_EVBIT, EV_ABS);
	ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY);
	ioctl(uinput_fd, UI_SET_EVBIT, EV_REL);
	ioctl(uinput_fd, UI_SET_PROPBIT, INPUT_PROP_POINTER);

	ioctl(uinput_fd, UI_SET_ABSBIT, ABS_X);
	ioctl(uinput_fd, UI_SET_ABSBIT, ABS_Y);
	ioctl(uinput_fd, UI_SET_RELBIT, REL_WHEEL);

	ioctl(uinput_fd, UI_SET_KEYBIT, BTN_MOUSE);
	ioctl(uinput_fd, UI_SET_KEYBIT, BTN_RIGHT);
	ioctl(uinput_fd, UI_SET_KEYBIT, BTN_WHEEL);

	ioctl(uinput_fd, UI_DEV_SETUP, &uinputSetup);

	if(ioctl(uinput_fd, UI_DEV_CREATE)) {
		close(uinput_fd);
		return -1;
	}
	return 1;
}

void report() {
    ie.type = EV_SYN;
    ie.code = SYN_REPORT;
    ie.value = 0;
    write(uinput_fd, &ie, sizeof(struct input_event));
}

extern "C" JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_cursorSetX
(JNIEnv * /*env*/, jobject /*obj*/, jint x) {
	ie.type = EV_ABS;
	ie.code = ABS_X;
	ie.value = x;
	write(uinput_fd, &ie, sizeof(struct input_event));
	report();
}

extern "C" JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_cursorSetY
(JNIEnv * /*env*/, jobject /*obj*/, jint y) {
	ie.type = EV_ABS;
	ie.code = ABS_Y;
	ie.value = y;
	write(uinput_fd, &ie, sizeof(struct input_event));
	report();
}

extern "C" JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_destroyUinputDev (JNIEnv * /*env*/, jobject /*obj*/) {
	if (uinput_fd > 0) {
		ioctl(uinput_fd, UI_DEV_DESTROY);
		close(uinput_fd);
		uinput_fd = -1;
	}
}