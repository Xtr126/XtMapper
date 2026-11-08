#ifndef FAKE_JNI_ENV_H
#define FAKE_JNI_ENV_H

#include <cstdarg>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <jni.h>
#include <string>
#include <type_traits>

struct FakeBridge {
    bool findClassFails = false;
    std::string findClassNameRequested;
    bool methodLookupFails = false;
    bool objectMethodLookupFails = false;
    bool globalRefFails = false;
    bool callFails = false;
    bool voidCallFails = false;
    int voidCallCount = 0;
    int jniErrno = 0;
    void (*onStaticCall)() = nullptr;
    std::string staticMethodRequested;
    std::string staticMethodSignatureRequested;
    bool newStringFails = false;
    bool stringAttempted = false;
    std::u16string createdMessage;
    bool callAttempted = false;
    int staticCallCount = 0;
    jint priorityArg = -1;
    jint sourceArg = -1;
    std::u16string messageArg;
    jint managedReturnValue = 0;
    bool pending = false;
    int exceptionCheckCount = 0;
    int exceptionClearCount = 0;
    int globalRefCount = 0;
    int deleteLocalRefCount = 0;
    int deleteGlobalRefCount = 0;
    bool utfCharsFail = false;
    int utfCharsCount = 0;
    int utfReleaseCount = 0;
    std::string utfCharsValue;

    void reset() { *this = FakeBridge(); }
};

inline FakeBridge g_bridge;

inline jclass fakeClassHandle() { return reinterpret_cast<jclass>(0x11); }
inline jstring fakeStringHandle() { return reinterpret_cast<jstring>(0x22); }
inline jmethodID fakeMethodHandle() { return reinterpret_cast<jmethodID>(0x33); }

inline jint JNICALL fakeGetVersion(JNIEnv *) { return JNI_VERSION_1_6; }

inline jclass JNICALL fakeFindClass(JNIEnv *, const char *name) {
    g_bridge.findClassNameRequested = name;
    if (g_bridge.findClassFails) {
        g_bridge.pending = true;
        return nullptr;
    }
    return fakeClassHandle();
}

inline jobject JNICALL fakeNewGlobalRef(JNIEnv *, jobject object) {
    if (g_bridge.globalRefFails) {
        g_bridge.pending = true;
        return nullptr;
    }
    g_bridge.globalRefCount += 1;
    return object;
}

inline jmethodID JNICALL fakeGetStaticMethodID(JNIEnv *, jclass, const char *name, const char *sig) {
    g_bridge.staticMethodRequested = name;
    g_bridge.staticMethodSignatureRequested = sig;
    if (g_bridge.methodLookupFails) {
        g_bridge.pending = true;
        return nullptr;
    }
    return fakeMethodHandle();
}

inline jstring JNICALL fakeNewString(JNIEnv *, const jchar *chars, jsize length) {
    g_bridge.stringAttempted = true;
    if (g_bridge.newStringFails) {
        g_bridge.pending = true;
        return nullptr;
    }
    if (chars != nullptr && length > 0) {
        g_bridge.createdMessage.assign(chars, chars + length);
    } else {
        g_bridge.createdMessage.clear();
    }
    return fakeStringHandle();
}

inline jint JNICALL fakeCallStaticIntMethod(JNIEnv *, jclass, jmethodID mid, ...) {
    g_bridge.callAttempted = true;
    g_bridge.staticCallCount += 1;
    va_list args;
    va_start(args, mid);
    g_bridge.priorityArg = va_arg(args, jint);
    g_bridge.sourceArg = va_arg(args, jint);
    (void)va_arg(args, jstring); // and the string content is recorded by fakeNewString
    va_end(args);
    g_bridge.messageArg = g_bridge.createdMessage;
    if (g_bridge.onStaticCall != nullptr) g_bridge.onStaticCall();
    if (g_bridge.callFails) g_bridge.pending = true;
    return g_bridge.managedReturnValue;
}

inline jboolean JNICALL fakeExceptionCheck(JNIEnv *) {
    if (g_bridge.jniErrno != 0) errno = g_bridge.jniErrno;
    g_bridge.exceptionCheckCount += 1;
    return g_bridge.pending ? JNI_TRUE : JNI_FALSE;
}

inline void JNICALL fakeExceptionClear(JNIEnv *) {
    g_bridge.exceptionClearCount += 1;
    g_bridge.pending = false;
}

inline void JNICALL fakeDeleteLocalRef(JNIEnv *, jobject) { g_bridge.deleteLocalRefCount += 1; }
inline void JNICALL fakeDeleteGlobalRef(JNIEnv *, jobject) { g_bridge.deleteGlobalRefCount += 1; }

inline const char *JNICALL fakeGetStringUTFChars(JNIEnv *, jstring, jboolean *isCopy) {
    if (g_bridge.utfCharsFail) {
        g_bridge.pending = true;
        return nullptr;
    }
    g_bridge.utfCharsCount += 1;
    if (isCopy != nullptr) *isCopy = JNI_TRUE;
    return g_bridge.utfCharsValue.c_str();
}

inline void JNICALL fakeReleaseStringUTFChars(JNIEnv *, jstring, const char *) {
    g_bridge.utfReleaseCount += 1;
}

inline jclass JNICALL fakeGetObjectClass(JNIEnv *, jobject) { return fakeClassHandle(); }

inline jmethodID JNICALL fakeGetMethodID(JNIEnv *, jclass, const char *, const char *) {
    if (g_bridge.objectMethodLookupFails) {
        g_bridge.pending = true;
        return nullptr;
    }
    return fakeMethodHandle();
}

inline jstring JNICALL fakeNewStringUTF(JNIEnv *, const char *) { return fakeStringHandle(); }

inline void JNICALL fakeCallVoidMethod(JNIEnv *, jobject, jmethodID, ...) {
    g_bridge.voidCallCount++;
    if (g_bridge.voidCallFails) g_bridge.pending = true;
}

inline void JNICALL fakeCallVoidMethodV(JNIEnv *env, jobject obj, jmethodID method, va_list) {
    fakeCallVoidMethod(env, obj, method);
}

// Use the real JDK or NDK table layout; unexpected JNI calls hit an empty slot.
inline JNIEnv *make_fake_env() {
    static std::remove_const_t<std::remove_pointer_t<decltype(JNIEnv{}.functions)>> table{};
    table.GetVersion = fakeGetVersion;
    table.FindClass = fakeFindClass;
    table.NewGlobalRef = fakeNewGlobalRef;
    table.GetStaticMethodID = fakeGetStaticMethodID;
    table.NewString = fakeNewString;
    table.CallStaticIntMethod = fakeCallStaticIntMethod;
    table.ExceptionCheck = fakeExceptionCheck;
    table.ExceptionClear = fakeExceptionClear;
    table.DeleteLocalRef = fakeDeleteLocalRef;
    table.DeleteGlobalRef = fakeDeleteGlobalRef;
    table.GetObjectClass = fakeGetObjectClass;
    table.GetMethodID = fakeGetMethodID;
    table.NewStringUTF = fakeNewStringUTF;
    table.GetStringUTFChars = fakeGetStringUTFChars;
    table.ReleaseStringUTFChars = fakeReleaseStringUTFChars;
    table.CallVoidMethod = fakeCallVoidMethod;
    table.CallVoidMethodV = fakeCallVoidMethodV;
    static JNIEnv holder{};
    holder.functions = &table;
    return &holder;
}

inline JavaVM *make_fake_vm() {
    static std::remove_const_t<std::remove_pointer_t<decltype(JavaVM{}.functions)>> table{};
    table.GetEnv = [](JavaVM *, void **env, jint) -> jint {
        *env = make_fake_env();
        return JNI_OK;
    };
    table.AttachCurrentThread = [](JavaVM *, auto env, void *) -> jint {
        *env = make_fake_env();
        return JNI_OK;
    };
    table.DetachCurrentThread = [](JavaVM *) -> jint { return JNI_OK; };
    static JavaVM vm{};
    vm.functions = &table;
    return &vm;
}

inline int g_failed_checks = 0;

#define CHECK(cond, ...)                                                    \
    do {                                                                    \
        if (!(cond)) {                                                      \
            g_failed_checks += 1;                                           \
            std::printf("FAIL %s:%d ", __FILE__, __LINE__);                 \
            std::printf(__VA_ARGS__);                                       \
            std::printf("\n");                                              \
        }                                                                   \
    } while (0)

#endif
