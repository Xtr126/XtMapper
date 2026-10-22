#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <stdarg.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <linux/input.h>

#define MAX_KEYBOARDS 32
#define TEST_BIT(b, a) (((a)[(b) / 8] >> ((b) % 8)) & 1)

static int g_keyboard_fds[MAX_KEYBOARDS];
static int g_num_keyboards = 0;
static int g_grab_active = 0;

static int (*real_open)(const char *, int, ...) = NULL;
static int (*real_open_2)(const char *, int) = NULL;
static int (*real_openat)(int, const char *, int, ...) = NULL;
static int (*real_openat_2)(int, const char *, int) = NULL;

static void set_grab(int enable) {
    g_grab_active = enable;
    for (int i = 0; i < g_num_keyboards; i++) {
        int fd = g_keyboard_fds[i];
        if (fd >= 0) {
            ioctl(fd, EVIOCGRAB, (void *)(long)enable);
        }
    }
}

static void sig_handler(int signo) {
    if (signo == SIGUSR1) {
        set_grab(1);
    } else if (signo == SIGUSR2) {
        set_grab(0);
    }
}

static int is_keyboard_device(int fd) {
    unsigned long evbit = 0;
    if (ioctl(fd, EVIOCGBIT(0, sizeof(evbit)), &evbit) < 0) return 0;
    if (!(evbit & (1 << EV_KEY))) return 0;

    unsigned char keybits[KEY_MAX / 8 + 1] = {0};
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits) < 0) return 0;

    // Must have standard alphanumeric keys
    if (!TEST_BIT(KEY_A, keybits) || !TEST_BIT(KEY_Z, keybits) ||
        !TEST_BIT(KEY_SPACE, keybits) || !TEST_BIT(KEY_ENTER, keybits)) {
        return 0;
    }

    // Exclude virtual/uinput devices
    char devname[128] = {0};
    if (ioctl(fd, EVIOCGNAME(sizeof(devname) - 1), devname) >= 0) {
        if (strstr(devname, "x-virtual") || strstr(devname, "uinput")) {
            return 0;
        }
    }
    return 1;
}

static void check_and_register_device(int fd, const char *pathname) {
    if (fd < 0 || !pathname) return;
    if (strncmp(pathname, "/dev/input/event", 16) != 0 && strstr(pathname, "event") == NULL) return;

    if (is_keyboard_device(fd)) {
        if (g_num_keyboards < MAX_KEYBOARDS) {
            g_keyboard_fds[g_num_keyboards++] = fd;
        }
        if (g_grab_active) {
            ioctl(fd, EVIOCGRAB, (void *)1);
        }
    }
}

int __open_2(const char *pathname, int flags) {
    if (!real_open_2) real_open_2 = (int (*)(const char *, int)) dlsym(RTLD_NEXT, "__open_2");
    int fd = real_open_2 ? real_open_2(pathname, flags) : -1;
    check_and_register_device(fd, pathname);
    return fd;
}

int open(const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t) va_arg(args, int);
        va_end(args);
    }
    if (!real_open) real_open = (int (*)(const char *, int, ...)) dlsym(RTLD_NEXT, "open");
    int fd = real_open ? real_open(pathname, flags, mode) : -1;
    check_and_register_device(fd, pathname);
    return fd;
}

int openat(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t) va_arg(args, int);
        va_end(args);
    }
    if (!real_openat) real_openat = (int (*)(int, const char *, int, ...)) dlsym(RTLD_NEXT, "openat");
    int fd = real_openat ? real_openat(dirfd, pathname, flags, mode) : -1;
    check_and_register_device(fd, pathname);
    return fd;
}

int __openat_2(int dirfd, const char *pathname, int flags) {
    if (!real_openat_2) real_openat_2 = (int (*)(int, const char *, int)) dlsym(RTLD_NEXT, "__openat_2");
    int fd = real_openat_2 ? real_openat_2(dirfd, pathname, flags) : -1;
    check_and_register_device(fd, pathname);
    return fd;
}

static void __attribute__((constructor)) init_getevent(void) {
    setlinebuf(stdout);

    real_open = (int (*)(const char *, int, ...)) dlsym(RTLD_NEXT, "open");
    real_open_2 = (int (*)(const char *, int)) dlsym(RTLD_NEXT, "__open_2");
    real_openat = (int (*)(int, const char *, int, ...)) dlsym(RTLD_NEXT, "openat");
    real_openat_2 = (int (*)(int, const char *, int)) dlsym(RTLD_NEXT, "__openat_2");

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = sig_handler;
    sa.sa_flags = SA_RESTART;
    sigaction(SIGUSR1, &sa, NULL);
    sigaction(SIGUSR2, &sa, NULL);

    const char *block = getenv("BLOCK_KEYBOARD");
    if (block && strcmp(block, "1") == 0) {
        g_grab_active = 1;
    }

    FILE *f = fopen("/data/local/tmp/xtmapper_getevent.pid", "w");
    if (f) {
        fprintf(f, "%d\n", getpid());
        fclose(f);
    }
}

static void __attribute__((destructor)) fini_getevent(void) {
    unlink("/data/local/tmp/xtmapper_getevent.pid");
}