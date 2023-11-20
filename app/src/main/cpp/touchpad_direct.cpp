#include <cstdlib>
#include <linux/input-event-codes.h>
#include <linux/input.h>
#include <linux/uinput.h>

#include <sys/poll.h>
#include <fcntl.h>
#include <unistd.h>
#include <jni.h>

#include <dirent.h>
#include <cstring>
#include <cstdio>
#include <iostream>
#include <vector>
#include <thread>
#include "mouse_cursor.h"
#include "evdev_common.h"

using std::string;

struct input_event ie {};

std::atomic<bool> running = false;

std::thread looper;

std::vector<pollfd> poll_fds;
std::vector<int> uinput_fds;

void SetAbsInfoFrom(int device_fd, int uinput_fd) {
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
}

void SetKeyBits(int device_fd, int uinput_fd) {
	for(int key_i = BTN_MOUSE; key_i <= KEY_MAX; key_i++) {
		if (HasSpecificKey(device_fd, key_i)) {
			ioctl(uinput_fd, UI_SET_KEYBIT, key_i);
		}
	}
	if(!HasSpecificKey(device_fd, BTN_TOUCH)) {
		ioctl(uinput_fd, UI_SET_KEYBIT, BTN_TOUCH);
	}
}


void SetEventTypeBits(int device_fd, int uinput_fd) {
	for(int ev_i = EV_SYN; ev_i <= EV_MAX; ev_i++) {
		if (HasEventType(device_fd, ev_i)) {
			ioctl(uinput_fd, UI_SET_EVBIT, ev_i);
		}
	}
}

int SetupUinputDevice(int device_fd) {
	struct uinput_setup uinputSetup {};
	int uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);

	if (uinput_fd <= 0) exit(EXIT_FAILURE);

	SetEventTypeBits(device_fd, uinput_fd);
	SetKeyBits(device_fd, uinput_fd);
	SetAbsInfoFrom(device_fd, uinput_fd);
	ioctl(uinput_fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

	memset(&uinputSetup, 0, sizeof(uinputSetup));

	strncpy(uinputSetup.name, x_virtual_touch, strlen(x_virtual_touch));
	uinputSetup.id.version = 1;
	uinputSetup.id.bustype = BUS_VIRTUAL;
	ioctl(uinput_fd, UI_DEV_SETUP, &uinputSetup);

	if(ioctl(uinput_fd, UI_DEV_CREATE)) {
		close(uinput_fd);
		exit(EXIT_FAILURE);
	}
	return uinput_fd;
}

void start()
{
	while(running) {
		if(poll(poll_fds.data(), poll_fds.size(), 1000) <= 0)
            continue;

		for (size_t i = 0; i < poll_fds.size(); i++)
			if (poll_fds[i].revents & POLLIN)
				if (read(poll_fds[i].fd, &ie, sizeof(ie)) == sizeof(struct input_event)){
                    if (ie.type == EV_KEY) ie.code = BTN_TOUCH;
                    write(uinput_fds[i], &ie, sizeof(struct input_event));
				}
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_startTouchpadDirect(JNIEnv *env, jobject thiz) {
	running = true;

	poll_fds.clear();
	uinput_fds.clear();

	std::vector<int> touchpadDeviceFds = scanTouchpadDevices();

	for (auto & evdev : touchpadDeviceFds) {
        poll_fds.push_back(pollfd{evdev, POLLIN, 0});
        uinput_fds.push_back(SetupUinputDevice(evdev));
    }

	if (poll_fds.empty()) {
		printf("I: No touchpad devices found\n");
		return;
	}

	looper = std::thread(start);
}

extern "C"
JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_stopTouchpadDirect(JNIEnv *env, jobject thiz) {
	running = false;
	for (auto & poll_fd : poll_fds) {
		struct input_event ie {0, 0, EV_SYN, SYN_REPORT, 0};
		write(poll_fd.fd, &ie, sizeof(struct input_event));
	}
	for (size_t i = 0; i < poll_fds.size(); i++) {
		ioctl(uinput_fds[i], UI_DEV_DESTROY);
		close(uinput_fds[i]);
		close(poll_fds[i].fd);
	}
    looper.join();
}
