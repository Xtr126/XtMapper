#ifndef XT_DIAGNOSTICS_H
#define XT_DIAGNOSTICS_H

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include "xt_diag_events.h"

// Submission outcomes mirroring xtr.keymapper.log.LogSubmission; ACCEPTED means queue admission, not persistence.
enum {
    XT_DIAG_ACCEPTED = 0,
    XT_DIAG_CLOSED = 1,
    XT_DIAG_UNAVAILABLE = 2,
    XT_DIAG_INVALID = 3,
    XT_DIAG_FILTERED = 4,
    XT_DIAG_AGGREGATED = 5 // XtDiag_SubmitEvent only: the occurrence was counted and admitted no record.
};

#define XT_DIAG_SOURCE_MOUSE 0
#define XT_DIAG_SOURCE_KEYBOARD 1

// Android log priorities 2..6 FATAL is deliberately unsupported in phase one.
#define XT_DIAG_VERBOSE 2
#define XT_DIAG_DEBUG 3
#define XT_DIAG_INFO 4
#define XT_DIAG_WARN 5
#define XT_DIAG_ERROR 6

#define XT_DIAG_MAX_UTF8_BYTES 512

// Initializes the per-library cache from the loading thread's JNI_OnLoad; idempotent, a failed lookup leaves the bridge unavailable for the process lifetime. Returns JNI_OK when usable.
XT_DIAG_API jint XtDiag_Init(JNIEnv *env);

// Submits fixed UTF-8 text plus optional decimal n (n < 0 omits it); use the caller's JNIEnv outside native locks. Preserves errno and foreign exceptions.
XT_DIAG_API jint XtDiag_Submit(JNIEnv *env, int priority, int source,
                               const char *text, long n);

// Host-test hook: clears the once-per-process stderr fallback so a later unavailable result writes the notice again.
XT_DIAG_API void XtDiag_ResetUnavailableFallback(void);

// Aggregates repeated producer failures: the first occurrence and at most one report per second cross the bridge; everything else must be summarized through XtDiagEvent_TakeResidual at worker exit.
XT_DIAG_API jint XtDiag_SubmitEvent(JNIEnv *env, int priority, int source,
                                    XtDiagEventGroup *group, const char *text);

XT_DIAG_API int64_t XtDiag_NowNanos(void);

#endif
