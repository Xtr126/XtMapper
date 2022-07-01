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

#include <arpa/inet.h>
#include <sys/socket.h>
#define PORT 6345


static const char* kTAG = "mouse_read";

typedef struct mouse_context {
	JavaVM  *javaVM;
	jclass   jniHelperClz;
	jobject  jniHelperObj;
	jclass   InputClz;
	jobject  InputObj;
	pthread_mutex_t  lock;
	int      done;
} mouseContext;
mouseContext g_ctx;


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

    g_ctx.done = 0;
    g_ctx.InputObj = NULL;
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

    // get Input updateTimer function
    jmethodID timerId = (*env)->GetMethodID(env, pctx->InputClz,
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
       /* jmethodID updateX = (*env)->GetMethodID(env, pctx->InputClz,
                                                 "updateMouseX", "()V");
        jmethodID updateY = (*env)->GetMethodID(env, pctx->InputClz,
                                                "updateMouseX", "()V"); */
        int fd;
        const char *dev = "/dev/input/event2";
        struct input_event ie;

        if ((fd = open(dev, O_RDONLY)) == -1) {
            perror("opening device");
            exit(EXIT_FAILURE);
        }
//        ioctl(fd, EVIOCGRAB, (void *)1);

        int sock = 0, client_fd;
        struct sockaddr_in serv_addr;

        if ((sock = socket(AF_INET, SOCK_STREAM, 0)) < 0) {
            printf("\n Socket creation error \n");
        }

        serv_addr.sin_family = AF_INET;
        serv_addr.sin_port = htons(PORT);

        // Convert IPv4 and IPv6 addresses from text to binary
        // form
        if (inet_pton(AF_INET, "127.0.0.1", &serv_addr.sin_addr)
            <= 0) {
            printf(
                    "\nInvalid address/ Address not supported \n");
        }

        if ((client_fd
                     = connect(sock, (struct sockaddr*)&serv_addr,
                               sizeof(serv_addr)))
            < 0) {
            printf("\nConnection Failed \n");
        }

        while (read(fd, &ie, sizeof(struct input_event))) {
            char str[16];
            if (ie.code == REL_X)
                printf("%d %s\n", ie.value, "REL_X");
               // (*env)->CallVoidMethod(env, pctx->InputObj, updateX, (jint) ie.value);
            sprintf(str, "REL_X %d \n", ie.value);
            send(sock, str, strlen(str), 0);

            if (ie.code == REL_Y)
                printf("%d %s\n", ie.value, "REL_Y");
            //  (*env)->CallVoidMethod(env, pctx->InputObj, updateY, (jint) ie.value);
            sprintf(str, "REL_Y %d \n", ie.value);
            send(sock, str, strlen(str), 0);
        }
        close(fd);
        // closing the connected socket
        close(client_fd);
        return 0;
       // (*env)->CallVoidMethod(env, pctx->InputObj, timerId);


    }

    sendJavaMsg(env, pctx->jniHelperObj, statusId,
                "MouseThread status: read");
    (*javaVM)->DetachCurrentThread(javaVM);
    return context;
    }


    JNIEXPORT void JNICALL
    Java_com_xtr_keymapper_Input_startMouse(JNIEnv *env, jobject instance) {
        pthread_t threadInfo_;
        pthread_attr_t threadAttr_;

        pthread_attr_init(&threadAttr_);
        pthread_attr_setdetachstate(&threadAttr_, PTHREAD_CREATE_DETACHED);

        pthread_mutex_init(&g_ctx.lock, NULL);

        jclass clz = (*env)->GetObjectClass(env, instance);
        g_ctx.
                InputClz = (*env)->NewGlobalRef(env, clz);
        g_ctx.
                InputObj = (*env)->NewGlobalRef(env, instance);
        //system("chown -hR $(whoami) -R /dev/input");
        int result = pthread_create(&threadInfo_, &threadAttr_, UpdateMouse, &g_ctx);
        assert(result == 0);

        pthread_attr_destroy(&threadAttr_);

        (void) result;
    }