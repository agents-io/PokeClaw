// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.telegram

/**
 * Converts standard Markdown to Telegram MarkdownV2.
 *
 * Official syntax mapping:
 *   Bold      **text**  →  *text*
 *   Italic    *text*    →  _text_
 *   Strikethrough ~~text~~ →  ~text~
 *   Inline code `code` →  `code`       (only ` and \ escaped inside)
 *   Code block ```      →  ```          (only ` and \ escaped inside)
 *   Link      [t](url)  →  [t](url)     () and \ escaped inside url)
 *   Heading   # text    →  *text*       (TG has no headings, use bold instead)
 *
 * All other reserved characters _ * [ ] ( ) ~ ` > # + - = | { } . ! must be \ escaped in plain text
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

    // ---------- Internal methods ----------

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
