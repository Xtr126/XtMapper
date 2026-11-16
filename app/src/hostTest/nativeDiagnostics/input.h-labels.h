#include <linux/input.h>

static const char *get_input_event_label(int type, int code) {
    return type == EV_KEY && code == KEY_A ? "KEY_A" : nullptr;
}
