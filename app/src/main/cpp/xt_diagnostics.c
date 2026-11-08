#include "xt_diagnostics.h"

#include <errno.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stddef.h>
#include <string.h>
#include <time.h>

// Per-library cache; written once on the loading thread and read-only after.
static jclass xt_diag_facade_class;
static jmethodID xt_diag_log_native_method;
static int xt_diag_unavailable;

// Set at most once per process: the only stderr output a losing bridge produces.
static atomic_flag xt_diag_fallback_written = ATOMIC_FLAG_INIT;

// One bounded stderr line when the bridge is unavailable (no init, foreign pending
// exception, JNI failure); preserves errno and depends on no JNI.
static void xt_diag_report_unavailable_fallback(void) {
    if (atomic_flag_test_and_set_explicit(&xt_diag_fallback_written, memory_order_seq_cst)) {
        return;
    }
    const int saved_errno = errno;
    fputs("xtdiag: diagnostics bridge unavailable; producer diagnostics suppressed for this process\n", stderr);
    errno = saved_errno;
}

jint XtDiag_Init(JNIEnv *env) {
    const int saved_errno = errno;
    jint result = JNI_ERR;
    if (xt_diag_facade_class != NULL) {
        result = JNI_OK;
        goto done;
    }
    if (xt_diag_unavailable) {
        goto done;
    }
    if (env == NULL || (*env)->ExceptionCheck(env)) {
        xt_diag_unavailable = 1;
        goto done;
    }
    jclass local = (*env)->FindClass(env, "xtr/keymapper/log/XtLogger");
    jmethodID method = local == NULL ? NULL : (*env)->GetStaticMethodID(
        env, local, "logNative", "(IILjava/lang/String;)I");
    jclass global = method == NULL ? NULL : (*env)->NewGlobalRef(env, local);
    if (local != NULL) {
        (*env)->DeleteLocalRef(env, local);
    }
    if (global == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        xt_diag_unavailable = 1;
        goto done;
    }
    xt_diag_facade_class = global;
    xt_diag_log_native_method = method;
    result = JNI_OK;
done:
    errno = saved_errno;
    return result;
}

// Decodes standard UTF-8 into UTF-16; returns false for truncated, overlong, surrogate-encoded or out-of-range sequences; out holds at most bytes jchars.
static int xt_diag_decode_utf8(const char *input, size_t bytes, jchar *out, size_t *outLen) {
    size_t i = 0;
    size_t len = 0;
    while (i < bytes) {
        unsigned char lead = (unsigned char)input[i];
        uint32_t code_point;
        size_t continuations;
        if (lead < 0x80) {
            code_point = lead;
            continuations = 0;
        } else if ((lead & 0xE0) == 0xC0) {
            code_point = lead & 0x1Fu;
            continuations = 1;
        } else if ((lead & 0xF0) == 0xE0) {
            code_point = lead & 0x0Fu;
            continuations = 2;
        } else if ((lead & 0xF8) == 0xF0) {
            code_point = lead & 0x07u;
            continuations = 3;
        } else {
            return 0;
        }
        if (i + 1 + continuations > bytes) {
            return 0;
        }
        for (size_t k = 1; k <= continuations; k++) {
            unsigned char continuation = (unsigned char)input[i + k];
            if ((continuation & 0xC0) != 0x80) {
                return 0;
            }
            code_point = (code_point << 6) | (continuation & 0x3Fu);
        }
        if ((continuations == 1 && code_point < 0x80u) ||
            (continuations == 2 && code_point < 0x800u) ||
            (continuations == 3 && code_point < 0x10000u)) {
            return 0; /* overlong encoding */
        }
        if (code_point >= 0xD800u && code_point <= 0xDFFFu) {
            return 0;
        }
        if (code_point > 0x10FFFFu) {
            return 0;
        }
        if (code_point < 0x10000u) {
            out[len++] = (jchar)code_point;
        } else {
            code_point -= 0x10000u;
            out[len++] = (jchar)(0xD800u | (code_point >> 10));
            out[len++] = (jchar)(0xDC00u | (code_point & 0x3FFu));
        }
        i += 1 + continuations;
    }
    *outLen = len;
    return 1;
}

static jint xt_diag_submit_encoded(JNIEnv *env, jint priority, jint source, const char *text) {
    if (env == NULL) {
        return XT_DIAG_UNAVAILABLE;
    }
    if (xt_diag_facade_class == NULL) {
        return XT_DIAG_UNAVAILABLE;
    }
    if ((*env)->ExceptionCheck(env)) {
        return XT_DIAG_UNAVAILABLE; /* never compete with a foreign pending exception */
    }

    size_t message_bytes = strnlen(text, XT_DIAG_MAX_UTF8_BYTES + 1);
    if (message_bytes == 0 || message_bytes > XT_DIAG_MAX_UTF8_BYTES) {
        return XT_DIAG_INVALID;
    }
    jchar utf16[XT_DIAG_MAX_UTF8_BYTES];
    size_t utf16_len = 0;
    if (!xt_diag_decode_utf8(text, message_bytes, utf16, &utf16_len)) {
        return XT_DIAG_INVALID;
    }

    jstring string = (*env)->NewString(env, utf16, (jsize)utf16_len);
    if (string == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        return XT_DIAG_UNAVAILABLE;
    }

    jint result = (*env)->CallStaticIntMethod(
        env, xt_diag_facade_class, xt_diag_log_native_method, priority, source, string);
    (*env)->DeleteLocalRef(env, string);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return XT_DIAG_UNAVAILABLE;
    }
    return result;
}

jint XtDiag_Submit(JNIEnv *env, int priority, int source, const char *text, long n) {
    const int saved_errno = errno;
    jint result = XT_DIAG_INVALID;
    if (priority < XT_DIAG_VERBOSE || priority > XT_DIAG_ERROR ||
        (source != XT_DIAG_SOURCE_MOUSE && source != XT_DIAG_SOURCE_KEYBOARD) ||
        text == NULL) {
        goto done;
    }
    if (n < 0) {
        result = xt_diag_submit_encoded(env, (jint)priority, (jint)source, text);
        goto done;
    }
    // Fixed producer text plus one bounded numeric suffix, formatted before JNI.
    char message[XT_DIAG_MAX_UTF8_BYTES + 1];
    int written = snprintf(message, sizeof(message), "%.*s%ld", XT_DIAG_MAX_UTF8_BYTES + 1, text, n);
    if (written >= 0 && (size_t)written < sizeof(message)) {
        result = xt_diag_submit_encoded(env, (jint)priority, (jint)source, message);
    }
done:
    if (result == XT_DIAG_UNAVAILABLE) {
        xt_diag_report_unavailable_fallback();
    }
    errno = saved_errno;
    return result;
}

// Host-test hook: lets a later unavailable result write the notice again.
XT_DIAG_API void XtDiag_ResetUnavailableFallback(void) {
    atomic_flag_clear_explicit(&xt_diag_fallback_written, memory_order_seq_cst);
}

jint XtDiag_SubmitEvent(JNIEnv *env, int priority, int source,
                        XtDiagEventGroup *group, const char *text) {
    uint64_t suppressed = 0;
    if (!XtDiagEvent_ShouldReport(group, XtDiag_NowNanos(), &suppressed)) {
        return XT_DIAG_AGGREGATED;
    }
    return XtDiag_Submit(env, priority, source, text, (long)suppressed);
}

int64_t XtDiag_NowNanos(void) {
    const int saved_errno = errno;
    struct timespec now = {0};
    clock_gettime(CLOCK_MONOTONIC, &now);
    const int64_t nanos = (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec;
    errno = saved_errno;
    return nanos;
}
