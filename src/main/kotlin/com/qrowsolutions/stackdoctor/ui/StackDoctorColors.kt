package com.qrowsolutions.stackdoctor.ui

import com.intellij.ui.JBColor
import java.awt.Color

/** Theme-aware palette for the graph. Each [JBColor] holds a light and a dark variant. */
object StackDoctorColors {
    // Node body. A subtle top→bottom gradient (FILL_TOP → FILL_BOTTOM) gives nodes a soft sheen.
    val NODE_FILL_TOP = JBColor(Color(0xFFFFFF), Color(0x4B4F52))
    val NODE_FILL_BOTTOM = JBColor(Color(0xEDF1F6), Color(0x3C3F41))
    val NODE_BORDER = JBColor(Color(0xC2CAD6), Color(0x5A5D5F))
    val SELECTED_BORDER = JBColor(Color(0x3B82F6), Color(0x4E9BF5))
    val SELECTED_GLOW = JBColor(Color(0x3B82F6), Color(0x4E9BF5))

    val WARNING_FILL_TOP = JBColor(Color(0xFFFBF0), Color(0x53492A))
    val WARNING_FILL_BOTTOM = JBColor(Color(0xFFF1D6), Color(0x44391F))
    val ERROR_FILL_TOP = JBColor(Color(0xFFF1F1), Color(0x53302F))
    val ERROR_FILL_BOTTOM = JBColor(Color(0xFBDFDF), Color(0x432626))

    /** Translucent drop-shadow under each node. */
    val SHADOW = JBColor(Color(0, 0, 0, 28), Color(0, 0, 0, 90))

    val EDGE = JBColor(Color(0x9AA4B2), Color(0x6E7174))
    val EDGE_HIGHLIGHT = JBColor(Color(0x3B82F6), Color(0x4E9BF5))

    val BADGE_NEUTRAL = JBColor(Color(0x5C6470), Color(0x9DA3AB))
    val WARNING_TEXT = JBColor(Color(0xB5731A), Color(0xE0A85A))
    val ERROR_TEXT = JBColor(Color(0xC0392B), Color(0xE57373))
    val OK_TEXT = JBColor(Color(0x2E8B57), Color(0x6FCF97))

    // Accent for compose-file (root) nodes in the map.
    val ACCENT_FILE = JBColor(Color(0x4C6EF5), Color(0x6B8AFD))

    // Category accent bar painted down the left edge of each node.
    val ACCENT_DATABASE = JBColor(Color(0x8E5BD0), Color(0xB084E6))
    val ACCENT_CACHE = JBColor(Color(0xD08A3E), Color(0xE3A862))
    val ACCENT_PROXY = JBColor(Color(0x2B8A8A), Color(0x4FB6B6))
    val ACCENT_QUEUE = JBColor(Color(0xC65B8A), Color(0xE085AC))
    val ACCENT_WEB = JBColor(Color(0x3B82F6), Color(0x5B9BF7))
    val ACCENT_GENERIC = JBColor(Color(0x8A93A0), Color(0x7C8896))
}
