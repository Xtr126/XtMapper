#include <jni.h>
#include <cstdint>
#include <cstdio>
#include <cerrno>
#include <ctime>
#include <string>
#include "fake_jni_env.h"

extern "C" {
#include "xt_diagnostics.h"
#include "xt_diag_events.h"
}

static void resetFakeBridge() { g_bridge.reset(); }

static int64_t fakeNanos = 0;
extern "C" int __wrap_clock_gettime(clockid_t, timespec *now) {
    now->tv_sec = fakeNanos / 1000000000LL;
    now->tv_nsec = fakeNanos % 1000000000LL;
    errno = EBUSY;
    return 0;
}

static void test_init_caches_the_facade_once(JNIEnv *env) {
    resetFakeBridge();
    CHECK(XtDiag_Init(env) == JNI_OK, "init should succeed against the fake facade");
    CHECK(g_bridge.findClassNameRequested == "xtr/keymapper/log/XtLogger", "FindClass name is %s",
          g_bridge.findClassNameRequested.c_str());
    CHECK(g_bridge.staticMethodRequested == "logNative", "method name is %s",
          g_bridge.staticMethodRequested.c_str());
    CHECK(g_bridge.staticMethodSignatureRequested == "(IILjava/lang/String;)I",
          "method signature is %s", g_bridge.staticMethodSignatureRequested.c_str());
    CHECK(g_bridge.globalRefCount == 1, "one class global ref after init, saw %d",
          g_bridge.globalRefCount);
    const int refs_before = g_bridge.globalRefCount;
    CHECK(XtDiag_Init(env) == JNI_OK, "repeated init stays OK");
    CHECK(g_bridge.globalRefCount == refs_before, "repeated init leaks refs (%d -> %d)",
          refs_before, g_bridge.globalRefCount);
}

static void test_submit_formats_fixed_text_with_numeric_suffix(JNIEnv *env) {
    resetFakeBridge();
    const jint result = XtDiag_Submit(env, XT_DIAG_WARN, XT_DIAG_SOURCE_MOUSE,
                                      "mouse EVIOCGRAB failed errno=", 22);
    CHECK(result == XT_DIAG_ACCEPTED, "submit returned %d", (int)result);
    CHECK(g_bridge.callAttempted, "bootstrap reached CallStaticIntMethod");
    CHECK(g_bridge.priorityArg == XT_DIAG_WARN && g_bridge.sourceArg == XT_DIAG_SOURCE_MOUSE,
          "args are priority=%d source=%d", (int)g_bridge.priorityArg, (int)g_bridge.sourceArg);
    CHECK(g_bridge.messageArg == u"mouse EVIOCGRAB failed errno=22", "message is wrong");
    CHECK(g_bridge.messageArg.size() <= XT_DIAG_MAX_UTF8_BYTES, "message exceeds the byte bound");
    CHECK(g_bridge.deleteLocalRefCount >= 1, "message local ref was not deleted");
}

static void test_submit_passes_through_the_managed_result(JNIEnv *env) {
    resetFakeBridge();
    g_bridge.managedReturnValue = XT_DIAG_FILTERED;
    CHECK(XtDiag_Submit(env, XT_DIAG_INFO, XT_DIAG_SOURCE_KEYBOARD, "keyboard filtered probe", -1) ==
              XT_DIAG_FILTERED,
          "managed FILTERED must pass through");
    g_bridge.managedReturnValue = XT_DIAG_CLOSED;
    CHECK(XtDiag_Submit(env, XT_DIAG_INFO, XT_DIAG_SOURCE_KEYBOARD, "keyboard closed probe", -1) ==
              XT_DIAG_CLOSED,
          "managed CLOSED must pass through");
}

static void test_submit_decodes_utf8_strictly(JNIEnv *env) {
    resetFakeBridge();
    CHECK(XtDiag_Submit(env, XT_DIAG_WARN, XT_DIAG_SOURCE_KEYBOARD, "héllo", -1) ==
              XT_DIAG_ACCEPTED,
          "two-byte utf8 submit rejected");
    CHECK(g_bridge.createdMessage.size() == 5, "expected 5 utf16 code units, got %zu",
          g_bridge.createdMessage.size());
    CHECK((uint16_t)g_bridge.createdMessage[1] == 0x00E9 &&
              g_bridge.createdMessage[2] == u'l' && g_bridge.createdMessage[3] == u'l' &&
              g_bridge.createdMessage[4] == u'o',
          "two-byte utf8 sequence decoded incorrectly");

    resetFakeBridge();
    CHECK(XtDiag_Submit(env, XT_DIAG_WARN, XT_DIAG_SOURCE_KEYBOARD, "数据", -1) ==
              XT_DIAG_ACCEPTED,
          "three-byte utf8 submit rejected");
    CHECK(g_bridge.createdMessage.size() == 2 && g_bridge.createdMessage[0] == u'数' &&
              g_bridge.createdMessage[1] == u'据',
          "three-byte utf8 sequence decoded incorrectly");

    resetFakeBridge();
    CHECK(XtDiag_Submit(env, XT_DIAG_ERROR, XT_DIAG_SOURCE_MOUSE, "buf\U0001F600", -1) ==
              XT_DIAG_ACCEPTED,
          "surrogate pairing submit rejected");
    CHECK((uint16_t)g_bridge.createdMessage[3] == 0xD83D &&
              (uint16_t)g_bridge.createdMessage[4] == 0xDE00,
          "four-byte utf8 must decode to a surrogate pair");
    CHECK(g_bridge.createdMessage.size() == 5, "expected 5 utf16 units after the emoji");
}

static void test_submit_rejects_invalid_utf8_without_jni_calls(JNIEnv *env) {
    resetFakeBridge();
    const char *invalid_samples[] = {
        "ok\xC0\x80",      // overlong two-byte encoding of NUL
        "ok\xC3",          // truncated two-byte sequence
        "ok\xED\xA0\x80",  // UTF-8-encoded surrogate
        "ok\xF4\x90\x80\x80", // above U+10FFFF
        "ok\x80",          // lone continuation byte
        "ok\xF0\x9F\x98",  // truncated four-byte sequence
    };
    for (const char *sample : invalid_samples) {
        const jint result = XtDiag_Submit(env, XT_DIAG_INFO, XT_DIAG_SOURCE_MOUSE, sample, -1);
        CHECK(result == XT_DIAG_INVALID, "sample returned %d", (int)result);
        CHECK(!g_bridge.stringAttempted, "NewString was attempted for invalid input");
        CHECK(!g_bridge.pending, "helper left a pending exception");
    }
}

static void test_submit_enforces_the_utf8_byte_bound(JNIEnv *env) {
    resetFakeBridge();
    std::string exact(XT_DIAG_MAX_UTF8_BYTES, 'a');
    CHECK(XtDiag_Submit(env, XT_DIAG_INFO, XT_DIAG_SOURCE_MOUSE, exact.c_str(), -1) ==
              XT_DIAG_ACCEPTED,
          "512-byte message must be accepted");
    CHECK(g_bridge.createdMessage.size() == XT_DIAG_MAX_UTF8_BYTES, "wrong decode length");

    resetFakeBridge();
    std::string oversized(XT_DIAG_MAX_UTF8_BYTES + 1, 'b');
    CHECK(XtDiag_Submit(env, XT_DIAG_INFO, XT_DIAG_SOURCE_MOUSE, oversized.c_str(), -1) ==
              XT_DIAG_INVALID,
          "513-byte message must be rejected");
    CHECK(!g_bridge.stringAttempted, "NewString was attempted for an oversized payload");
    CHECK(!g_bridge.pending, "helper left a pending exception after oversize");

    resetFakeBridge();
    CHECK(XtDiag_Submit(env, XT_DIAG_INFO, XT_DIAG_SOURCE_MOUSE, "", -1) == XT_DIAG_INVALID,
          "empty message must be rejected");
    CHECK(XtDiag_Submit(env, XT_DIAG_INFO, XT_DIAG_SOURCE_MOUSE, nullptr, -1) == XT_DIAG_INVALID,
          "null text must be rejected without crashing");
    CHECK(!g_bridge.stringAttempted, "NewString was attempted for empty/null input");
}

static void test_submit_rejects_unknown_priority_and_source(JNIEnv *env) {
    resetFakeBridge();
    for (int priority : {XT_DIAG_VERBOSE - 1, XT_DIAG_ERROR + 1, 1, 7, 0, -1}) {
        CHECK(XtDiag_Submit(env, priority, XT_DIAG_SOURCE_MOUSE, "probe", -1) == XT_DIAG_INVALID,
              "priority %d must be invalid", priority);
    }
    for (int source : {XT_DIAG_SOURCE_MOUSE - 1, XT_DIAG_SOURCE_KEYBOARD + 1, 2, 3, -1}) {
        CHECK(XtDiag_Submit(env, XT_DIAG_WARN, source, "probe", -1) == XT_DIAG_INVALID,
              "source %d must be invalid", source);
    }
    CHECK(!g_bridge.stringAttempted && !g_bridge.callAttempted,
          "invalid submissions must not reach JNI string or call");
    CHECK(!g_bridge.pending, "helper left a pending exception");
}

static void test_pending_exception_freezes_the_bridge(JNIEnv *env) {
    resetFakeBridge();
    g_bridge.pending = true;
    CHECK(XtDiag_Submit(env, XT_DIAG_WARN, XT_DIAG_SOURCE_MOUSE, "probe", -1) ==
              XT_DIAG_UNAVAILABLE,
          "pending exception must report the bridge unavailable");
    CHECK(!g_bridge.stringAttempted && !g_bridge.callAttempted,
          "the bridge attempted calls while an exception was pending");
    CHECK(g_bridge.exceptionClearCount == 0, "the helper cleared a foreign exception");
    CHECK(g_bridge.pending, "the helper removed the pending exception");
}

static void test_newstring_failure_reports_unavailable_and_cleans_up(JNIEnv *env) {
    resetFakeBridge();
    g_bridge.newStringFails = true;
    CHECK(XtDiag_Submit(env, XT_DIAG_WARN, XT_DIAG_SOURCE_MOUSE, "probe", -1) ==
              XT_DIAG_UNAVAILABLE,
          "NewString failure must be unavailable, not accepted");
    CHECK(g_bridge.exceptionClearCount >= 1, "the helper did not clear its own exception");
    CHECK(!g_bridge.pending, "the helper left its own exception pending");
    CHECK(!g_bridge.callAttempted, "no managed call may follow a failed string creation");
}

static void test_managed_call_failure_is_unavailable_and_releases_the_string(JNIEnv *env) {
    resetFakeBridge();
    g_bridge.callFails = true;
    CHECK(XtDiag_Submit(env, XT_DIAG_ERROR, XT_DIAG_SOURCE_MOUSE, "probe", -1) == XT_DIAG_UNAVAILABLE,
          "managed exception must not report admission");
    CHECK(g_bridge.deleteLocalRefCount == 1, "failed managed call leaked its string");
    CHECK(g_bridge.exceptionClearCount == 1 && !g_bridge.pending, "own managed exception was not cleared");
}

static void test_submissions_preserve_errno_on_success_and_failure(JNIEnv *env) {
    for (bool fail : {false, true}) {
        resetFakeBridge();
        g_bridge.callFails = fail;
        g_bridge.jniErrno = EBADF;
        errno = EACCES;
        XtDiag_Submit(env, XT_DIAG_WARN, XT_DIAG_SOURCE_MOUSE, "errno=", 22);
        CHECK(errno == EACCES, "JNI changed the caller's errno to %d", errno);
    }
    resetFakeBridge();
    XtDiagEventGroup group;
    XtDiagEvent_Init(&group);
    errno = EINTR;
    XtDiag_SubmitEvent(env, XT_DIAG_ERROR, XT_DIAG_SOURCE_KEYBOARD, &group, "failure; suppressed=");
    CHECK(errno == EINTR, "event reporting changed errno to %d", errno);
    errno = ENODEV;
    XtDiag_NowNanos();
    CHECK(errno == ENODEV, "the clock helper changed errno to %d", errno);
}

static void test_each_submission_deletes_its_local_references(JNIEnv *env) {
    resetFakeBridge();
    const int locals_before = g_bridge.deleteLocalRefCount;
    for (int i = 0; i < 3; i++) {
        XtDiag_Submit(env, XT_DIAG_INFO, XT_DIAG_SOURCE_MOUSE, "loop probe", -1);
    }
    CHECK(g_bridge.deleteLocalRefCount - locals_before == 3,
          "per-message locals leaked (%d for 3 submissions)",
          g_bridge.deleteLocalRefCount - locals_before);
    CHECK(!g_bridge.pending, "loop submissions left a pending exception");
}

static void test_event_group_reports_first_and_not_sub_second_repeats(JNIEnv *env) {
    resetFakeBridge();
    XtDiagEventGroup group;
    XtDiagEvent_Init(&group);
    CHECK(group.lastReportNanos == -1 && group.suppressedSinceLastReport == 0,
          "fresh group must start empty");

    uint64_t history = 0;
    CHECK(XtDiagEvent_ShouldReport(&group, 1000, &history), "first occurrence must report");
    CHECK(history == 0, "first history must be zero, got %llu", (unsigned long long)history);

    CHECK(!XtDiagEvent_ShouldReport(&group, 1000 + 500, &history), "sub-second repeat suppressed");
    CHECK(!XtDiagEvent_ShouldReport(&group, 1000 + 999999999, &history),
          "just-under-boundary repeat suppressed");
    CHECK(group.suppressedSinceLastReport == 2, "suppressed count mismatch");
    CHECK(history == group.suppressedSinceLastReport, "suppression output lost counted occurrences");
}

static void test_event_group_boundary_inclusive_second_report(JNIEnv *env) {
    XtDiagEventGroup group;
    XtDiagEvent_Init(&group);
    uint64_t history = 7;
    (void)XtDiagEvent_ShouldReport(&group, 0, &history);
    (void)XtDiagEvent_ShouldReport(&group, 1, &history);
    (void)XtDiagEvent_ShouldReport(&group, 2, &history);
    CHECK(XtDiagEvent_ShouldReport(&group, XT_DIAG_EVENT_REPORT_INTERVAL_NANOS, &history),
          "exactly one second since the last report must report");
    CHECK(history == 2, "boundary report must carry the suppressed history, got %llu",
          (unsigned long long)history);
    CHECK(group.suppressedSinceLastReport == 0, "history must reset after a report");
}

static void test_residual_summaries_are_removed_once(JNIEnv *env) {
    XtDiagEventGroup group;
    XtDiagEvent_Init(&group);
    uint64_t history = 0;
    (void)XtDiagEvent_ShouldReport(&group, 0, &history);
    (void)XtDiagEvent_ShouldReport(&group, 1, &history);
    (void)XtDiagEvent_ShouldReport(&group, 2, &history);
    CHECK(XtDiagEvent_TakeResidual(&group) == 2, "residual must carry suppressed occurrences");
    CHECK(XtDiagEvent_TakeResidual(&group) == 0, "residual must be removed exactly once");
}

static void test_submit_event_aggregates_repeated_failures(JNIEnv *env) {
    resetFakeBridge();
    XtDiagEventGroup group;
    XtDiagEvent_Init(&group);
    CHECK(XtDiag_SubmitEvent(env, XT_DIAG_ERROR, XT_DIAG_SOURCE_KEYBOARD, &group,
                             "InputService.sendKeyboardEvent failed; suppressed=") ==
              XT_DIAG_ACCEPTED,
          "first occurrence submits");
    CHECK(g_bridge.staticCallCount == 1, "first submit count");
    CHECK(g_bridge.messageArg == u"InputService.sendKeyboardEvent failed; suppressed=0",
          "first report must state zero suppressed failures");
    g_bridge.messageArg.clear();
    for (int i = 0; i < 20; i++) {
        CHECK(XtDiag_SubmitEvent(env, XT_DIAG_ERROR, XT_DIAG_SOURCE_KEYBOARD, &group,
                                 "InputService.sendKeyboardEvent failed; suppressed=") ==
                  XT_DIAG_AGGREGATED,
              "sub-second repetition %d must aggregate", i);
    }
    CHECK(g_bridge.staticCallCount == 1, "aggregation must not touch the bridge repeatedly");
    CHECK(g_bridge.messageArg.empty(), "aggregated occurrences must not create messages");

    uint64_t residual = XtDiagEvent_TakeResidual(&group);
    CHECK(residual == 20, "worker exit residual mismatch, got %llu",
          (unsigned long long)residual);
    CHECK(XtDiag_Submit(env, XT_DIAG_ERROR, XT_DIAG_SOURCE_KEYBOARD,
                        "InputService.sendKeyboardEvent failures suppressed at worker exit count=", (long)residual) ==
              XT_DIAG_ACCEPTED,
          "exit residual must submit through the bridge");
    CHECK(g_bridge.messageArg == u"InputService.sendKeyboardEvent failures suppressed at worker exit count=20",
          "residual message mismatch");
    CHECK(g_bridge.staticCallCount == 2, "exit residual submit count");
}

int main() {
    JNIEnv *env = make_fake_env();
    test_init_caches_the_facade_once(env);
    test_submit_formats_fixed_text_with_numeric_suffix(env);
    test_submit_passes_through_the_managed_result(env);
    test_submit_decodes_utf8_strictly(env);
    test_submit_rejects_invalid_utf8_without_jni_calls(env);
    test_submit_enforces_the_utf8_byte_bound(env);
    test_submit_rejects_unknown_priority_and_source(env);
    test_pending_exception_freezes_the_bridge(env);
    test_newstring_failure_reports_unavailable_and_cleans_up(env);
    test_managed_call_failure_is_unavailable_and_releases_the_string(env);
    test_submissions_preserve_errno_on_success_and_failure(env);
    test_each_submission_deletes_its_local_references(env);
    test_event_group_reports_first_and_not_sub_second_repeats(env);
    test_event_group_boundary_inclusive_second_report(env);
    test_residual_summaries_are_removed_once(env);
    test_submit_event_aggregates_repeated_failures(env);
    if (g_failed_checks == 0) {
        std::printf("native_diagnostics_host_tests: all checks passed\n");
        return 0;
    }
    std::printf("native_diagnostics_host_tests: %d failed checks\n", g_failed_checks);
    return 1;
}
