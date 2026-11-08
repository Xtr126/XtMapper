#include "xt_diag_events.h"

void XtDiagEvent_Init(XtDiagEventGroup *group) {
    group->suppressedSinceLastReport = 0;
    group->lastReportNanos = -1;
}

// First occurrence reports immediately; afterwards at most one report per interval, with suppressed occurrences summarized at that boundary or at worker exit.
bool XtDiagEvent_ShouldReport(XtDiagEventGroup *group, int64_t nowNanos,
                              uint64_t *suppressedHistory) {
    if (group->lastReportNanos < 0 ||
        nowNanos - group->lastReportNanos >= XT_DIAG_EVENT_REPORT_INTERVAL_NANOS) {
        *suppressedHistory = group->suppressedSinceLastReport;
        group->suppressedSinceLastReport = 0;
        group->lastReportNanos = nowNanos;
        return true;
    }
    group->suppressedSinceLastReport += 1;
    *suppressedHistory = group->suppressedSinceLastReport;
    return false;
}

uint64_t XtDiagEvent_TakeResidual(XtDiagEventGroup *group) {
    uint64_t residual = group->suppressedSinceLastReport;
    group->suppressedSinceLastReport = 0;
    return residual;
}
