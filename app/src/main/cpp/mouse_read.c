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
#include <jni.h>
#include <pthread.h>
#include <assert.h>
#include <inttypes.h>

static const char* kTAG = "mouse_read";

typedef struct mouse_context {
	JavaVM  *javaVM;
	jclass   jniHelperClz;
	jobject  jniHelperObj;
	jclass   mainActivityClz;
	jobject  mainActivityObj;
	pthread_mutex_t  lock;
	int      done;
} mouseContext;
mouseContext g_ctx;

JNIEXPORT jstring JNICALL
Java_com_xtr_keymapper_MainActivity_stringFromJNI(JNIEnv *env, jobject thiz) {
#if defined(__arm__)
	#if defined(__ARM_ARCH_7A__)
    #if defined(__ARM_NEON__)
      #if defined(__ARM_PCS_VFP)
        #define ABI "armeabi-v7a/NEON (hard-float)"
      #else
        #define ABI "armeabi-v7a/NEON"
      #endif
    #else
      #if defined(__ARM_PCS_VFP)
        #define ABI "armeabi-v7a (hard-float)"
      #else
        #define ABI "armeabi-v7a"
      #endif
    #endif
  #else
   #define ABI "armeabi"
  #endif
#elif defined(__i386__)
#define ABI "x86"
#elif defined(__x86_64__)
	#define ABI "x86_64"
#elif defined(__mips64)  /* mips64el-* toolchain defines __mips__ too */
#define ABI "mips64"
#elif defined(__mips__)
#define ABI "mips"
#elif defined(__aarch64__)
#define ABI "arm64-v8a"
#else
#define ABI "unknown"
#endif

	return (*env)->NewStringUTF(env, "Compiled with ABI " ABI ".");
}
void queryRuntimeInfo(JNIEnv *env, jobject instance) {
    // Find out which OS we are running on. It does not matter for this app
    // just to demo how to call static functions.
    // Our java JniHelper class id and instance are initialized when this
    // shared lib got loaded, we just directly use them
    //    static function does not need instance, so we just need to feed
    //    class and method id to JNI
    jmethodID versionFunc = (*env)->GetStaticMethodID(
            env, g_ctx.jniHelperClz,
            "getBuildVersion", "()Ljava/lang/String;");

    if (!versionFunc) {

        return;
    }
    jstring buildVersion = (*env)->CallStaticObjectMethod(env,
                                                          g_ctx.jniHelperClz, versionFunc);
    const char *version = (*env)->GetStringUTFChars(env, buildVersion, NULL);
    if (!version) {

        return;
    }

    (*env)->ReleaseStringUTFChars(env, buildVersion, version);

    // we are called from JNI_OnLoad, so got to release LocalRef to avoid leaking
    (*env)->DeleteLocalRef(env, buildVersion);

    // Query available memory size from a non-static public function
    // we need use an instance of JniHelper class to call JNI
    jmethodID memFunc = (*env)->GetMethodID(env, g_ctx.jniHelperClz,
                                            "getRuntimeMemorySize", "()J");
    if (!memFunc) {

        return;
    }
    jlong result = (*env)->CallLongMethod(env, instance, memFunc);

    (void)result;  // silence the compiler warning
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    memset(&g_ctx, 0, sizeof(g_ctx));

    g_ctx.javaVM = vm;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR; // JNI version not supported.
    }

    jclass  clz = (*env)->FindClass(env,
                                    "com/xtr/keymapper/JniHandler");
    g_ctx.jniHelperClz = (*env)->NewGlobalRef(env, clz);

    jmethodID  jniHelperCtor = (*env)->GetMethodID(env, g_ctx.jniHelperClz,
                                                   "<init>", "()V");
    jobject    handler = (*env)->NewObject(env, g_ctx.jniHelperClz,
                                           jniHelperCtor);
    g_ctx.jniHelperObj = (*env)->NewGlobalRef(env, handler);
    queryRuntimeInfo(env, g_ctx.jniHelperObj);

    g_ctx.done = 0;
    g_ctx.mainActivityObj = NULL;
    return  JNI_VERSION_1_6;
}
void   sendJavaMsg(JNIEnv *env, jobject instance,
                   jmethodID func,const char* msg) {
    jstring javaMsg = (*env)->NewStringUTF(env, msg);
    (*env)->CallVoidMethod(env, instance, func, javaMsg);
    (*env)->DeleteLocalRef(env, javaMsg);
}

void*  UpdateMouse(void* context) {
    mouseContext *pctx = (mouseContext*) context;
    JavaVM *javaVM = pctx->javaVM;
    JNIEnv *env;
    jint res = (*javaVM)->GetEnv(javaVM, (void**)&env, JNI_VERSION_1_6);
    if (res != JNI_OK) {
        res = (*javaVM)->AttachCurrentThread(javaVM, &env, NULL);
        if (JNI_OK != res) {
            return NULL;
        }
    }
    jmethodID statusId = (*env)->GetMethodID(env, pctx->jniHelperClz,
                                             "updateStatus",
                                             "(Ljava/lang/String;)V");
    sendJavaMsg(env, pctx->jniHelperObj, statusId,
                "MouseThread status: initializing...");

    // get mainActivity updateTimer function
    jmethodID timerId = (*env)->GetMethodID(env, pctx->mainActivityClz,
                                            "updateMouse", "()V");


    sendJavaMsg(env, pctx->jniHelperObj, statusId,
                "MouseThread status: start mouseing ...");
    while(1) {
        pthread_mutex_lock(&pctx->lock);
        int done = pctx->done;
        if (pctx->done) {
            pctx->done = 0;
        }
        pthread_mutex_unlock(&pctx->lock);
        if (done) {
            break;
        }
        jmethodID updateX = (*env)->GetMethodID(env, pctx->mainActivityClz,
                                                 "updateMouseX", "()V");
        jmethodID updateY = (*env)->GetMethodID(env, pctx->mainActivityClz,
                                                 "updateMouseY", "()V");
        jmethodID updateXn = (*env)->GetMethodID(env, pctx->mainActivityClz,
                                                 "updateMouseXn", "()V");
        jmethodID updateYn = (*env)->GetMethodID(env, pctx->mainActivityClz,
                                                 "updateMouseYn", "()V");
        int fd;
        const char *dev = "/dev/input/event2";
        struct input_event ie;

        if ((fd = open(dev, O_RDONLY)) == -1) {
            perror("opening device");
            exit(EXIT_FAILURE);
        }
        while (read(fd, &ie, sizeof(struct input_event))) {
            if (ie.code == REL_X) {
                if (ie.value > 0) {
                    while (ie.value > 0) {
                        (*env)->CallVoidMethod(env, pctx->mainActivityObj, updateX);
                        ie.value--;
                    }
                } else {
                    while (ie.value < 0) {
                        (*env)->CallVoidMethod(env, pctx->mainActivityObj, updateXn);
                        ie.value++;
                    }
                }
                break;
            }
            if (ie.code == REL_Y) {
                if (ie.value > 0) {
                    while (ie.value > 0) {
                        (*env)->CallVoidMethod(env, pctx->mainActivityObj, updateY);
                        ie.value--;
                    }
                } else {
                    while (ie.value < 0) {
                        (*env)->CallVoidMethod(env, pctx->mainActivityObj, updateYn);
                        ie.value++;
                    }
                }
                break;
            }
        }
        close(fd);
        (*env)->CallVoidMethod(env, pctx->mainActivityObj, timerId);


    }

    sendJavaMsg(env, pctx->jniHelperObj, statusId,
                "MouseThread status: read");
    (*javaVM)->DetachCurrentThread(javaVM);
    return context;
    }


    JNIEXPORT void JNICALL
    Java_com_xtr_keymapper_MainActivity_startMouse(JNIEnv *env, jobject instance) {
        pthread_t threadInfo_;
        pthread_attr_t threadAttr_;

        pthread_attr_init(&threadAttr_);
        pthread_attr_setdetachstate(&threadAttr_, PTHREAD_CREATE_DETACHED);

        pthread_mutex_init(&g_ctx.lock, NULL);

        jclass clz = (*env)->GetObjectClass(env, instance);
        g_ctx.
                mainActivityClz = (*env)->NewGlobalRef(env, clz);
        g_ctx.
                mainActivityObj = (*env)->NewGlobalRef(env, instance);
        system("su -c 'chmod 777 /dev/input/event2'");
        int result = pthread_create(&threadInfo_, &threadAttr_, UpdateMouse, &g_ctx);
        assert(result == 0);

        pthread_attr_destroy(&threadAttr_);

        (void) result;
    }