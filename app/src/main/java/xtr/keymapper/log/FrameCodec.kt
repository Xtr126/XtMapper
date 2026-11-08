package xtr.keymapper.log

import java.util.zip.CRC32

data class Frame(val seq: Long, val sid: String, val payload: String)

class FrameRecovery(val frames: List<Frame>, val discardedBytes: Long)

object DayProtocol {
    // The day header is an ordinary frame: seq 0, sid "protocol", payload "P1".
    // No extra grammar — every transaction re-loads and re-validates the first line.
    fun header(): String = FrameCodec.encode(0, "protocol", "P1").decodeToString()
}

object FrameCodec {
    private val SID = Regex("[0-9A-Za-z-]{1,40}")
    private val HEX = Regex("[0-9a-f]{8}")

    // Protocol limits: 32 KiB hard maximum for the ENCODED frame (envelope + escaping +
    // terminator included); the payload budget is chosen so maximal escaping stays inside.
    const val MAX_PAYLOAD_BYTES = 15 * 1024
    const val MAX_FRAME_BYTES = 32 * 1024

    fun isDayHeader(frame: Frame) = frame.seq == 0L && frame.sid == "protocol" && frame.payload == "P1"

    fun encode(seq: Long, sid: String, payload: String): ByteArray {
        require(seq >= 0) { "sequence numbers are non-negative" }
        require(SID.matches(sid)) { "session id violates the envelope grammar" }
        val raw = payload.toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_PAYLOAD_BYTES) { "payload exceeds the protocol limit" }
        val line = "X1 $seq $sid ${raw.size} ${crc(sid, seq, raw.size.toLong(), raw)} ${escape(payload)}\n"
        require(line.encodeToByteArray().size <= MAX_FRAME_BYTES) { "encoded frame exceeds the protocol limit" }
        return line.toByteArray(Charsets.UTF_8)
    }

    fun decode(line: String): Frame? {
        if (line.length > MAX_FRAME_BYTES) return null
        val fields = line.split(' ', limit = 6)
        if (fields.size != 6 || fields[0] != "X1") return null
        val seq = fields[1].toLongOrNull() ?: return null
        if (seq < 0) return null
        if (!SID.matches(fields[2])) return null
        val len = fields[3].toLongOrNull() ?: return null
        if (len < 0 || len > MAX_PAYLOAD_BYTES) return null
        if (!HEX.matches(fields[4])) return null
        val payload = unescape(fields[5]) ?: return null
        val raw = payload.toByteArray(Charsets.UTF_8)
        if (raw.size.toLong() != len || crc(fields[2], seq, len, raw) != fields[4]) return null
        return Frame(seq, fields[2], payload)
    }

    // After the first unparseable region everything is uninterpreted: valid frames are
    // recovered whole, plainlined bytes are counted for the recovery record. The FINAL
    // region must carry its terminator — a delimiter-less tail is reported as discarded
    // (matching tail repair), never decoded as a frame.
    fun scanRecovery(raw: ByteArray): FrameRecovery {
        val frames = ArrayList<Frame>()
        var good = 0
        var pos = 0
        while (pos < raw.size) {
            var end = pos
            while (end < raw.size && raw[end] != '\n'.code.toByte()) end++
            if (end == raw.size) break
            val frame = decode(String(raw, pos, end - pos, Charsets.UTF_8)) ?: break
            frames += frame
            good = end + 1
            pos = end + 1
        }
        return FrameRecovery(frames, (raw.size - good).toLong())
    }

    // "P1|<sid>|<seq>|<len>|" + raw payload bytes: '|' cannot occur in the restricted sid
    // alphabet or in the numeric fields, so the pre-image is unambiguous and canonical.
    private fun crc(sid: String, seq: Long, len: Long, raw: ByteArray) =
        "%08x".format(CRC32().apply {
            update("P1|$sid|$seq|$len|".toByteArray(Charsets.US_ASCII))
            update(raw)
        }.getValue())
    private fun escape(p: String) = p.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(s: String): String? {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                when (s.getOrNull(i + 1)) {
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> return null
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
