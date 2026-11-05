#include <stdbool.h>
#include <stdlib.h>
#include <unistd.h>

#include <linux/input.h>
#include <fcntl.h>
#include <string.h>
#include <jni.h>
#include <pthread.h>
#include <assert.h>
#include <arpa/inet.h>
#include <paths.h>
#include <errno.h>
#include <stdio.h>
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
    int done;
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

    g_ctx.done = 0;
    g_ctx.inputServiceObj = NULL;
    return  JNI_VERSION_1_6;
}

/*
 * Main working thread function. From a pthread,
 *     calling back to InputService::updateTimer() to send mouse events
 */
void* send_mouse_events(void* context) {
    serviceContext *pctx = (serviceContext *) context;
    JavaVM *javaVM = pctx->javaVM;
    JNIEnv *env;
    jint res = (*javaVM)->GetEnv(javaVM, (void **)&env, JNI_VERSION_1_6);
    if (res != JNI_OK) {
        LOGD("mouse thread: attaching native thread to JVM (GetEnv result=%d)", res);
        res = (*javaVM)->AttachCurrentThread(javaVM, &env, NULL);
        if (JNI_OK != res) {
            LOGE("mouse thread: AttachCurrentThread failed (result=%d)", res);
            return NULL;
        }
    }
    // get inputService sendMouseEvent function
    jmethodID mouseEvent = (*env)->GetMethodID(env, pctx->inputServiceClz, "sendMouseEvent", "(II)V");
    if (mouseEvent == NULL) {
        LOGE("mouse thread: could not resolve InputService.sendMouseEvent(II)V");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
        return NULL;
    }

    int mouse_fd = pctx->mouse_fd;
    int mouse_lock = 1;
    LOGI("mouse thread started: fd=%d, initial grab requested", mouse_fd);
    if (ioctl(mouse_fd, EVIOCGRAB, mouse_lock) == -1) {
        LOGE("EVIOCGRAB(fd=%d, 1) failed: errno=%d (%s)", mouse_fd, errno, strerror(errno));
    } else {
        LOGI("EVIOCGRAB(fd=%d, 1) succeeded", mouse_fd);
    }

    struct input_event ie;
    for (;;) {
        ssize_t bytes_read = read(mouse_fd, &ie, sizeof(struct input_event));
        if (bytes_read == -1) {
            if (errno == EINTR) continue;
            LOGE("read(fd=%d) failed: errno=%d (%s)", mouse_fd, errno, strerror(errno));
            break;
        }
        if (bytes_read == 0) {
            LOGI("read(fd=%d) reached EOF", mouse_fd);
            break;
        }
        if (bytes_read != sizeof(struct input_event)) {
            LOGE("read(fd=%d) returned partial event: %zd of %zu bytes", mouse_fd,
                 bytes_read, sizeof(struct input_event));
            continue;
        }

        LOGD("event: type=%s(%u) code=%u value=%d", event_type_name(ie.type), ie.type,
             ie.code, ie.value);

        pthread_mutex_lock(&pctx->lock);
        int done = pctx->done;
        if (pctx->done) {
            pctx->done = 0;
        }

        if (pctx->mouse_lock != mouse_lock) {
            mouse_lock = pctx->mouse_lock;
            if (ioctl(mouse_fd, EVIOCGRAB, mouse_lock) == -1) {
                LOGE("EVIOCGRAB(fd=%d, %d) failed: errno=%d (%s)", mouse_fd,
                     mouse_lock, errno, strerror(errno));
            } else {
                LOGI("EVIOCGRAB(fd=%d, %d) succeeded", mouse_fd, mouse_lock);
            }
        }
        pthread_mutex_unlock(&pctx->lock);
        if (done) {
            break;
        }

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
    LOGI("mouse thread closing fd=%d", mouse_fd);
    close(mouse_fd);
    pthread_mutex_lock(&pctx->lock);
    pctx->done = 0;
    pthread_mutex_unlock(&pctx->lock);
    return context;
}


void startMouseThread(JNIEnv *env, jobject thiz) {

    pthread_t threadInfo_;
    pthread_attr_t threadAttr_;

    pthread_attr_init(&threadAttr_);
    pthread_attr_setdetachstate(&threadAttr_, PTHREAD_CREATE_DETACHED);

    jclass clz = (*env)->GetObjectClass(env, thiz);
    g_ctx.inputServiceClz = (*env)->NewGlobalRef(env, clz);
    g_ctx.inputServiceObj = (*env)->NewGlobalRef(env, thiz);
    LOGI("starting mouse thread; service class/object global refs created");

    int result = pthread_create(&threadInfo_, &threadAttr_, send_mouse_events, &g_ctx);
    if (result != 0) {
        LOGE("pthread_create for mouse thread failed: result=%d (%s)", result, strerror(result));
    }
    assert(result == 0);
    pthread_attr_destroy(&threadAttr_);
    (void) result;
}

JNIEXPORT jint JNICALL
Java_xtr_keymapper_server_InputService_openDevice(JNIEnv *env, jobject thiz, jstring device) {
    const char *evdev = (*env)->GetStringUTFChars(env, device, NULL);
    if (evdev == NULL) {
        LOGE("openDevice: GetStringUTFChars failed");
        return -1;
    }
    LOGI("openDevice: opening %s", evdev);
    if ((g_ctx.mouse_fd = open(evdev, O_RDONLY)) == -1) {
        LOGE("open(%s, O_RDONLY) failed: errno=%d (%s)", evdev, errno, strerror(errno));
    } else {
        LOGI("open(%s) succeeded: fd=%d", evdev, g_ctx.mouse_fd);
        startMouseThread(env, thiz);
    }
    (*env)->ReleaseStringUTFChars(env, device, evdev);
    return g_ctx.mouse_fd;
}

/*
 * Interface to Java side to stop:
 */
JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_stopMouse(JNIEnv *env, jobject thiz) {
    LOGI("stopMouse requested; waiting for mouse read thread to exit");
    pthread_mutex_lock(&g_ctx.lock);
    g_ctx.done = 1;
    pthread_mutex_unlock(&g_ctx.lock);

    // waiting for mouse read thread to flip the done flag
    struct timespec sleepTime;
    memset(&sleepTime, 0, sizeof(sleepTime));
    sleepTime.tv_nsec = 100000000;
    while (g_ctx.done) {
        nanosleep(&sleepTime, NULL);
    }
    LOGI("mouse read thread stop handshake completed");

    // release object we allocated
    (*env)->DeleteGlobalRef(env, g_ctx.inputServiceClz);
    (*env)->DeleteGlobalRef(env, g_ctx.inputServiceObj);
    g_ctx.inputServiceObj = NULL;
    g_ctx.inputServiceClz = NULL;
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
