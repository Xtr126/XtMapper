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

    fun isDayHeader(frame: Frame) = frame.seq == 0L && frame.sid == "protocol" && frame.payload == "P1"

    fun encode(seq: Long, sid: String, payload: String): ByteArray {
        val raw = payload.toByteArray(Charsets.UTF_8)
        // len/crc cover the raw payload bytes BEFORE escaping: counts stay stable if the escape table ever evolves.
        return "X1 $seq $sid ${raw.size} ${crc(raw)} ${escape(payload)}\n".toByteArray(Charsets.UTF_8)
    }

    fun decode(line: String): Frame? {
        val fields = line.split(' ', limit = 6)
        if (fields.size != 6 || fields[0] != "X1") return null
        val seq = fields[1].toLongOrNull() ?: return null
        if (!SID.matches(fields[2])) return null
        val len = fields[3].toLongOrNull() ?: return null
        if (!HEX.matches(fields[4])) return null
        val payload = unescape(fields[5]) ?: return null
        val raw = payload.toByteArray(Charsets.UTF_8)
        if (raw.size.toLong() != len || crc(raw) != fields[4]) return null
        return Frame(seq, fields[2], payload)
    }

    // After the first unparseable region everything is uninterpreted: valid frames are
    // recovered whole, plainlined bytes are counted for the recovery record.
    fun scanRecovery(raw: ByteArray): FrameRecovery {
        val frames = ArrayList<Frame>()
        var good = 0
        var pos = 0
        while (pos < raw.size) {
            var end = pos
            while (end < raw.size && raw[end] != '\n'.code.toByte()) end++
            val lineEnd = if (end < raw.size) end + 1 else raw.size
            val frame = decode(String(raw, pos, end - pos, Charsets.UTF_8)) ?: break
            frames += frame
            good = lineEnd
            pos = lineEnd
        }
        return FrameRecovery(frames, (raw.size - good).toLong())
    }

    private fun crc(raw: ByteArray) = "%08x".format(CRC32().apply { update(raw) }.getValue())
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
