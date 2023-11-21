#include "mouse_cursor.h"
#include <thread>
#include <jni.h>
#include <vector>
#include <iostream>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/poll.h>
#include <linux/uinput.h>
#include <linux/input.h>
#include <linux/input-event-codes.h>
#include <cstdlib>
#include "evdev_common.h"

using std::string;

std::vector<string> ListInputDevices() {
    const string input_directory = "/dev/input";
    std::vector<string> filenames;
    struct DIR * directory = opendir(input_directory.c_str());

    struct dirent *entry;
    while ((entry = readdir(directory))) {
        if (entry->d_name[0] == 'e') // is eventX
          filenames.push_back(input_directory + "/" + entry->d_name);
    }
  return filenames;
}

std::vector<int> scanTouchpadDevices() {
    std::vector<string> evdevNames = ListInputDevices();
    std::vector<int> touchpadDeviceFds;

    printf("I: Searching for touchpad devices...\n");
    for (auto & evdev : evdevNames) {
        int device_fd = open(evdev.c_str(), O_RDWR);
        if (device_fd < 0) {
            perror("opening device");
        }

        // Get device name
        char dev_name[24];
        if(!ioctl(device_fd, EVIOCGNAME(sizeof(dev_name) - 1), &dev_name)) {
            perror("get device name");
            close(device_fd);
            continue;
        }

        if(!HasSpecificAbs(device_fd, ABS_X) || !HasSpecificAbs(device_fd, ABS_Y)) {
            printf("%s: no ABS_X or ABS_Y events found\n", dev_name);
            printf("Not a touch device\n");
            close(device_fd);
            continue;
        }

        if (!HasInputProp(device_fd, INPUT_PROP_POINTER)) {
            printf("%s: INPUT_PROP_POINTER not set\n", dev_name);
            if (!HasSpecificKey(device_fd, BTN_MOUSE)) {
                printf("BTN_MOUSE not found\n");
                printf("Not a touchpad device\n");
                close(device_fd);
                continue;
            }
        }


        // Check for virtual devices
        if (strcmp(x_virtual_tablet, dev_name) == 0 || strcmp(x_virtual_mouse, dev_name) == 0) {
            printf("skipping %s\n", dev_name);
            close(device_fd);
            continue;
        }
        else if (x_virtual_touch == dev_name) {
            touchpadDeviceFds.clear();
            return touchpadDeviceFds;
        }

        printf("I: Add touchpad device: %s %s\n", dev_name, evdev.c_str());
        ioctl(device_fd, EVIOCGRAB, (void *)1);

        touchpadDeviceFds.push_back(device_fd);
    }
    return touchpadDeviceFds;
}

bool HasSpecificAbs(int device_fd, unsigned int abs) {
  unsigned long nchar = KEY_MAX / 8 + 1;
  unsigned char bits[nchar];
  // Get the bit fields of available abs events.
  ioctl(device_fd, EVIOCGBIT(EV_ABS, sizeof(bits)), &bits);
  return bits[abs/8] & (1 << (abs % 8));
}

bool HasSpecificKey(int device_fd, unsigned int key) {
  unsigned long nchar = KEY_MAX / 8 + 1;
  unsigned char bits[nchar];
  // Get the bit fields of available keys.
  ioctl(device_fd, EVIOCGBIT(EV_KEY, sizeof(bits)), &bits);
  return bits[key/8] & (1 << (key % 8));
}

bool HasInputProp(int device_fd, unsigned int input_prop) {
  unsigned long nchar = INPUT_PROP_MAX / 8 + 1;
  unsigned char bits[nchar];
  // Get the bit fields of available keys.
  ioctl(device_fd, EVIOCGPROP(sizeof(bits)), &bits);
  return bits[input_prop/8] & (1 << (input_prop % 8));
}

bool HasEventType(int device_fd, unsigned int type) {
  unsigned long evbit = 0;
  // Get the bit field of available event types.
  ioctl(device_fd, EVIOCGBIT(0, sizeof(evbit)), &evbit);
  return evbit & (1 << type);
}
