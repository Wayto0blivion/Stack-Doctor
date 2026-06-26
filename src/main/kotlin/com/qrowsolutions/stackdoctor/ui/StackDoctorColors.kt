package com.qrowsolutions.stackdoctor.ui

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Theme-aware palette for the graph. Every colour is resolved from the user's *active* IDE theme
 * rather than a fixed set of literals, and re-resolved on each use so a live theme switch is picked
 * up immediately:
 *
 *  * UI chrome (borders, edges, selection, badges) comes from the theme's named LaF keys.
 *  * Node body/status tints are derived from the themed panel background and the themed status hues.
 *  * Category accents are lifted from the editor's syntax-highlighting scheme, so they match the
 *    colours the user already sees in their code and stay distinct under any theme.
 *
 * The hard-coded literals are only fallbacks for the rare theme that omits a key.
 */
object StackDoctorColors {

    /** The theme accent (focus-ring) colour — drives selection, links and highlighted edges. */
    val ACCENT = JBColor.namedColor("Component.focusColor", JBColor(Color(0x3B82F6), Color(0x4E9BF5)))

    // Node body. A subtle top→bottom gradient lifts a soft sheen off the themed background.
    val NODE_FILL_TOP = JBColor { ColorUtil.brighter(nodeBackground(), 2) }
    val NODE_FILL_BOTTOM = JBColor { ColorUtil.darker(nodeBackground(), 1) }
    val NODE_BORDER = JBColor.namedColor("Component.borderColor", JBColor(Color(0xC2CAD6), Color(0x5A5D5F)))
    val SELECTED_BORDER = ACCENT
    val SELECTED_GLOW = ACCENT

    // Status tints: the themed status hue mixed lightly into the node background, so it reads as a
    // tint of the current theme rather than a fixed block of yellow/red.
    val WARNING_FILL_TOP = JBColor { tint(WARNING_TEXT, 0.08) }
    val WARNING_FILL_BOTTOM = JBColor { tint(WARNING_TEXT, 0.18) }
    val ERROR_FILL_TOP = JBColor { tint(ERROR_TEXT, 0.08) }
    val ERROR_FILL_BOTTOM = JBColor { tint(ERROR_TEXT, 0.18) }

    /** Translucent drop-shadow under each node (theme-independent by design). */
    val SHADOW = JBColor(Color(0, 0, 0, 28), Color(0, 0, 0, 90))

    val EDGE = JBColor.namedColor("Separator.foreground", JBColor(Color(0x9AA4B2), Color(0x6E7174)))
    val EDGE_HIGHLIGHT = ACCENT

    val BADGE_NEUTRAL = JBColor.namedColor("Component.infoForeground", JBColor(Color(0x5C6470), Color(0x9DA3AB)))
    val WARNING_TEXT = JBColor.namedColor("Component.warningFocusColor", JBColor(Color(0xB5731A), Color(0xE0A85A)))
    val ERROR_TEXT = JBColor.namedColor("Label.errorForeground", JBColor(Color(0xC0392B), Color(0xE57373)))
    val OK_TEXT = JBColor.namedColor("Label.successForeground", JBColor(Color(0x2E8B57), Color(0x6FCF97)))

    // Accent for compose-file (root) nodes in the map — the theme's own accent colour.
    val ACCENT_FILE = ACCENT

    // Category accent bar painted down the left edge of each node. Each is pulled from a distinct
    // syntax-highlight token in the active editor scheme so the categories stay visually separated
    // while matching the user's theme.
    val ACCENT_DATABASE = JBColor { syntax(DefaultLanguageHighlighterColors.KEYWORD, JBColor(Color(0x8E5BD0), Color(0xB084E6))) }
    val ACCENT_CACHE = JBColor { syntax(DefaultLanguageHighlighterColors.NUMBER, JBColor(Color(0xD08A3E), Color(0xE3A862))) }
    val ACCENT_PROXY = JBColor { syntax(DefaultLanguageHighlighterColors.FUNCTION_DECLARATION, JBColor(Color(0x2B8A8A), Color(0x4FB6B6))) }
    val ACCENT_QUEUE = JBColor { syntax(DefaultLanguageHighlighterColors.STRING, JBColor(Color(0xC65B8A), Color(0xE085AC))) }
    val ACCENT_WEB = JBColor { syntax(DefaultLanguageHighlighterColors.INSTANCE_FIELD, JBColor(Color(0x3B82F6), Color(0x5B9BF7))) }
    val ACCENT_GENERIC = JBColor.namedColor("Component.infoForeground", JBColor(Color(0x8A93A0), Color(0x7C8896)))

    /** The panel/card background the nodes sit on, per the active theme. */
    private fun nodeBackground(): Color =
        JBColor.namedColor("Panel.background", JBColor(Color(0xF7F9FC), Color(0x3C3F41)))

    /** Mixes [amount] of a status hue into the node background to produce a gentle tint. */
    private fun tint(hue: Color, amount: Double): Color = ColorUtil.mix(nodeBackground(), hue, amount)

    /** Foreground colour of a syntax token in the active editor scheme, falling back when unset. */
    private fun syntax(key: TextAttributesKey, fallback: JBColor): Color {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return scheme.getAttributes(key)?.foregroundColor
            ?: key.defaultAttributes?.foregroundColor
            ?: fallback
    }
}
