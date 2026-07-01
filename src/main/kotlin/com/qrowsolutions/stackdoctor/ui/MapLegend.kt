package com.qrowsolutions.stackdoctor.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

/** Builds the HTML shown in the map's "Legend" popup, explaining every symbol, line and tint. */
object MapLegend {

    fun html(): String {
        val fg = hex(UIUtil.getLabelForeground())
        val muted = hex(UIUtil.getContextHelpForeground())
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:sans-serif;color:$fg;margin:2px;'>")

        section(sb, "Nodes", fg)
        row(sb, swatch(StackDoctorColors.ACCENT_FILE, "❐"), "Compose file", "A root node — its services branch off to the right.", muted)
        for (cat in ServiceCategory.entries) {
            row(sb, swatch(cat.accent, cat.glyph), "${title(cat)} service", cat.description, muted)
        }
        sb.append("<div style='color:$muted;font-size:88%;margin:3px 0 0 16px;font-style:italic;'>")
        sb.append("Categories are a best-effort guess from the name/image — cosmetic only.")
        sb.append("</div>")

        section(sb, "Connections", fg)
        row(sb, line(StackDoctorColors.EDGE, "──▶"), "depends_on", "Solid arrow points at the dependency that should start / be healthy first.", muted)
        row(sb, line(StackDoctorColors.EDGE, "╌╌╌"), "contains", "Dashed link from a file to its entry services (those nothing else depends on).", muted)
        row(sb, line(StackDoctorColors.EDGE_HIGHLIGHT, "──▶"), "highlighted", "Edges light up blue when you hover or select a connected node.", muted)

        section(sb, "Status tint", fg)
        row(sb, fill(StackDoctorColors.ERROR_FILL_TOP, StackDoctorColors.ERROR_TEXT), "Error", "The node (or, for a file, one of its services) has an error-level finding.", muted)
        row(sb, fill(StackDoctorColors.WARNING_FILL_TOP, StackDoctorColors.WARNING_TEXT), "Warning", "The node has a warning-level finding. Files tint for their services' findings too.", muted)
        row(sb, fill(StackDoctorColors.NODE_FILL_TOP, StackDoctorColors.NODE_BORDER), "Clean", "No findings.", muted)

        section(sb, "Badges", fg)
        badge(sb, "▸ N ports", StackDoctorColors.BADGE_NEUTRAL, "Count of published host ports.", muted)
        badge(sb, "local-only", StackDoctorColors.WARNING_TEXT, "A port is bound to 127.0.0.1 — unreachable from other devices.", muted)
        badge(sb, "♥ healthy", StackDoctorColors.OK_TEXT, "The service declares a healthcheck.", muted)
        badge(sb, "healthcheck off", StackDoctorColors.WARNING_TEXT, "A healthcheck is present but disabled.", muted)
        badge(sb, "✖ N errors / ⚠ N warnings", StackDoctorColors.ERROR_TEXT, "On a file node: totals across its services.", muted)

        section(sb, "Storage", fg)
        row(sb, ring(StackDoctorColors.VOLUME_PERSISTENT), "Persistent border", "Solid coloured outline: the service mounts a named volume or host bind, so its data survives a container recreate.", muted)
        row(sb, ring(StackDoctorColors.VOLUME_EPHEMERAL), "Ephemeral border", "Dashed outline: the service has only anonymous (unnamed) volumes — Docker names them randomly and the data is easily lost.", muted)
        badge(sb, "▤ N persistent", StackDoctorColors.VOLUME_PERSISTENT, "Count of named-volume and host-bind mounts on the service.", muted)
        badge(sb, "N unnamed", StackDoctorColors.VOLUME_EPHEMERAL, "Count of anonymous volumes — flagged because their data isn't reliably kept.", muted)
        sb.append("<div style='color:$muted;font-size:88%;margin:3px 0 0 16px;font-style:italic;'>")
        sb.append("Hover a service to list its volumes and where each is mounted.")
        sb.append("</div>")

        sb.append("<div style='color:$muted;font-size:88%;margin-top:8px;'>")
        sb.append("Click a node for its breakdown · double-click to open it in the editor.")
        sb.append("</div>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun section(sb: StringBuilder, title: String, fg: String) {
        sb.append("<div style='color:$fg;font-weight:bold;margin:8px 0 3px;'>").append(title).append("</div>")
    }

    private fun row(sb: StringBuilder, symbol: String, name: String, desc: String, muted: String) {
        sb.append("<div style='margin:2px 0;'>")
        sb.append("<span style='white-space:nowrap;'>").append(symbol).append("</span> ")
        sb.append("<b>").append(esc(name)).append("</b> ")
        sb.append("<span style='color:$muted;font-size:92%;'>— ").append(esc(desc)).append("</span>")
        sb.append("</div>")
    }

    private fun badge(sb: StringBuilder, text: String, color: JBColor, desc: String, muted: String) {
        sb.append("<div style='margin:2px 0;'>")
        sb.append("<span style='color:${hex(color)};font-weight:bold;'>").append(esc(text)).append("</span> ")
        sb.append("<span style='color:$muted;font-size:92%;'>— ").append(esc(desc)).append("</span>")
        sb.append("</div>")
    }

    private fun swatch(accent: JBColor, glyph: String): String =
        "<span style='color:${hex(accent)};font-weight:bold;'>${esc(glyph)} ▮</span>"

    private fun line(color: JBColor, glyph: String): String =
        "<span style='color:${hex(color)};font-weight:bold;'>${esc(glyph)}</span>"

    /** A small coloured rectangle-outline glyph standing in for a node's storage-persistence border. */
    private fun ring(color: JBColor): String =
        "<span style='color:${hex(color)};font-weight:bold;'>▭</span>"

    private fun fill(body: JBColor, edge: JBColor): String =
        "<span style='color:${hex(edge)};'>▮▮</span>"

    private fun title(cat: ServiceCategory): String =
        cat.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun hex(c: Color): String = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
