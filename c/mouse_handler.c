#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#include <linux/input.h>
#include <fcntl.h>

#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
enum {
	VERBOSE    = true,
	MAX_WIDTH  = 1280,
	MAX_HEIGHT = 720,
	VALUE_PRESSED  = 1,
	VALUE_RELEASED = 0,
	BITS_PER_BYTE  = 8,
};

struct cursor_t {
	int x, y;
};

struct mouse_t {
	struct cursor_t current;
	struct cursor_t pressed, released;
};
const char *mouse_dev = "/dev/input/event2";

/* logging functions */
enum loglevel_t {
    DEBUG = 0,
    WARN,
    ERROR,
    FATAL,
};

void logging(enum loglevel_t loglevel, char *format, ...)
{
    va_list arg;
    static const char *loglevel2str[] = {
        [DEBUG] = "DEBUG",
        [WARN]  = "WARN",
        [ERROR] = "ERROR",
        [FATAL] = "FATAL",
    };

    /* debug message is available on verbose mode */
    if ((loglevel == DEBUG) && (VERBOSE == false))
        return;

    fprintf(stderr, ">>%s<<\t", loglevel2str[loglevel]);

    va_start(arg, format);
    vfprintf(stderr, format, arg);
    va_end(arg);
}

/* misc functions */
int my_ceil(int val, int div)
{
    if (div == 0)
        return 0;
    else
        return (val + div - 1) / div;
}



/* mouse functions */
void init_mouse(struct mouse_t *mouse)
{
	mouse->current.x  = mouse->current.y  = 0;
	mouse->pressed.x  = mouse->pressed.y  = 0;
	mouse->released.x = mouse->released.y = 0;
	//mouse->button_pressed  = false;
	//mouse->button_released = false;
}



void print_mouse_state(struct mouse_t *mouse)
{
      char buf[BUFSIZ];
      snprintf(buf, sizeof(buf), "xdotool mousemove %d %d",mouse->current.x, mouse->current.y);
        system(buf);


}

void cursor(struct input_event *ie, struct mouse_t *mouse)
{
	if (ie->code == REL_X)
		mouse->current.x += ie->value;

	if (mouse->current.x < 0)
		mouse->current.x = 0;
	else if (mouse->current.x >= MAX_WIDTH)
		mouse->current.x = MAX_WIDTH - 1;

	if (ie->code == REL_Y)
		mouse->current.y += ie->value;

	if (mouse->current.y < 0)
		mouse->current.y = 0;
	else if (mouse->current.y >= MAX_HEIGHT)
		mouse->current.y = MAX_HEIGHT - 1;
}

void button(struct input_event *ie, struct mouse_t *mouse)
{
	if (ie->code != BTN_LEFT)
		return;

	if (ie->value == VALUE_PRESSED)
		mouse->pressed = mouse->current,

	if (ie->value == VALUE_RELEASED)
		mouse->released = mouse->current,
}


void (*event_handler[EV_CNT])(struct input_event *ie, struct mouse_t *mouse) = {
	//[EV_SYN] = sync,
	[EV_REL] = cursor,
	[EV_KEY] = button, 
	[EV_MAX] = NULL, 
};

int main(int argc, char *argv[])
{
	int fd;
	const char *dev;

	struct input_event ie;
	struct mouse_t mouse;

	dev = (argc > 1) ? argv[1]: mouse_dev;

	if ((fd = open(dev, O_RDONLY)) == -1) {
		perror("opening device");
		exit(EXIT_FAILURE);
	}

	init_mouse(&mouse);

	while (read(fd, &ie, sizeof(struct input_event))) {
		print_mouse_state(&mouse);

		if (event_handler[ie.type])
			event_handler[ie.type](&ie, &mouse);
	}

	close(fd);

	return EXIT_SUCCESS;
}
