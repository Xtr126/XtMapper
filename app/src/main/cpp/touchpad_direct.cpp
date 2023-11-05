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

using std::string;

struct input_event ie {};
const char *deviceName = "x-virtual-touch";

std::vector<string> ListInputDevices() {
    const string input_directory = "/dev/input";
    std::vector<string> filenames;
    DIR* directory = opendir(input_directory.c_str());

    struct dirent *entry = NULL;
    while ((entry = readdir(directory))) {
        if (strcmp(entry->d_name, ".") && strcmp(entry->d_name, "..")) {
          filenames.push_back(input_directory + "/" + entry->d_name);
        }
    }
  return filenames;
}

bool HasSpecificAbs(int device_fd, unsigned int abs) {
  size_t nchar = KEY_MAX/8 + 1;
  unsigned char bits[nchar];
  // Get the bit fields of available abs events.
  ioctl(device_fd, EVIOCGBIT(EV_ABS, sizeof(bits)), &bits);
  return bits[abs/8] & (1 << (abs % 8));
}

void SetAbsInfoFrom(int device_fd, int uinput_fd) {
	for(int abs_i = ABS_X; abs_i <= ABS_MAX; abs_i++) {
		if(HasSpecificAbs(device_fd, abs_i)) {
			struct input_absinfo absinfo;
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

bool HasSpecificKey(int device_fd, unsigned int key) {
  size_t nchar = KEY_MAX/8 + 1;
  unsigned char bits[nchar];
  // Get the bit fields of available keys.
  ioctl(device_fd, EVIOCGBIT(EV_KEY, sizeof(bits)), &bits);
  return bits[key/8] & (1 << (key % 8));
}

void SetKeyBits(int device_fd, int uinput_fd) {
	for(int key_i = BTN_MOUSE; key_i <= KEY_MAX; key_i++) {
		if (HasSpecificKey(device_fd, key_i)) {
			ioctl(uinput_fd, UI_SET_KEYBIT, key_i);
		}
	}
}

bool HasEventType(int device_fd, unsigned int type) {
  unsigned long evbit = 0;
  // Get the bit field of available event types.
  ioctl(device_fd, EVIOCGBIT(0, sizeof(evbit)), &evbit);
  return evbit & (1 << type);
}


void SetEventTypeBits(int device_fd, int uinput_fd) {
	for(int ev_i = EV_SYN; ev_i <= EV_MAX; ev_i++) {
		if (HasEventType(device_fd, ev_i)) {
			ioctl(uinput_fd, UI_SET_EVBIT, ev_i);
		}
	}
}

int SetupUinputDevice(int device_fd) {
	struct uinput_setup uinputSetup;
	int uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);

	if (uinput_fd <= 0) exit(EXIT_FAILURE);

	SetEventTypeBits(device_fd, uinput_fd);
	SetKeyBits(device_fd, uinput_fd);
	SetAbsInfoFrom(device_fd, uinput_fd);
	ioctl(uinput_fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

	memset(&uinputSetup, 0, sizeof(uinputSetup));

	strncpy(uinputSetup.name, deviceName, strlen(deviceName));
	uinputSetup.id.version = 1;
	uinputSetup.id.bustype = BUS_VIRTUAL;
	ioctl(uinput_fd, UI_DEV_SETUP, &uinputSetup);

	if(ioctl(uinput_fd, UI_DEV_CREATE)) {
		close(uinput_fd);
		exit(EXIT_FAILURE);
	}
	return uinput_fd;
}

int start()
{
	std::vector<string> evdevNames = ListInputDevices();

	std::vector<pollfd> poll_fds;
	std::vector<int> uinput_fds;

	for (size_t i = 0; i < evdevNames.size(); i++) {
		int device_fd = open(evdevNames[i].c_str(), O_RDONLY);
		if (device_fd < 0) {
			return 1;
		}

		if(!HasSpecificAbs(device_fd, ABS_X) || !HasSpecificAbs(device_fd, ABS_Y)) {
			continue;
		}

		ioctl(device_fd, EVIOCGRAB, (void *)1);
		printf("add device: %s\n", evdevNames[i].c_str());

		poll_fds.push_back(pollfd{device_fd, POLLIN, 0});
		uinput_fds.push_back(SetupUinputDevice(device_fd));
	}

	if (poll_fds.empty()) return 1;

	while(true) {
		poll(poll_fds.data(), poll_fds.size(), -1);

		for (size_t i = 0; i < poll_fds.size(); i++)
			if (poll_fds[i].revents & POLLIN)
				if (read(poll_fds[i].fd, &ie, sizeof(ie)) == sizeof(struct input_event)){
                    if (ie.type == EV_KEY)
                        ie.code = BTN_TOUCH;
                    write(uinput_fds[i], &ie, sizeof(struct input_event));
				}
	}
}

extern "C"
JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_startTouchpadDirect(JNIEnv *env, jobject thiz) {
	std::thread looper(start);
	looper.detach();
}