#include <jni.h>

#include <string>
#include <linux/input.h>
#include <linux/uinput.h>
#include <fcntl.h>
#include <unistd.h>


int uinput_fd = -1;
struct input_event ie {};



extern "C" JNIEXPORT jint JNICALL
Java_xtr_keymapper_server_InputService_initMouseCursor(JNIEnv *env, jobject thiz, jint width,
													   jint height) {

  	uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    struct uinput_user_dev uinputUserDev {};

    if(uinput_fd <= 0) {
		return uinput_fd;
	}
    
	memset(&uinputUserDev, 0x00, sizeof(uinputUserDev));
	strncpy(uinputUserDev.name, "x-virtual-tablet", strlen("x-virtual-tablet"));
    uinputUserDev.id.version = 1;
    uinputUserDev.id.bustype = BUS_VIRTUAL;

    uinputUserDev.absmin[ABS_X] = 0;
    uinputUserDev.absmax[ABS_X] = width;
    uinputUserDev.absfuzz[ABS_X] = 0;
    uinputUserDev.absflat[ABS_X] = 0;

    uinputUserDev.absmin[ABS_Y] = 0;
    uinputUserDev.absmax[ABS_Y] = height;
    uinputUserDev.absfuzz[ABS_Y] = 0;
    uinputUserDev.absflat[ABS_Y] = 0;

	ioctl(uinput_fd, UI_SET_EVBIT, EV_ABS);
	ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY);
	ioctl(uinput_fd, UI_SET_EVBIT, EV_REL);
	ioctl(uinput_fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

	ioctl(uinput_fd, UI_SET_ABSBIT, ABS_X);
	ioctl(uinput_fd, UI_SET_ABSBIT, ABS_Y);
	ioctl(uinput_fd, UI_SET_RELBIT, REL_WHEEL);

	ioctl(uinput_fd, UI_SET_KEYBIT, BTN_MOUSE);
	ioctl(uinput_fd, UI_SET_KEYBIT, BTN_RIGHT);
	ioctl(uinput_fd, UI_SET_KEYBIT, BTN_WHEEL);

	if(write(uinput_fd, &uinputUserDev, sizeof(uinputUserDev)) != sizeof(uinputUserDev))
	{
		close(uinput_fd);
		uinput_fd = -1;
		return uinput_fd;
	}

	if(ioctl(uinput_fd, UI_DEV_CREATE))
	{
		close(uinput_fd);
		uinput_fd = -1;
		return uinput_fd;
	}

	memset(&ie, 0, sizeof(struct input_event));
	return 1;
}

void report() {
    ie.type = EV_SYN;
    ie.code = SYN_REPORT;
    ie.value = 0;
    write(uinput_fd, &ie, sizeof(struct input_event));
}

extern "C" JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_cursorSetX(JNIEnv *env, jobject thiz, jint x) {
	ie.type = EV_ABS;
	ie.code = ABS_X;
	ie.value = x;
	write(uinput_fd, &ie, sizeof(struct input_event));
	report();
}

extern "C" JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_cursorSetY(JNIEnv *env, jobject thiz, jint y) {
	ie.type = EV_ABS;
	ie.code = ABS_Y;
	ie.value = y;
	write(uinput_fd, &ie, sizeof(struct input_event));
	report();
}
