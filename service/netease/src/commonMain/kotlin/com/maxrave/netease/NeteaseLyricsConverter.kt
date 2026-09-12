/*
 * 网易歌词格式处理 —— NeriPlayer SyncedLyricsView 里 yrc/lrc 解析的 core 版(降层),
 * 只保留格式知识,不绑任何 UI 类型。输出中立的 [NeteaseLyricLine],渲染层自行映射。
 *
 * yrc 行示例: [12580,3470](12580,250,0)难(12830,300,0)以
 *   头部 [start,duration],段落 (start,duration,flag)文字
 */
package com.maxrave.netease

import com.maxrave.netease.model.NeteaseLyricLine

object NeteaseLyricsConverter {
    private val YRC_LINE_REGEX = Regex("""\[\d{1,19},\s*\d{1,19}]\(\d{1,19},""")
    private val YRC_HEADER_REGEX = Regex("""\[(\d{1,19}),\s*(\d{1,19})]""")
    private val YRC_SEGMENT_REGEX = Regex("""\((\d{1,19}),\s*(\d{1,19}),\s*[-\d]{1,20}\)([^()\n\r]*)""")
    private val LRC_TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun isYrc(content: String): Boolean = content.contains(YRC_LINE_REGEX)

    /**
     * 自动识别并解析:yrc → 逐字行;否则按 LRC 逐句解析。
     * 解析失败返回空列表(歌词缺失/格式异常走 UI 兜底)。
     */
    fun parseAuto(content: String): List<NeteaseLyricLine> =
        when {
            isYrc(content) -> runCatching { parseYrc(content) }.getOrDefault(emptyList())
            else -> parseLrc(content)
        }

    /** yrc(逐字)解析,按开始时间排序 */
    fun parseYrc(yrc: String): List<NeteaseLyricLine> {
        val out = mutableListOf<NeteaseLyricLine>()
        yrc.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || !line.startsWith("[")) return@forEach

            val header = YRC_HEADER_REGEX.find(line) ?: return@forEach
            val start = header.groupValues[1].toLongOrNull() ?: return@forEach
            val duration = header.groupValues[2].toLongOrNull() ?: return@forEach

            val segments = YRC_SEGMENT_REGEX.findAll(line).toList()
            if (segments.isEmpty()) {
                out +=
                    NeteaseLyricLine(
                        text = line.substringAfter(']').trim(),
                        startMs = start,
                        endMs = start.saturatingAdd(duration),
                    )
            } else {
                val words = mutableListOf<NeteaseLyricLine.Word>()
                val text = StringBuilder()
                for (m in segments) {
                    val wordStart = m.groupValues[1].toLongOrNull() ?: continue
                    val wordDuration = m.groupValues[2].toLongOrNull() ?: continue
                    val wordText = m.groupValues[3]
                    text.append(wordText)
                    words +=
                        NeteaseLyricLine.Word(
                            startMs = wordStart,
                            endMs = wordStart.saturatingAdd(wordDuration),
                            charCount = wordText.length,
                        )
                }
                out +=
                    NeteaseLyricLine(
                        text = text.toString(),
                        startMs = start,
                        endMs = start.saturatingAdd(duration),
                        words = words,
                    )
            }
        }
        return out.sortedBy { it.startMs }
    }

    /** LRC(逐句)解析,支持 [mm:ss] / [mm:ss.SSS] 与多时间戳行 */
    fun parseLrc(lrc: String): List<NeteaseLyricLine> {
        val timeline = mutableListOf<NeteaseLyricLine>()
        lrc.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("{") || line.startsWith("}")) return@forEach

            val stamps = LRC_TIMESTAMP_REGEX.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach
            val text = line.substringAfterLast(']').trim()
            stamps.forEach { m ->
                val minutes = m.groupValues[1].toLongOrNull() ?: return@forEach
                val seconds = m.groupValues[2].toLongOrNull() ?: return@forEach
                val fractionDigits = m.groupValues[3]
                // [mm:ss.X] 1位=百毫秒 [mm:ss.XX] 2位=十毫秒 [mm:ss.XXX] 3位=毫秒
                val millis =
                    when (fractionDigits.length) {
                        0 -> 0L
                        1 -> fractionDigits.toLongOrNull()?.times(100)
                        2 -> fractionDigits.toLongOrNull()?.times(10)
                        else -> fractionDigits.take(3).toLongOrNull()
                    } ?: 0L
                timeline +=
                    NeteaseLyricLine(
                        text = text,
                        startMs = minutes * 60_000 + seconds * 1_000 + millis,
                        endMs = Long.MAX_VALUE, // 逐句无固有时长,渲染层用下一行起点
                    )
            }
        }
        return timeline.sortedBy { it.startMs }
    }

    /** yrc 降级成标准 LRC 文本(逐字信息丢弃),给只认 LRC 的消费方用 */
    fun yrcToLrc(yrc: String): String =
        parseYrc(yrc).joinToString("\n") { line ->
            "[${line.startMs.toLrcStamp()}]${line.text}"
        }

    private fun Long.toLrcStamp(): String {
        val minutes = this / 60_000
        val seconds = (this % 60_000) / 1_000
        val millis = this % 1_000
        // common 无 String.format,手动补零:mm:ss.SSS
        return buildString {
            appendPad(minutes, 2)
            append(':')
            appendPad(seconds, 2)
            append('.')
            appendPad(millis, 3)
        }
    }

    private fun StringBuilder.appendPad(
        value: Long,
        width: Int,
    ) {
        val text = value.toString()
        repeat(width - text.length) { append('0') }
        append(text)
    }

    private fun Long.saturatingAdd(other: Long): Long =
        if (other > 0L && this > Long.MAX_VALUE - other) {
            Long.MAX_VALUE
        } else {
            this + other
        }
}
