#include <stdbool.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <time.h>

#include <linux/input.h>
#include <fcntl.h>
#include <string.h>
#include <jni.h>
#include <pthread.h>
#include <assert.h>
#include <arpa/inet.h>
#include <paths.h>
#include <sys/ioctl.h>
#include <android/log.h>

#define LOG_TAG "XtMapperMouse"
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char *event_type_name(unsigned short type) {
    switch (type) {
        case EV_SYN: return "EV_SYN";
        case EV_KEY: return "EV_KEY";
        case EV_REL: return "EV_REL";
        case EV_ABS: return "EV_ABS";
        case EV_MSC: return "EV_MSC";
        case EV_SW: return "EV_SW";
        case EV_LED: return "EV_LED";
        case EV_SND: return "EV_SND";
        case EV_REP: return "EV_REP";
        case EV_FF: return "EV_FF";
        default: return "EV_UNKNOWN";
    }
}

typedef struct input_service_context {
    JavaVM *javaVM;
    jclass inputServiceClz;
    jobject inputServiceObj;
    pthread_mutex_t lock;
    int done; /* stop request; cleared only after the reader has fully exited */
    int reader_started; /* true while the detached reader owns the mouse fd */
    int mouse_lock;
    int mouse_fd;
} serviceContext;
serviceContext g_ctx;

/*
 * processing one time initialization:
 *     Cache the javaVM into our context
 * Note:
 *     All resources allocated here are never released by application
 *     we rely on system to free all global refs when it goes away;
 *     the pairing function JNI_OnUnload() never gets called at all.
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void) reserved;
    JNIEnv* env;
    memset(&g_ctx, 0, sizeof(g_ctx));

    g_ctx.javaVM = vm;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: GetEnv failed for JNI 1.6");
        return JNI_ERR; // JNI version not supported.
    }

    int result = pthread_mutex_init(&g_ctx.lock, NULL);
    if (result != 0) {
        LOGE("JNI_OnLoad: mutex initialization failed: result=%d (%s)", result, strerror(result));
        return JNI_ERR;
    }
    g_ctx.done = 0;
    g_ctx.reader_started = 0;
    g_ctx.mouse_fd = -1;
    g_ctx.inputServiceObj = NULL;
    g_ctx.inputServiceClz = NULL;
    return  JNI_VERSION_1_6;
}

static void finish_mouse_reader(serviceContext *pctx, int mouse_fd, bool attached_here) {
    if (mouse_fd >= 0) {
        LOGI("mouse thread closing fd=%d", mouse_fd);
        close(mouse_fd);
    }
    if (attached_here) {
        (*pctx->javaVM)->DetachCurrentThread(pctx->javaVM);
    }

    pthread_mutex_lock(&pctx->lock);
    if (pctx->mouse_fd == mouse_fd) {
        pctx->mouse_fd = -1;
    }
    pctx->done = 0;
    pctx->reader_started = 0;
    pthread_mutex_unlock(&pctx->lock);
}

/* Bounded polling lets stopMouse() terminate an idle or disconnected reader. */
void* send_mouse_events(void* context) {
    serviceContext *pctx = (serviceContext *) context;
    JavaVM *javaVM = pctx->javaVM;
    JNIEnv *env = NULL;
    bool attached_here = false;

    pthread_mutex_lock(&pctx->lock);
    int mouse_fd = pctx->mouse_fd;
    pthread_mutex_unlock(&pctx->lock);
    if (mouse_fd < 0) {
        finish_mouse_reader(pctx, mouse_fd, false);
        return context;
    }

    jint res = (*javaVM)->GetEnv(javaVM, (void **)&env, JNI_VERSION_1_6);
    if (res != JNI_OK) {
        LOGD("mouse thread: attaching native thread to JVM (GetEnv result=%d)", res);
        res = (*javaVM)->AttachCurrentThread(javaVM, &env, NULL);
        if (JNI_OK != res) {
            LOGE("mouse thread: AttachCurrentThread failed (result=%d)", res);
            finish_mouse_reader(pctx, mouse_fd, false);
            return context;
        }
        attached_here = true;
    }

    jmethodID mouseEvent = (*env)->GetMethodID(
        env, pctx->inputServiceClz, "sendMouseEvent", "(II)V"
    );
    if (mouseEvent == NULL) {
        LOGE("mouse thread: could not resolve InputService.sendMouseEvent(II)V");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        finish_mouse_reader(pctx, mouse_fd, attached_here);
        return context;
    }

    int mouse_lock = 1;
    LOGI("mouse thread started: fd=%d, initial grab requested", mouse_fd);
    if (ioctl(mouse_fd, EVIOCGRAB, mouse_lock) == -1) {
        LOGE("EVIOCGRAB(fd=%d, 1) failed: errno=%d (%s)", mouse_fd, errno, strerror(errno));
    } else {
        LOGI("EVIOCGRAB(fd=%d, 1) succeeded", mouse_fd);
    }

    struct input_event ie;
    for (;;) {
        pthread_mutex_lock(&pctx->lock);
        int stop_requested = pctx->done;
        int requested_lock = pctx->mouse_lock;
        pthread_mutex_unlock(&pctx->lock);
        if (stop_requested) {
            break;
        }

        if (requested_lock != mouse_lock) {
            mouse_lock = requested_lock;
            if (ioctl(mouse_fd, EVIOCGRAB, mouse_lock) == -1) {
                LOGE("EVIOCGRAB(fd=%d, %d) failed: errno=%d (%s)", mouse_fd,
                     mouse_lock, errno, strerror(errno));
            } else {
                LOGI("EVIOCGRAB(fd=%d, %d) succeeded", mouse_fd, mouse_lock);
            }
        }

        struct pollfd poll_fd;
        poll_fd.fd = mouse_fd;
        poll_fd.events = POLLIN | POLLPRI;
        poll_fd.revents = 0;
        int poll_result = poll(&poll_fd, 1, 100);
        if (poll_result == 0) {
            continue;
        }
        if (poll_result < 0) {
            if (errno == EINTR) {
                continue;
            }
            LOGE("poll(fd=%d) failed: errno=%d (%s)", mouse_fd, errno, strerror(errno));
            break;
        }
        if (poll_fd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            if (!(poll_fd.revents & (POLLIN | POLLPRI))) {
                LOGI("poll(fd=%d) ended reader: revents=%d", mouse_fd, poll_fd.revents);
                break;
            }
        }
        if (!(poll_fd.revents & (POLLIN | POLLPRI))) {
            continue;
        }

        ssize_t bytes = read(mouse_fd, &ie, sizeof(struct input_event));
        if (bytes < 0) {
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) {
                continue;
            }
            LOGE("read(fd=%d) failed: errno=%d (%s)", mouse_fd, errno, strerror(errno));
            break;
        }
        if (bytes != sizeof(struct input_event)) {
            if (bytes == 0) {
                LOGI("read(fd=%d) reached EOF", mouse_fd);
            } else {
                LOGE("read(fd=%d) returned partial event: %zd of %zu bytes", mouse_fd,
                     bytes, sizeof(struct input_event));
            }
            break;
        }

        LOGD("event: type=%s(%u) code=%u value=%d", event_type_name(ie.type), ie.type,
             ie.code, ie.value);

        switch (ie.code) {
            case REL_X :
            case REL_Y :
            case REL_WHEEL :
            case BTN_MOUSE :
            case BTN_RIGHT :
            case BTN_MIDDLE :
            case BTN_EXTRA :
            case BTN_SIDE :
                if (mouse_lock) {
                    (*env)->CallVoidMethod(env, pctx->inputServiceObj, mouseEvent, ie.code, ie.value);
                    if ((*env)->ExceptionCheck(env)) {
                        LOGE("Java exception while dispatching mouse event code=%u value=%d", ie.code, ie.value);
                        (*env)->ExceptionDescribe(env);
                        (*env)->ExceptionClear(env);
                    }
                } else {
                    LOGD("not dispatching recognized mouse event while unlocked: code=%u value=%d",
                         ie.code, ie.value);
                }
                break;
            default:
                LOGV("ignoring event outside mouse code filter: type=%s(%u) code=%u value=%d",
                     event_type_name(ie.type), ie.type, ie.code, ie.value);
                break;
        }
    }

    finish_mouse_reader(pctx, mouse_fd, attached_here);
    return context;
}

int startMouseThread(JNIEnv *env, jobject thiz) {
    jclass localClass = (*env)->GetObjectClass(env, thiz);
    if (localClass == NULL) {
        LOGE("startMouseThread: GetObjectClass failed");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return 0;
    }
    jclass globalClass = (*env)->NewGlobalRef(env, localClass);
    jobject globalObject = (*env)->NewGlobalRef(env, thiz);
    (*env)->DeleteLocalRef(env, localClass);
    if (globalClass == NULL || globalObject == NULL) {
        LOGE("startMouseThread: creating service global refs failed");
        if (globalClass != NULL) (*env)->DeleteGlobalRef(env, globalClass);
        if (globalObject != NULL) (*env)->DeleteGlobalRef(env, globalObject);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return 0;
    }

    pthread_mutex_lock(&g_ctx.lock);
    g_ctx.inputServiceClz = globalClass;
    g_ctx.inputServiceObj = globalObject;
    g_ctx.done = 0;
    pthread_mutex_unlock(&g_ctx.lock);
    LOGI("starting mouse thread; service class/object global refs created");

    pthread_attr_t threadAttr;
    bool attr_initialized = false;
    int result = pthread_attr_init(&threadAttr);
    if (result == 0) {
        attr_initialized = true;
        result = pthread_attr_setdetachstate(&threadAttr, PTHREAD_CREATE_DETACHED);
    }
    if (result == 0) {
        pthread_t threadInfo;
        result = pthread_create(&threadInfo, &threadAttr, send_mouse_events, &g_ctx);
    }
    if (attr_initialized) {
        pthread_attr_destroy(&threadAttr);
    }
    if (result == 0) {
        return 1;
    }

    LOGE("starting mouse thread failed: result=%d (%s)", result, strerror(result));
    pthread_mutex_lock(&g_ctx.lock);
    jclass failedClass = g_ctx.inputServiceClz;
    jobject failedObject = g_ctx.inputServiceObj;
    g_ctx.inputServiceClz = NULL;
    g_ctx.inputServiceObj = NULL;
    g_ctx.reader_started = 0;
    g_ctx.done = 0;
    pthread_mutex_unlock(&g_ctx.lock);
    if (failedClass != NULL) (*env)->DeleteGlobalRef(env, failedClass);
    if (failedObject != NULL) (*env)->DeleteGlobalRef(env, failedObject);
    return 0;
}

JNIEXPORT jint JNICALL
Java_xtr_keymapper_server_InputService_openDevice(JNIEnv *env, jobject thiz, jstring device) {
    if (device == NULL) {
        LOGE("openDevice: device is null");
        return -1;
    }

    const char *evdev = (*env)->GetStringUTFChars(env, device, NULL);
    if (evdev == NULL) {
        LOGE("openDevice: GetStringUTFChars failed");
        return -1;
    }

    LOGI("openDevice: opening %s", evdev);
    int mouse_fd = open(evdev, O_RDONLY);
    if (mouse_fd == -1) {
        LOGE("open(%s, O_RDONLY) failed: errno=%d (%s)", evdev, errno, strerror(errno));
        (*env)->ReleaseStringUTFChars(env, device, evdev);
        return -1;
    }
    LOGI("open(%s) succeeded: fd=%d", evdev, mouse_fd);

    pthread_mutex_lock(&g_ctx.lock);
    if (g_ctx.reader_started || g_ctx.mouse_fd >= 0) {
        pthread_mutex_unlock(&g_ctx.lock);
        close(mouse_fd);
        LOGE("openDevice: refusing a second mouse reader before the first stops");
        (*env)->ReleaseStringUTFChars(env, device, evdev);
        return -1;
    }
    g_ctx.mouse_fd = mouse_fd;
    g_ctx.reader_started = 1; /* includes the interval while pthread_create is in progress */
    g_ctx.done = 0;
    pthread_mutex_unlock(&g_ctx.lock);

    if (!startMouseThread(env, thiz)) {
        pthread_mutex_lock(&g_ctx.lock);
        if (g_ctx.mouse_fd == mouse_fd) {
            g_ctx.mouse_fd = -1;
        }
        g_ctx.reader_started = 0;
        g_ctx.done = 0;
        pthread_mutex_unlock(&g_ctx.lock);
        close(mouse_fd);
        mouse_fd = -1;
    }

    (*env)->ReleaseStringUTFChars(env, device, evdev);
    return mouse_fd;
}

/*
 * Interface to Java side to stop:
 */
JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_stopMouse(JNIEnv *env, jobject thiz) {
    LOGI("stopMouse requested; waiting for mouse read thread to exit");
    int should_wait = 0;
    int fd_to_close = -1;

    pthread_mutex_lock(&g_ctx.lock);
    should_wait = g_ctx.reader_started;
    if (should_wait) {
        g_ctx.done = 1;
    } else {
        fd_to_close = g_ctx.mouse_fd;
        g_ctx.mouse_fd = -1;
        g_ctx.done = 0;
    }
    pthread_mutex_unlock(&g_ctx.lock);

    if (fd_to_close >= 0) {
        close(fd_to_close);
    }

    if (should_wait) {
        struct timespec sleepTime;
        memset(&sleepTime, 0, sizeof(sleepTime));
        sleepTime.tv_nsec = 100000000;
        for (;;) {
            pthread_mutex_lock(&g_ctx.lock);
            int reader_running = g_ctx.reader_started;
            pthread_mutex_unlock(&g_ctx.lock);
            if (!reader_running) break;
            nanosleep(&sleepTime, NULL);
        }
    }
    LOGI("mouse read thread stop handshake completed");

    /* The reader must exit before its global references are released. */
    pthread_mutex_lock(&g_ctx.lock);
    jclass serviceClass = g_ctx.inputServiceClz;
    jobject serviceObject = g_ctx.inputServiceObj;
    g_ctx.inputServiceClz = NULL;
    g_ctx.inputServiceObj = NULL;
    pthread_mutex_unlock(&g_ctx.lock);

    if (serviceClass != NULL) (*env)->DeleteGlobalRef(env, serviceClass);
    if (serviceObject != NULL) (*env)->DeleteGlobalRef(env, serviceObject);
}

JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_setMouseLock(JNIEnv *env, jobject thiz, jboolean lock) {
    pthread_mutex_lock(&g_ctx.lock);
    if (lock == JNI_TRUE) {
        g_ctx.mouse_lock = 1;
    } else {
        g_ctx.mouse_lock = 0;
    }
    LOGI("setMouseLock requested: %s", g_ctx.mouse_lock ? "locked" : "unlocked");
    pthread_mutex_unlock(&g_ctx.lock);
}
