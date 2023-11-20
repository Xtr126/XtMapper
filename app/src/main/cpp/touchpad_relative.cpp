#include <cstdlib>
#include <linux/input-event-codes.h>
#include <linux/input.h>
#include <linux/uinput.h>

#include <sys/poll.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>

#include <cstring>
#include <cstdio>
#include <iostream>
#include <vector>
#include <thread>
#include "evdev_common.h"
#include <jni.h>

using std::string;

std::vector<pollfd> poll_fds;
std::vector<int> uinput_fds;

std::atomic<bool> running = false;

std::thread looper;

int SetupUinputDevice() {
	struct uinput_setup uinputSetup {};
	int uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);

	if (uinput_fd <= 0) exit(EXIT_FAILURE);

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
		exit(EXIT_FAILURE);
	}
	return uinput_fd;
}

void start() {
	signed int last_absX, last_absY;
	struct input_event event {}, ie {};
	while(true) {
		poll(poll_fds.data(), poll_fds.size(), -1);

		for (size_t i = 0; i < poll_fds.size(); i++)
			if (poll_fds[i].revents & POLLIN)
				if (read(poll_fds[i].fd, &event, sizeof(event)) == sizeof(struct input_event)){
                    switch (event.code) {
						case ABS_X:
							if (event.value <= 1) break;
							ie.type = EV_REL;
							ie.code = REL_X;
							ie.value = event.value - last_absX;
							write(uinput_fds[i], &ie, sizeof(ie));
							last_absX = event.value;
						break;

						case ABS_Y:
							if (event.value <= 1) break;
							ie.type = EV_REL;
							ie.code = REL_Y;
							ie.value = event.value - last_absY;
							write(uinput_fds[i], &ie, sizeof(ie));
							last_absY = event.value;
						break;

						case BTN_MOUSE:
						case BTN_RIGHT:
						case BTN_MIDDLE:
							write(uinput_fds[i], &event, sizeof(event));
						break;
					}
					ie = input_event{0, 0, EV_SYN, SYN_REPORT, 0};
					write(uinput_fds[i], &ie, sizeof(ie));
				}
	}
}

extern "C"
JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_startTouchpadRelative(JNIEnv *env, jobject thiz) {
	running = true;

	poll_fds.clear();
	uinput_fds.clear();

	std::vector<int> touchpadDeviceFds = scanTouchpadDevices();

	for (auto & evdev : touchpadDeviceFds) {
		poll_fds.push_back(pollfd{evdev, POLLIN, 0});
		uinput_fds.push_back(SetupUinputDevice());
	}

	if (poll_fds.empty()) {
		printf("I: No touchpad devices found\n");
		return;
	}
	looper = std::thread(start);
}

extern "C"
JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_stopTouchpadRelative(JNIEnv *env, jobject thiz) {
	running = false;

	for (auto & poll_fd : poll_fds) {
		struct input_event ie{0, 0, EV_SYN, SYN_REPORT, 0};
		write(poll_fd.fd, &ie, sizeof(struct input_event));
	}
	for (size_t i = 0; i < poll_fds.size(); i++) {
		ioctl(uinput_fds[i], UI_DEV_DESTROY);
		close(uinput_fds[i]);
		close(poll_fds[i].fd);
	}
	looper.join();
}