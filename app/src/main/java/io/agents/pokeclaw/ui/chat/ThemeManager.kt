// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.graphics.Color
import io.agents.pokeclaw.utils.KVUtils

/**
 * Runtime theme color provider.
 * Reads saved theme ID from KVUtils and returns the appropriate colors.
 */
object ThemeManager {

    data class ChatColors(
        val bg: Int,
        val toolbarBg: Int,
        val userBubble: Int,
        val userText: Int,
        val aiBubble: Int,
        val aiBubbleBorder: Int,
        val aiText: Int,
        val avatarBg: Int,
        val inputBorder: Int,
        val sendColor: Int,
        val toolOk: Int,
        val toolDefault: Int,
        val divider: Int
    )

    private val themes = mapOf(
        "ember_dark" to ChatColors(
            bg = Color.parseColor("#151211"), toolbarBg = Color.parseColor("#1F1A18"),
            userBubble = Color.parseColor("#D45A30"), userText = Color.parseColor("#FEF2ED"),
            aiBubble = Color.parseColor("#342C28"), aiBubbleBorder = Color.parseColor("#3D332C"),
            aiText = Color.parseColor("#E0D5CC"), avatarBg = Color.parseColor("#C0542E"),
            inputBorder = Color.parseColor("#332B27"), sendColor = Color.parseColor("#E8845A"),
            toolOk = Color.parseColor("#E8845A"), toolDefault = Color.parseColor("#7A6E64"),
            divider = Color.parseColor("#2A2220")
        ),
        "abyss_dark" to ChatColors(
            bg = Color.parseColor("#0C111B"), toolbarBg = Color.parseColor("#151D2E"),
            userBubble = Color.parseColor("#2563EB"), userText = Color.parseColor("#F0F5FF"),
            aiBubble = Color.parseColor("#1E2D45"), aiBubbleBorder = Color.parseColor("#2A3D5A"),
            aiText = Color.parseColor("#D0DAE8"), avatarBg = Color.parseColor("#1D4ED8"),
            inputBorder = Color.parseColor("#1E293B"), sendColor = Color.parseColor("#60A5FA"),
            toolOk = Color.parseColor("#38BDF8"), toolDefault = Color.parseColor("#475569"),
            divider = Color.parseColor("#1A2234")
        ),
        "moss_dark" to ChatColors(
            bg = Color.parseColor("#0F1410"), toolbarBg = Color.parseColor("#161E16"),
            userBubble = Color.parseColor("#2D7A4F"), userText = Color.parseColor("#EEF7F0"),
            aiBubble = Color.parseColor("#243524"), aiBubbleBorder = Color.parseColor("#334833"),
            aiText = Color.parseColor("#D0E0D0"), avatarBg = Color.parseColor("#2D7A4F"),
            inputBorder = Color.parseColor("#233023"), sendColor = Color.parseColor("#6EE7A0"),
            toolOk = Color.parseColor("#4ADE80"), toolDefault = Color.parseColor("#4A6350"),
            divider = Color.parseColor("#1C261C")
        ),
        "onyx_dark" to ChatColors(
            bg = Color.parseColor("#111111"), toolbarBg = Color.parseColor("#1A1A1A"),
            userBubble = Color.parseColor("#444444"), userText = Color.parseColor("#F0F0F0"),
            aiBubble = Color.parseColor("#2C2C2C"), aiBubbleBorder = Color.parseColor("#404040"),
            aiText = Color.parseColor("#DDDDDD"), avatarBg = Color.parseColor("#444444"),
            inputBorder = Color.parseColor("#2A2A2A"), sendColor = Color.parseColor("#999999"),
            toolOk = Color.parseColor("#A3A3A3"), toolDefault = Color.parseColor("#555555"),
            divider = Color.parseColor("#222222")
        ),
        "ember_light" to ChatColors(
            bg = Color.parseColor("#F5EDE5"), toolbarBg = Color.parseColor("#EDE3DA"),
            userBubble = Color.parseColor("#C0542E"), userText = Color.parseColor("#FFFFFF"),
            aiBubble = Color.parseColor("#EAE0D4"), aiBubbleBorder = Color.parseColor("#D8CBBC"),
            aiText = Color.parseColor("#4A3828"), avatarBg = Color.parseColor("#C0542E"),
            inputBorder = Color.parseColor("#C8BAB0"), sendColor = Color.parseColor("#C0542E"),
            toolOk = Color.parseColor("#C0542E"), toolDefault = Color.parseColor("#C4B5A8"),
            divider = Color.parseColor("#DDD2C6")
        ),
        "abyss_light" to ChatColors(
            bg = Color.parseColor("#E8EDF4"), toolbarBg = Color.parseColor("#DDE4EE"),
            userBubble = Color.parseColor("#2563EB"), userText = Color.parseColor("#FFFFFF"),
            aiBubble = Color.parseColor("#D5DFEE"), aiBubbleBorder = Color.parseColor("#B0C0D8"),
            aiText = Color.parseColor("#334155"), avatarBg = Color.parseColor("#2563EB"),
            inputBorder = Color.parseColor("#C8D4E4"), sendColor = Color.parseColor("#2563EB"),
            toolOk = Color.parseColor("#2563EB"), toolDefault = Color.parseColor("#94A3B8"),
            divider = Color.parseColor("#C8D4E4")
        ),
        "moss_light" to ChatColors(
            bg = Color.parseColor("#E4EFE4"), toolbarBg = Color.parseColor("#D8E8D8"),
            userBubble = Color.parseColor("#2D7A4F"), userText = Color.parseColor("#FFFFFF"),
            aiBubble = Color.parseColor("#D0E8D0"), aiBubbleBorder = Color.parseColor("#A8C8A8"),
            aiText = Color.parseColor("#2A4A2A"), avatarBg = Color.parseColor("#2D7A4F"),
            inputBorder = Color.parseColor("#B8D0B8"), sendColor = Color.parseColor("#2D7A4F"),
            toolOk = Color.parseColor("#2D7A4F"), toolDefault = Color.parseColor("#8CB898"),
            divider = Color.parseColor("#C0D8C0")
        ),
        "onyx_light" to ChatColors(
            bg = Color.parseColor("#E8E8E8"), toolbarBg = Color.parseColor("#DEDEDE"),
            userBubble = Color.parseColor("#444444"), userText = Color.parseColor("#F5F5F5"),
            aiBubble = Color.parseColor("#D8D8D8"), aiBubbleBorder = Color.parseColor("#BBBBBB"),
            aiText = Color.parseColor("#2A2A2A"), avatarBg = Color.parseColor("#444444"),
            inputBorder = Color.parseColor("#CCCCCC"), sendColor = Color.parseColor("#555555"),
            toolOk = Color.parseColor("#666666"), toolDefault = Color.parseColor("#BBBBBB"),
            divider = Color.parseColor("#CCCCCC")
        )
    )

    fun getColors(): ChatColors {
        val id = KVUtils.getString("THEME_ID", "ember_dark")
        return themes[id] ?: themes["ember_dark"]!!
    }

    fun ChatColors.toComposeColors(): PokeclawColors {
        val dark = isDark()
        return PokeclawColors(
            background = androidx.compose.ui.graphics.Color(bg),
            surface = androidx.compose.ui.graphics.Color(toolbarBg),
            userBubble = androidx.compose.ui.graphics.Color(userBubble),
            userText = androidx.compose.ui.graphics.Color(userText),
            aiBubble = androidx.compose.ui.graphics.Color(aiBubble),
            aiBubbleBorder = androidx.compose.ui.graphics.Color(aiBubbleBorder),
            aiText = androidx.compose.ui.graphics.Color(aiText),
            avatar = androidx.compose.ui.graphics.Color(avatarBg),
            accent = androidx.compose.ui.graphics.Color(sendColor),
            // Warm grays to match ember palette (never cool blue-purple)
            textPrimary = if (dark) androidx.compose.ui.graphics.Color(0xFFF0EAE4.toInt())
                          else androidx.compose.ui.graphics.Color(0xFF2C2218.toInt()),
            textSecondary = if (dark) androidx.compose.ui.graphics.Color(0xFFB0A89E.toInt())
                            else androidx.compose.ui.graphics.Color(0xFF6B5D52.toInt()),
            textTertiary = if (dark) androidx.compose.ui.graphics.Color(0xFF7A6E64.toInt())
                           else androidx.compose.ui.graphics.Color(0xFFA09488.toInt()),
            divider = androidx.compose.ui.graphics.Color(divider),
            inputBorder = androidx.compose.ui.graphics.Color(inputBorder),
        )
    }

    fun isDark(): Boolean {
        val id = KVUtils.getString("THEME_ID", "ember_dark")
        return id.endsWith("_dark")
    }
}
