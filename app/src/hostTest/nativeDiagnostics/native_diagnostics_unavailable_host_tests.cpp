#include <jni.h>
#include <cstdio>
#include "fake_jni_env.h"

extern "C" {
#include "xt_diagnostics.h"
}

int main(int argc, char **argv) {
    if (argc != 2) {
        std::printf("usage: %s findclass|method|globalref|pending\n", argv[0]);
        return 2;
    }
    if (std::strcmp(argv[1], "findclass") == 0) {
        g_bridge.findClassFails = true;
    } else if (std::strcmp(argv[1], "method") == 0) {
        g_bridge.methodLookupFails = true;
    } else if (std::strcmp(argv[1], "globalref") == 0) {
        g_bridge.globalRefFails = true;
    } else if (std::strcmp(argv[1], "pending") == 0) {
        g_bridge.pending = true;
    } else {
        std::printf("unknown variant %s\n", argv[1]);
        return 2;
    }

    JNIEnv *env = make_fake_env();
    const bool foreignPending = g_bridge.pending;
    g_bridge.jniErrno = EBADF;
    errno = EACCES;
    if (XtDiag_Init(env) != JNI_ERR) {
        g_failed_checks += 1;
        std::printf("FAIL: a failed lookup must return JNI_ERR\n");
    }
    if (g_bridge.globalRefCount != 0) {
        g_failed_checks += 1;
        std::printf("FAIL: failed init must not keep a global ref (saw %d)\n",
                    g_bridge.globalRefCount);
    }
    CHECK(errno == EACCES, "initialization changed errno to %d", errno);
    if (g_bridge.methodLookupFails || g_bridge.globalRefFails) {
        CHECK(g_bridge.deleteLocalRefCount == 1, "failed initialization leaked its class local");
    }
    if (foreignPending) {
        CHECK(g_bridge.findClassNameRequested.empty(), "initialization called JNI with a foreign exception");
        CHECK(g_bridge.exceptionClearCount == 0, "initialization cleared a foreign exception");
    }
    if (XtDiag_Init(env) != JNI_ERR) {
        g_failed_checks += 1;
        std::printf("FAIL: repeated init after failure must stay JNI_ERR\n");
    }
    const jint result = XtDiag_Submit(env, XT_DIAG_WARN, XT_DIAG_SOURCE_MOUSE, "probe", -1);
    if (result != XT_DIAG_UNAVAILABLE) {
        g_failed_checks += 1;
        std::printf("FAIL: submissions after failed init must be unavailable, got %d\n",
                    (int)result);
    }
    if (g_bridge.stringAttempted || g_bridge.callAttempted) {
        g_failed_checks += 1;
        std::printf("FAIL: unavailable bridge attempted managed calls\n");
    }
    if (g_bridge.pending != foreignPending) {
        g_failed_checks += 1;
        std::printf("FAIL: pending exception ownership changed\n");
    }

    if (g_failed_checks == 0) {
        std::printf("native_diagnostics_unavailable_host_tests[%s]: all checks passed\n", argv[1]);
        return 0;
    }
    std::printf("native_diagnostics_unavailable_host_tests[%s]: %d failed checks\n", argv[1],
                g_failed_checks);
    return 1;
}
