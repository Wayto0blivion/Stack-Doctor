package com.qrowsolutions.stackdoctor.ui

import com.intellij.ui.JBColor
import java.awt.Color

/** Theme-aware palette for the graph. Each [JBColor] holds a light and a dark variant. */
object StackDoctorColors {
    val NODE_FILL = JBColor(Color(0xF2F5F9), Color(0x3C3F41))
    val NODE_BORDER = JBColor(Color(0xC2CAD6), Color(0x5A5D5F))
    val SELECTED_BORDER = JBColor(Color(0x3B82F6), Color(0x4E9BF5))

    val WARNING_FILL = JBColor(Color(0xFFF4E0), Color(0x4A4226))
    val ERROR_FILL = JBColor(Color(0xFCE6E6), Color(0x4A2E2E))

    val EDGE = JBColor(Color(0x9AA4B2), Color(0x6E7174))

    val BADGE_NEUTRAL = JBColor(Color(0x5C6470), Color(0x9DA3AB))
    val WARNING_TEXT = JBColor(Color(0xB5731A), Color(0xE0A85A))
    val ERROR_TEXT = JBColor(Color(0xC0392B), Color(0xE57373))
    val OK_TEXT = JBColor(Color(0x2E8B57), Color(0x6FCF97))
}
