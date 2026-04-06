// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.wechat

/**
 * Markdown to plain text conversion.
 * Strictly mirrors the official @tencent-weixin/openclaw-weixin@1.0.2 src/messaging/send.ts markdownToPlainText()
 */
object WeChatMarkdown {

    /**
     * Convert Markdown-formatted text to plain text, preserving line breaks and removing Markdown syntax.
     */
    fun markdownToPlainText(text: String): String {
        var result = text

        // Code blocks: strip fences, keep code content
        result = Regex("```[^\\n]*\\n?([\\s\\S]*?)```").replace(result) { m ->
            m.groupValues[1].trim()
        }

        // Images: remove entirely
        result = Regex("!\\[[^\\]]*]\\([^)]*\\)").replace(result, "")

        // Links: keep display text only
        result = Regex("\\[([^\\]]+)]\\([^)]*\\)").replace(result) { m ->
            m.groupValues[1]
        }

        // Tables: remove separator rows
        result = Regex("^\\|[\\s:|-]+\\|$", RegexOption.MULTILINE).replace(result, "")

        // Tables: strip leading/trailing pipes, convert inner pipes to spaces
        result = Regex("^\\|(.+)\\|$", RegexOption.MULTILINE).replace(result) { m ->
            m.groupValues[1].split("|").joinToString("  ") { it.trim() }
        }

        // Strip remaining inline markdown (corresponds to SDK stripMarkdown)
        result = stripMarkdown(result)

        return result
    }

    /**
     * Strip inline Markdown syntax markers.
     */
    private fun stripMarkdown(text: String): String {
        var result = text

        // Bold: **text** or __text__
        result = Regex("\\*\\*(.+?)\\*\\*").replace(result) { it.groupValues[1] }
        result = Regex("__(.+?)__").replace(result) { it.groupValues[1] }

        // Italic: *text* or _text_
        result = Regex("\\*(.+?)\\*").replace(result) { it.groupValues[1] }
        result = Regex("(?<=\\s|^)_(.+?)_(?=\\s|$)").replace(result) { it.groupValues[1] }

        // Strikethrough: ~~text~~
        result = Regex("~~(.+?)~~").replace(result) { it.groupValues[1] }

        // Inline code: `text`
        result = Regex("`(.+?)`").replace(result) { it.groupValues[1] }

        // Headings: # text
        result = Regex("^#{1,6}\\s+", RegexOption.MULTILINE).replace(result, "")

        // Blockquotes: > text
        result = Regex("^>\\s?", RegexOption.MULTILINE).replace(result, "")

        // Horizontal rules
        result = Regex("^[-*_]{3,}$", RegexOption.MULTILINE).replace(result, "")

        // Unordered lists: - item, * item
        result = Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE).replace(result, "")

        // Ordered lists: 1. item
        result = Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE).replace(result, "")

        return result
    }
}
