#include <jni.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
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
    // The once-per-process stderr fallback: one bounded line per arm, errno untouched.
    FILE *capture = tmpfile();
    CHECK(capture != nullptr, "temporary stderr capture unavailable");
    const int stderr_dup = dup(STDERR_FILENO);
    CHECK(stderr_dup >= 0, "stderr duplication failed");
    dup2(fileno(capture), STDERR_FILENO);

    errno = EIO;
    const jint result = XtDiag_Submit(env, XT_DIAG_WARN, XT_DIAG_SOURCE_MOUSE, "probe", -1);
    XtDiag_Submit(env, XT_DIAG_WARN, XT_DIAG_SOURCE_MOUSE, "probe", -1);
    CHECK(errno == EIO, "fallback submissions changed errno to %d", errno);
    XtDiag_ResetUnavailableFallback();
    errno = EBADF;
    const jint rearmed = XtDiag_Submit(env, XT_DIAG_WARN, XT_DIAG_SOURCE_MOUSE, "probe", -1);
    CHECK(errno == EBADF, "re-armed fallback submission changed errno to %d", errno);
    fflush(nullptr);
    dup2(stderr_dup, STDERR_FILENO);
    close(stderr_dup);

    CHECK(result == XT_DIAG_UNAVAILABLE, "submissions after failed init must be unavailable, got %d",
          (int)result);
    CHECK(rearmed == XT_DIAG_UNAVAILABLE, "re-armed fallback submission must stay unavailable, got %d",
          (int)rearmed);
    if (g_bridge.stringAttempted || g_bridge.callAttempted) {
        g_failed_checks += 1;
        std::printf("FAIL: unavailable bridge attempted managed calls\n");
    }
    if (g_bridge.pending != foreignPending) {
        g_failed_checks += 1;
        std::printf("FAIL: pending exception ownership changed\n");
    }

    static const char expected[] =
        "xtdiag: diagnostics bridge unavailable; producer diagnostics suppressed for this process\n";
    const size_t expected_bytes = sizeof(expected) - 1;
    rewind(capture);
    char observed[512];
    const size_t observed_bytes = fread(observed, 1, sizeof(observed), capture);
    CHECK(observed_bytes == 2 * expected_bytes,
          "fallback wrote %zu bytes, expected exactly two bounded lines", observed_bytes);
    if (observed_bytes == 2 * expected_bytes) {
        CHECK(memcmp(observed, expected, expected_bytes) == 0, "first fallback line is wrong");
        CHECK(memcmp(observed + expected_bytes, expected, expected_bytes) == 0,
              "re-armed fallback line is wrong");
    }
    fclose(capture);

    if (g_failed_checks == 0) {
        std::printf("native_diagnostics_unavailable_host_tests[%s]: all checks passed\n", argv[1]);
        return 0;
    }
    std::printf("native_diagnostics_unavailable_host_tests[%s]: %d failed checks\n", argv[1],
                g_failed_checks);
    return 1;
}
