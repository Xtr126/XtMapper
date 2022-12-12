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
#include <sys/wait.h>
#include <paths.h>


char str[16];

typedef struct socket_context {
    JavaVM  *javaVM;
    int client_fd;
    int server_fd;
    struct sockaddr_in address;
    int addrlen;
} socketContext;
socketContext s_ctx;

typedef struct mouse_context {
    JavaVM  *javaVM;
    pthread_mutex_t  lock;
    int done;
} mouseContext;
mouseContext g_ctx;

int mouse_fd, port;
const char* device;
int s;

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


void create_socket(void* context){
    socketContext *sock = (socketContext*) context;
    int PORT = port;
    int opt = 1;
    sock->addrlen = sizeof(sock->address);
    // Creating socket file descriptor
    if ((sock->server_fd = socket(AF_INET, SOCK_STREAM, 0)) < 0) {
        printf("mouse_read: Socket creation error \n");
    }

    // Forcefully attaching socket to the port
    if (setsockopt(sock->server_fd, SOL_SOCKET,
                   SO_REUSEADDR | SO_REUSEPORT, &opt,
                   sizeof(opt))) {
        printf("mouse_read: setsockopt error \n");
    }

    sock->address.sin_family = AF_INET;
    sock->address.sin_port = htons(PORT);
    sock->address.sin_addr.s_addr = INADDR_ANY;

    if (bind(sock->server_fd, (struct sockaddr*)&(sock->address),
             sizeof(sock->address)) < 0) {
        printf("mouse_read: socket bind error \n");
    }

    if (listen(sock->server_fd, 2) < 0) {
        printf("mouse_read: socket listen failed \n");
    }
}

void* send_mouse_events(void* context) {
    socketContext *sock = (socketContext*) context;
    JavaVM *javaVM = sock->javaVM;
    JNIEnv *env;
    jint res = (*javaVM)->GetEnv(javaVM, (void**)&env, JNI_VERSION_1_6);
    if (res != JNI_OK) {
        res = (*javaVM)->AttachCurrentThread(javaVM, &env, NULL);
        if (JNI_OK != res) {
            return NULL;
        }
    }
    struct input_event ie;

    while (read(mouse_fd, &ie, sizeof(struct input_event))) {
        switch (ie.code) {
            case REL_X :
                sprintf(str, "REL_X %d \n", ie.value * s);
                send(sock->client_fd, str, strlen(str), 0);
                break;
            case REL_Y :
                sprintf(str, "REL_Y %d \n", ie.value * s);
                send(sock->client_fd, str, strlen(str), 0);
                break;
            case REL_WHEEL :
                sprintf(str, "REL_WHEEL %d \n", ie.value);
                send(sock->client_fd, str, strlen(str), 0);
                break;
            case BTN_MOUSE :
                sprintf(str, "BTN_MOUSE %d \n", ie.value);
                send(sock->client_fd, str, strlen(str), 0);
                break;
        }
    }
    close(mouse_fd);
    close(sock->client_fd);
    (*javaVM)->DetachCurrentThread(javaVM);
    return context;
}

void* send_getevent(void *context) {
    socketContext *sock = (socketContext*) context;
    switch (fork()) {
        case -1:    /* Error. */
            exit(EXIT_FAILURE);  /* exit if fork() fails */
        case  0: {    /* In the child process: */
            //child_pid = getpid(); /* save child pid for killing process later */
            dup2(sock->client_fd, STDOUT_FILENO);  /* duplicate socket on stdout */
            dup2(sock->client_fd, STDERR_FILENO);  /* duplicate socket on stderr too */
            close(sock->client_fd);  /* can close the original after it's duplicated */
            char *argp[] = {"sh", "-c",
                            "$LD_LIBRARY_PATH/libgetevent.so -ql",NULL};
            execve(_PATH_BSHELL, argp, environ);
            _exit(127);
            /* NOTREACHED */
        }
    }
    return context;
}

/*
 * Main working thread function. From a pthread,
 *     create socket and listen for msg from TouchPointer::MouseEventHandler::connect()
 *     according to msg received, create new pthread for send_mouse_events() or
 *     redirect getevent stdout to socket for TouchPointer::KeyEventHandler
 */
void* init(void* context) {
    mouseContext *pctx = (mouseContext*) context;
    s_ctx.javaVM = pctx->javaVM;
    JavaVM *javaVM = pctx->javaVM;
    JNIEnv *env;
    jint res = (*javaVM)->GetEnv(javaVM, (void**)&env, JNI_VERSION_1_6);
    if (res != JNI_OK) {
        res = (*javaVM)->AttachCurrentThread(javaVM, &env, NULL);
        if (JNI_OK != res) {
            return NULL;
        }
    }
    create_socket(&s_ctx);
    printf("Waiting for overlay...\n");
    char dev[24] = { 0 };
    strcpy(dev, device);
    int mouse_read_fd;
    while(true) {
        pthread_mutex_lock(&pctx->lock);
        int done = pctx->done;
        if (pctx->done) {
            pctx->done = 0;
        }
        pthread_mutex_unlock(&pctx->lock);
        if (done) {
            break;
        }
        pthread_t threadInfo_;
        pthread_attr_t threadAttr_;

        pthread_attr_init(&threadAttr_);
        pthread_attr_setdetachstate(&threadAttr_, PTHREAD_CREATE_DETACHED);

        struct sockaddr_in address = s_ctx.address;
        int addrlen = s_ctx.addrlen;
        if ((s_ctx.client_fd
                     = accept(s_ctx.server_fd, (struct sockaddr*)&address,
                              (socklen_t*)&addrlen)) < 0) {
            printf("mouse_read: connection failed \n");
        }

        char buffer[24] = { 0 };
        read(s_ctx.client_fd, buffer, 24);
        printf("msg: %s", buffer);

        if (strcmp(buffer, "mouse_read\n") == 0) {
            mouse_read_fd = s_ctx.client_fd;
            if ((mouse_fd = open(dev, O_RDONLY)) == -1) {
                perror("opening device");
                write(mouse_read_fd, "error\n", strlen("error\n"));
            } else {
                ioctl(mouse_fd, EVIOCGRAB, (void *)1);
                pthread_create(&threadInfo_, &threadAttr_, send_mouse_events, &s_ctx);
            }
        }
        else if (strcmp(buffer, "getevent\n") == 0) {
            pthread_create(&threadInfo_, &threadAttr_, send_getevent, &s_ctx);
        }
        else if (strcmp(buffer, "stop\n") == 0) {
            close(mouse_fd);
        }
        else if (strcmp(buffer, "change_device\n") == 0) {
            printf("device change");
            char new_device[24];
            read(s_ctx.client_fd, new_device, 24);
            printf(": %s", new_device);
            strcpy(dev, new_device);
            write(mouse_read_fd, "restart\n", strlen("restart\n"));
        }
        pthread_attr_destroy(&threadAttr_);
    }
    close(s_ctx.server_fd); // closing the listening socket
    (*javaVM)->DetachCurrentThread(javaVM);
    return context;
    }

/*
* Interface to Java side to start, caller is from main()
*/
JNIEXPORT void JNICALL
    Java_xtr_keymapper_Input_startMouse(JNIEnv *env, jobject instance, jstring dev, jstring mouse_sensitivity, jint default_port) {
        setlinebuf(stdout);

        device = (*env)->GetStringUTFChars(env, dev, 0);
        s = atoi((*env)->GetStringUTFChars(env, mouse_sensitivity, 0));

        (*env)->ReleaseStringUTFChars(env, dev ,0);
        (*env)->ReleaseStringUTFChars(env, mouse_sensitivity ,0);

        port = default_port;
        pthread_t threadInfo_;
        pthread_attr_t threadAttr_;

        pthread_attr_init(&threadAttr_);
        pthread_attr_setdetachstate(&threadAttr_, PTHREAD_CREATE_DETACHED);

        pthread_mutex_init(&g_ctx.lock, NULL);

        jclass clz = (*env)->GetObjectClass(env, instance);
        (*env)->NewGlobalRef(env, clz);
        (*env)->NewGlobalRef(env, instance);
        int result = pthread_create(&threadInfo_, &threadAttr_, init, &g_ctx);
        assert(result == 0);
        pthread_attr_destroy(&threadAttr_);
        (void) result;
    }



