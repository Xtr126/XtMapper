#include <stdio.h>

static void __attribute ((constructor))
getevent(void) {
    setlinebuf(stdout);
}