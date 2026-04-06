package io.agents.pokeclaw.channel.telegram

/**
 * 标准 Markdown → Telegram MarkdownV2 转换工具。
 *
 * 官方语法对照：
 *   粗体     **text**  →  *text*
 *   斜体     *text*    →  _text_
 *   删除线   ~~text~~  →  ~text~
 *   行内代码 `code`    →  `code`       (内部只转义 ` 和 \)
 *   代码块   ```       →  ```          (内部只转义 ` 和 \)
 *   链接     [t](url)  →  [t](url)     (url 内转义 ) 和 \)
 *   标题     # text    →  *text*       (TG 无标题，用粗体代替)
 *
 * 其余保留字符 _ * [ ] ( ) ~ ` > # + - = | { } . ! 在普通文本中必须 \ 转义
 */
object TelegramMarkdownUtils {

    private val markdownPatterns = listOf(
        Regex("""\*\*.+?\*\*"""),
        Regex("""^#{1,6}\s""", RegexOption.MULTILINE),
        Regex("""```"""),
        Regex("""\[.+?]\(.+?\)"""),
        Regex("""^\|.+\|.+\|""", RegexOption.MULTILINE),
        Regex("""~~.+?~~"""),
        Regex("""^>\s""", RegexOption.MULTILINE),
        Regex("""^- \[[ x]]""", RegexOption.MULTILINE),
    )

    fun containsMarkdown(text: String): Boolean =
        markdownPatterns.any { it.containsMatchIn(text) }

    fun markdownToTelegramV2(text: String): String {
        val sb = StringBuilder()
        val lines = text.lines()
        var inCodeBlock = false

        for ((idx, line) in lines.withIndex()) {
            if (idx > 0) sb.append("\n")

            if (line.trimStart().startsWith("```")) {
                sb.append(line)
                inCodeBlock = !inCodeBlock
                continue
            }
            if (inCodeBlock) {
                sb.append(line.replace("\\", "\\\\").replace("`", "\\`"))
                continue
            }

            val headingMatch = Regex("""^#{1,6}\s+(.+)""").find(line)
            if (headingMatch != null) {
                sb.append("*").append(convertLineToTgV2(headingMatch.groupValues[1])).append("*")
                continue
            }

            sb.append(convertLineToTgV2(line))
        }
        return sb.toString()
    }

    fun escapePlain(text: String): String =
        text.replace("\\", "\\\\")
            .replace(Regex("""([_*\[\]()~`>#+\-=|{}.!])""")) { "\\${it.value}" }

    // ---------- 内部方法 ----------

    private val inlinePattern = Regex(
        """(`[^`]+`)|(\*\*(.+?)\*\*)|(\*([^*]+)\*)|(~~(.+?)~~)|(\[([^\]]+)]\(([^)]+)\))"""
    )

    private fun convertLineToTgV2(line: String): String {
        val result = StringBuilder()
        var lastEnd = 0

        for (m in inlinePattern.findAll(line)) {
            if (m.range.first > lastEnd) {
                result.append(escapePlain(line.substring(lastEnd, m.range.first)))
            }
            when {
                m.groups[1] != null -> {
                    val inner = m.value.removeSurrounding("`")
                    result.append("`").append(inner.replace("\\", "\\\\").replace("`", "\\`")).append("`")
                }
                m.groups[2] != null -> {
                    result.append("*").append(escapePlain(m.groups[3]!!.value)).append("*")
                }
                m.groups[4] != null -> {
                    result.append("_").append(escapePlain(m.groups[5]!!.value)).append("_")
                }
                m.groups[6] != null -> {
                    result.append("~").append(escapePlain(m.groups[7]!!.value)).append("~")
                }
                m.groups[8] != null -> {
                    val linkText = m.groups[9]!!.value
                    val linkUrl = m.groups[10]!!.value
                    result.append("[").append(escapePlain(linkText)).append("](")
                        .append(linkUrl.replace("\\", "\\\\").replace(")", "\\)"))
                        .append(")")
                }
            }
            lastEnd = m.range.last + 1
        }

        if (lastEnd < line.length) {
            result.append(escapePlain(line.substring(lastEnd)))
        }
        return result.toString()
    }
}
