#include <linux/uinput.h>

/* emit function is identical to of the first example */

int main(void)
{
   struct uinput_setup usetup;
   int i = 5;

   int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);

   /* enable touch and relative events */
   ioctl(fd, UI_SET_EVBIT, EV_SYN);

   ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOOL_FINGER);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOOL_QUINTTAP);

   ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_ABSBIT, ABS_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_PRESSURE);
    ioctl(fd, UI_SET_ABSBIT, ABS_TOOL_WIDTH);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);

   memset(&usetup, 0, sizeof(usetup));
   usetup.id.bustype = BUS_I8042;
   usetup.id.vendor = 0x2;
   usetup.id.product = 0x7;
   strcpy(usetup.name, "XtTouchscreen");

   ioctl(fd, UI_DEV_SETUP, &usetup);
   ioctl(fd, UI_DEV_CREATE);

   /*
    * On UI_DEV_CREATE the kernel will create the device node for this
    * device. We are inserting a pause here so that userspace has time
    * to detect, initialize the new device, and can start listening to
    * the event, otherwise it will not notice the event we are about
    * to send. This pause is only needed in our example code!
    */
   sleep(1);

   /* Simulate touch */
   while (i--) {
      emit(fd, EV_ABS, ABS_MT_TRACKING_ID, 19);
      emit(fd, EV_ABS, ABS_MT_POSITION_X, 2711);
      emit(fd, EV_ABS, ABS_MT_POSITION_Y, 3240);
      emit(fd, EV_ABS, ABS_MT_PRESSURE, 49);
      emit(fd, EV_KEY, BTN_TOUCH, 1);
      emit(fd, EV_ABS, ABS_X, 2711);
      emit(fd, EV_ABS, ABS_Y, 3240);
      emit(fd, EV_ABS, ABS_PRESSURE, 49);
      emit(fd, EV_KEY, BTN_TOOL_FINGER, 1);
      usleep(15000);
   }

   /*
    * Give userspace some time to read the events before we destroy the
    * device with UI_DEV_DESTOY.
    */
   sleep(1);

   ioctl(fd, UI_DEV_DESTROY);
   close(fd);

   return 0;
}
