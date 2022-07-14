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

char str[16];
typedef struct mouse_context {
	JavaVM  *javaVM;
    pthread_mutex_t  lock;
	int      done;
	const char* dev;
} mouseContext;
mouseContext g_ctx;

struct Socket {
    int sock;
    int client_fd;
};
int fd;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    memset(&g_ctx, 0, sizeof(g_ctx));

    g_ctx.javaVM = vm;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR; // JNI version not supported.
    }

    g_ctx.done = 0;
    return  JNI_VERSION_1_6;
}


void create_socket(struct Socket *Socket){
    struct sockaddr_in serv_addr;
    if ((Socket->sock = socket(AF_INET, SOCK_STREAM, 0)) < 0) {
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

    if ((Socket->client_fd
                 = connect(Socket->sock, (struct sockaddr*)&serv_addr,
                           sizeof(serv_addr)))
        < 0) {
        printf("\nConnection Failed \n");
    }
}

void send_data(struct input_event *ie, struct Socket *x_cts)
{
    switch (ie->code) {
        case REL_X :
            //printf("%d %s\n", ie->value, "REL_X");
            sprintf(str, "REL_X %d \n", ie->value);
            send(x_cts->sock, str, strlen(str), 0);
            break;
        case REL_Y :
            //printf("%d %s\n", ie->value, "REL_Y");
            sprintf(str, "REL_Y %d \n", ie->value);
            send(x_cts->sock, str, strlen(str), 0);
            break;

        case BTN_MOUSE :
            //printf("BTN_MOUSE %d\n", ie->value);
            sprintf(str, "BTN_MOUSE %d \n", ie->value);
            send(x_cts->sock, str, strlen(str), 0);
    }
}

void * UpdateMouse(void* context) {
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
        struct input_event ie;
        struct Socket x_cts;

        if ((fd = open(pctx->dev, O_RDONLY)) == -1) {
            perror("opening device");
            exit(EXIT_FAILURE);
        }
      //ioctl(fd, EVIOCGRAB, (void *)1);

        create_socket(&x_cts);

        while (read(fd, &ie, sizeof(struct input_event))) {
            send_data(&ie, &x_cts);
        }

        close(fd);
        // closing the connected socket
        close(x_cts.client_fd);
        return 0;

    }

    (*javaVM)->DetachCurrentThread(javaVM);
    return context;
    }


    JNIEXPORT void JNICALL
    Java_com_xtr_keymapper_Input_startMouse(JNIEnv *env, jobject instance, jstring device) {
        pthread_t threadInfo_;
        pthread_attr_t threadAttr_;

        pthread_attr_init(&threadAttr_);
        pthread_attr_setdetachstate(&threadAttr_, PTHREAD_CREATE_DETACHED);

        pthread_mutex_init(&g_ctx.lock, NULL);

        jclass clz = (*env)->GetObjectClass(env, instance);
        (*env)->NewGlobalRef(env, clz);
        (*env)->NewGlobalRef(env, instance);
        //system("chown -hR $(whoami) -R /dev/input");
        setlinebuf(stdout);

        g_ctx.dev = (*env)->GetStringUTFChars(env, device, 0);
        int result = pthread_create(&threadInfo_, &threadAttr_, UpdateMouse, &g_ctx);
        assert(result == 0);

        pthread_attr_destroy(&threadAttr_);

        (void) result;
    }


JNIEXPORT void JNICALL
Java_com_xtr_keymapper_Input_setIoctl(JNIEnv *env, jclass clazz, jboolean y) {
    if ( fd != 0 ) {
        ioctl(fd, EVIOCGRAB, y);
        printf("ioctl successful: gained exclusive access to input device");
    }
    else {
        printf("warning: unable to ioctl: fd not initialized");
    }
}