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

typedef struct input_service_context {
    JavaVM *javaVM;
    jclass inputServiceClz;
    jobject inputServiceObj;
    pthread_mutex_t lock;
    int done;
} serviceContext;
serviceContext g_ctx;

int mouse_fd;

/*
 * processing one time initialization:
 *     Cache the javaVM into our context
 * Note:
 *     All resources allocated here are never released by application
 *     we rely on system to free all global refs when it goes away;
 *     the pairing function JNI_OnUnload() never gets called at all.
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    setlinebuf(stdout);
    JNIEnv* env;
    memset(&g_ctx, 0, sizeof(g_ctx));

    g_ctx.javaVM = vm;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
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
        res = (*javaVM)->AttachCurrentThread(javaVM, &env, NULL);
        if (JNI_OK != res) {
            return NULL;
        }
    }
    // get inputService sendMouseEvent function
    jmethodID mouseEvent = (*env)->GetMethodID(env, pctx->inputServiceClz, "sendMouseEvent", "(II)V");

    // mouse grab
    ioctl(mouse_fd, EVIOCGRAB, (void *)1);

    struct input_event ie;
    while (read(mouse_fd, &ie, sizeof(struct input_event))) {
        pthread_mutex_lock(&pctx->lock);
        int done = pctx->done;
        if (pctx->done) {
            pctx->done = 0;
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
                (*env)->CallVoidMethod(env, pctx->inputServiceObj, mouseEvent, ie.code, ie.value);
                break;
        }
    }
    close(mouse_fd);
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

    int result = pthread_create(&threadInfo_, &threadAttr_, send_mouse_events, &g_ctx);
    assert(result == 0);
    pthread_attr_destroy(&threadAttr_);
    (void) result;
}

JNIEXPORT jint JNICALL
Java_xtr_keymapper_server_InputService_openDevice(JNIEnv *env, jobject thiz, jstring device) {
    const char *evdev = (*env)->GetStringUTFChars(env, device, NULL);

    if ((mouse_fd = open(evdev, O_RDONLY)) == -1) {
        perror("opening device");
    } else {
        startMouseThread(env, thiz);
    }
    (*env)->ReleaseStringUTFChars(env, device, evdev);
    return mouse_fd;
}

/*
 * Interface to Java side to stop:
 */
JNIEXPORT void JNICALL
Java_xtr_keymapper_server_InputService_stopMouse(JNIEnv *env, jobject thiz) {
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

    // release object we allocated
    (*env)->DeleteGlobalRef(env, g_ctx.inputServiceClz);
    (*env)->DeleteGlobalRef(env, g_ctx.inputServiceObj);
    g_ctx.inputServiceObj = NULL;
    g_ctx.inputServiceClz = NULL;
}