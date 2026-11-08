#ifndef XT_DIAG_EVENTS_H
#define XT_DIAG_EVENTS_H

#include <stdbool.h>
#include <stdint.h>

#define XT_DIAG_EVENT_REPORT_INTERVAL_NANOS 1000000000LL
#define XT_DIAG_API __attribute__((visibility("hidden")))

// Pure producer-side grouping state for one repeated failure condition so the owning thread has exclusive access, no locking.
typedef struct XtDiagEventGroup {
    uint64_t suppressedSinceLastReport;
    int64_t lastReportNanos;
} XtDiagEventGroup;

XT_DIAG_API void XtDiagEvent_Init(XtDiagEventGroup *group);

// Records one occurrence; returns true when the caller should submit now, with suppressedHistory holding occurrences skipped since the previous report.
XT_DIAG_API bool XtDiagEvent_ShouldReport(XtDiagEventGroup *group, int64_t nowNanos,
                                         uint64_t *suppressedHistory);

// Returns and clears occurrences suppressed since the last report.
XT_DIAG_API uint64_t XtDiagEvent_TakeResidual(XtDiagEventGroup *group);

#endif
