package xtr.keymapper.log

// design-single-file.md §2 step 5: one daily byte budget shared by every writer under the
// coordinator lock. D caps the whole day file; R is a fixed control reserve inside D so the
// notice, session END and recovery frames can still land once data has consumed D − R.
// Records and START/CONTINUED markers never write from the reserve.
// The ledger IS the day file: the validated prefix is re-derived every transaction, so
// accounting stays exact across instances and processes, a restart inherits the day's spent
// budget, and no same-day rollover is possible — normal charges stay under D − R and control
// charges under R.
class DailyBudget(val dailyCapBytes: Long, val reserveBytes: Long) {
    init { require(dailyCapBytes > reserveBytes) { "daily cap ($dailyCapBytes) must exceed the control reserve ($reserveBytes)" } }

    // Observed outcome of one transaction's budget selection; `frames` is the ordered output.
    class Verdict(
        val frames: List<ByteArray>,
        val recordsDropped: Int,
        val noticeWritten: Boolean,
        val endUnpersisted: Boolean,
        val recoveryUnpersisted: Boolean,
        val emergency: Boolean,
    )

    internal enum class Kind { HEADER, RECORD, SESSION_START, SESSION_END, RECOVERY, NOTICE }

    internal class Entry(val kind: Kind, val bytes: ByteArray)

    // Selects the frames that survive, under the caller's coordinator lock, refreshed from
    // the validated file prefix each transaction.
    internal fun charge(persisted: List<Frame>, entries: List<Entry>, legacyBytes: Long = 0): Verdict {
        var normalCharged = legacyBytes
        var controlCharged = 0L
        var noticeOnDisk = false
        for (frame in persisted) {
            // Canonical frames re-encode byte-identically: this is the validated on-disk size.
            val size = FrameCodec.encode(frame.seq, frame.sid, frame.payload).size.toLong()
            when (kindOf(frame)) {
                Kind.NOTICE -> {
                    controlCharged += size
                    noticeOnDisk = true
                }
                Kind.RECOVERY, Kind.SESSION_END -> controlCharged += size
                Kind.HEADER, Kind.RECORD, Kind.SESSION_START -> normalCharged += size
            }
        }
        var normalLeft = (dailyCapBytes - reserveBytes - normalCharged).coerceAtLeast(0)
        var reserveLeft = (reserveBytes - controlCharged).coerceAtLeast(0)
        // Reconstruction buckets by frame kind, not by the allowance a byte was written from,
        // so the seat split can drift from the file's true shape across transactions; the
        // physical roof (the whole day, D) is therefore enforced independently of the seats,
        // guaranteeing file ≤ D no matter how bytes were re-slotted.
        var physicalLeft = (dailyCapBytes - normalCharged - controlCharged).coerceAtLeast(0)
        // A day switches to reserve-writes at the first normal-kind candidate its RUNNING data
        // allowance cannot hold (in-transaction charges count); a notice on disk keeps it sticky.
        var latched = noticeOnDisk
        val out = ArrayList<ByteArray>(entries.size + 1)
        var headerSeen = false
        var noticeWritten = false
        var recordsDropped = 0
        var endUnpersisted = false
        var recoveryUnpersisted = false
        var submittedRecords = 0
        var submittedEnd = false
        fun commitNormal(bytes: ByteArray) {
            normalLeft -= bytes.size
            physicalLeft -= bytes.size
            out.add(bytes)
        }
        fun commitReserve(bytes: ByteArray) {
            reserveLeft -= bytes.size
            physicalLeft -= bytes.size
            out.add(bytes)
        }
        fun switch() {
            latched = true
            if (noticeOnDisk) return
            val notice = FrameCodec.encode(0, NOTICE_SID, NOTICE_PAYLOAD)
            if (notice.size.toLong() <= reserveLeft && notice.size.toLong() <= physicalLeft) {
                // Reserved-first file order: the notice precedes this transaction's selections.
                out.add(if (headerSeen) 1 else 0, notice)
                reserveLeft -= notice.size
                physicalLeft -= notice.size
                noticeWritten = true
            }
        }
        // Reserve-eligible duals queue here: the notice must precede them, and ENDs must exhaust
        // the reserve before a recovery frame may claim any of it (notice → END → recovery).
        val reserveQueue = ArrayList<Entry>()
        for (e in entries) {
            val size = e.bytes.size.toLong()
            when (e.kind) {
                // The day header is protocol infrastructure: it must land, so a config too
                // tight to hold it is rejected loudly.
                Kind.HEADER -> when {
                    size <= normalLeft && size <= physicalLeft -> { commitNormal(e.bytes); headerSeen = true }
                    size <= reserveLeft && size <= physicalLeft -> { commitReserve(e.bytes); headerSeen = true }
                    else -> throw IllegalStateException("daily budget cannot hold the day header")
                }
                // Session END and recovery are normal-first while data room survives (also the
                // latch trigger); reserve allocation happens after the pass, in priority order.
                Kind.SESSION_END, Kind.RECOVERY -> {
                    submittedEnd = submittedEnd || e.kind == Kind.SESSION_END
                    if (!latched && size <= normalLeft && size <= physicalLeft) commitNormal(e.bytes)
                    else {
                        if (!latched) switch()
                        reserveQueue += e
                    }
                }
                else -> {
                    if (e.kind == Kind.RECORD) submittedRecords++
                    if (!latched && size <= normalLeft && size <= physicalLeft) commitNormal(e.bytes)
                    else {
                        if (!latched) switch()
                        if (e.kind == Kind.RECORD) recordsDropped++
                    }
                }
            }
        }
        for (e in reserveQueue.filter { it.kind == Kind.SESSION_END } + reserveQueue.filter { it.kind == Kind.RECOVERY }) {
            val size = e.bytes.size.toLong()
            if (size <= reserveLeft && size <= physicalLeft) commitReserve(e.bytes)
            else if (e.kind == Kind.RECOVERY) recoveryUnpersisted = true else endUnpersisted = true
        }
        // Recovery refusal aborts the transaction: nothing committed. The verdict separates
        // the selection plan from the outcome — submitted records and ENDs are unpersisted.
        return if (recoveryUnpersisted) Verdict(emptyList(), submittedRecords, false, submittedEnd, true, latched)
        else Verdict(out, recordsDropped, noticeWritten, endUnpersisted, false, latched)
    }

    companion object {
        internal const val NOTICE_SID = "notice"
        internal const val NOTICE_PAYLOAD = "RESERVE_EXHAUSTED"
        const val RESERVE_BYTES: Long = 4L * FrameCodec.MAX_FRAME_BYTES
        const val DEFAULT_DAILY_CAP_BYTES: Long = 4L * 1024 * 1024

        internal fun kindOf(frame: Frame): Kind = when {
            FrameCodec.isDayHeader(frame) -> Kind.HEADER
            frame.sid == "recovery" -> Kind.RECOVERY
            frame.sid == NOTICE_SID -> Kind.NOTICE
            frame.payload.lineSequence().any { SessionMarkers.markerTypeOf(it) == SessionMarkers.END } -> Kind.SESSION_END
            frame.payload.lineSequence().any { SessionMarkers.markerTypeOf(it) != null || it == SessionMarkers.LATE } -> Kind.SESSION_START
            else -> Kind.RECORD
        }
    }
}
