package com.qrowsolutions.stackdoctor.ui

import com.qrowsolutions.stackdoctor.diagnostics.Diagnostic
import com.qrowsolutions.stackdoctor.diagnostics.Severity
import java.awt.Color

/**
 * Small shared HTML helpers for the Stack Doctor popups (the service/file breakdown in
 * [ServiceMapPanel] and the merge preview in [MergePreviewDialog]), so a diagnostic looks the
 * same everywhere it is rendered.
 */
object ComposeHtml {

    fun hex(c: Color): String = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Appends a severity-coloured diagnostics block (title + hint) into [sb]; a no-op when empty. */
    fun appendDiagnostics(sb: StringBuilder, diags: List<Diagnostic>, muted: String) {
        if (diags.isEmpty()) return
        sb.append("<div style='margin-bottom:8px;'>")
        for (d in diags) {
            val color = when (d.severity) {
                Severity.ERROR -> hex(StackDoctorColors.ERROR_TEXT)
                Severity.WARNING -> hex(StackDoctorColors.WARNING_TEXT)
                Severity.INFO -> muted
            }
            val mark = if (d.severity == Severity.ERROR) "✖" else "⚠"
            sb.append("<div style='margin:2px 0;'><span style='color:$color;'>$mark ")
            sb.append(esc(d.title)).append("</span>")
            d.hint?.let { sb.append("<br><span style='color:$muted;font-size:90%;'>&nbsp;&nbsp;&nbsp;${esc(it)}</span>") }
            sb.append("</div>")
        }
        sb.append("</div>")
    }
}
