package com.qrowsolutions.stackdoctor.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.qrowsolutions.stackdoctor.analysis.FileMapNode
import com.qrowsolutions.stackdoctor.analysis.MapNode
import com.qrowsolutions.stackdoctor.analysis.ServiceMap
import com.qrowsolutions.stackdoctor.analysis.ServiceMapNode
import com.qrowsolutions.stackdoctor.analysis.ServiceExplainer
import com.qrowsolutions.stackdoctor.diagnostics.Diagnostic
import com.qrowsolutions.stackdoctor.diagnostics.Severity
import com.qrowsolutions.stackdoctor.model.ComposeService
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Path2D
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/**
 * Custom-drawn project map. Every compose file is a root node on the left; its services branch off
 * to the right, laid out in dependency layers (most-dependent nearest the file, dependencies flowing
 * right) with curved arrows for `depends_on` edges. Each service node shows a category accent, quick
 * health/port badges and a severity tint; file nodes show a service count and an error/warning
 * summary. Clicking a node opens a line-by-line breakdown; double-clicking opens the file.
 */
class ServiceMapPanel(
    private val map: ServiceMap,
    private val onSelect: (MapNode?) -> Unit,
    private val onActivate: (MapNode) -> Unit = {},
    private val onGenerateHealthcheck: (ServiceMapNode) -> Unit = {},
) : JPanel() {

    private val nodeWidth = JBUI.scale(172)
    private val nodeHeight = JBUI.scale(64)
    private val hGap = JBUI.scale(84)
    private val vGap = JBUI.scale(28)
    private val bandGap = JBUI.scale(40)
    private val margin = JBUI.scale(28)
    private val accentWidth = JBUI.scale(6)
    private val arc = JBUI.scale(14)

    private val bounds = LinkedHashMap<String, Rectangle>()
    private var selected: String? = null
    private var hovered: String? = null
    private var openPopup: JBPopup? = null

    init {
        isOpaque = true
        background = UIUtil.getTreeBackground()
        computeLayout()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val hit = nodeAt(e.point)
                selected = hit
                onSelect(map.node(hit))
                repaint()
                val node = map.node(hit) ?: return
                if (e.clickCount >= 2) onActivate(node) else showBreakdown(node)
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val hit = nodeAt(e.point)
                if (hit != hovered) {
                    hovered = hit
                    cursor = Cursor.getPredefinedCursor(if (hit != null) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
                    repaint()
                }
            }
        })
    }

    private fun nodeAt(p: Point): String? =
        bounds.entries.firstOrNull { it.value.contains(p) }?.key

    fun select(node: MapNode?) {
        selected = node?.id
        repaint()
    }

    private fun computeLayout() {
        bounds.clear()
        val rowPitch = nodeHeight + vGap
        var bandTop = margin
        var maxCol = 0

        map.analyses.forEachIndexed { idx, a ->
            val fileId = "file/$idx"
            val layers = a.graph.layers()
            val fileMaxLayer = layers.values.maxOrNull() ?: 0

            // Service columns: column 1 = entry services (next to the file), dependencies flow right.
            val columns = LinkedHashMap<Int, MutableList<String>>()
            for (svc in a.project.services) {
                val col = 1 + (fileMaxLayer - (layers[svc.name] ?: 0))
                columns.getOrPut(col) { mutableListOf() }.add("$idx/${svc.name}")
            }

            val rows = (columns.values.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
            val bandHeight = rows * rowPitch - vGap

            for ((col, members) in columns) {
                val offset = (rows - members.size) * rowPitch / 2
                members.forEachIndexed { row, id ->
                    val x = margin + col * (nodeWidth + hGap)
                    val y = bandTop + offset + row * rowPitch
                    bounds[id] = Rectangle(x, y, nodeWidth, nodeHeight)
                }
                if (col > maxCol) maxCol = col
            }

            // File node in column 0, vertically centred against its band.
            val fy = (bandTop + (bandHeight - nodeHeight) / 2).coerceAtLeast(bandTop)
            bounds[fileId] = Rectangle(margin, fy, nodeWidth, nodeHeight)

            bandTop += bandHeight + bandGap
        }

        val width = margin * 2 + (maxCol + 1) * (nodeWidth + hGap)
        val height = (bandTop - bandGap) + margin
        preferredSize = Dimension(width, height.coerceAtLeast(margin * 2 + nodeHeight))
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            paintEdges(g2)
            for ((id, rect) in bounds) paintNode(g2, id, rect)
        } finally {
            g2.dispose()
        }
    }

    private fun paintEdges(g2: Graphics2D) {
        val active = selected ?: hovered

        // Containment links (file -> entry services): lighter, dashed, no arrow head.
        val dash = BasicStroke(
            JBUIScale.scale(1.2f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
            10f, floatArrayOf(JBUIScale.scale(4f), JBUIScale.scale(4f)), 0f,
        )
        for ((from, tos) in map.containmentEdges) {
            val a = bounds[from] ?: continue
            for (to in tos) {
                val b = bounds[to] ?: continue
                val highlight = active != null && (from == active || to == active)
                g2.color = if (highlight) StackDoctorColors.EDGE_HIGHLIGHT else StackDoctorColors.EDGE
                g2.stroke = dash
                paintCurve(g2, a.x + a.width, a.y + a.height / 2, b.x, b.y + b.height / 2, arrow = false)
            }
        }

        // Dependency edges (service -> dependency): solid, arrowed.
        for ((from, tos) in map.dependencyEdges) {
            val a = bounds[from] ?: continue
            for (to in tos) {
                val b = bounds[to] ?: continue
                val highlight = active != null && (from == active || to == active)
                g2.color = if (highlight) StackDoctorColors.EDGE_HIGHLIGHT else StackDoctorColors.EDGE
                g2.stroke = BasicStroke(JBUIScale.scale(if (highlight) 2.2f else 1.4f))
                paintCurve(g2, a.x + a.width, a.y + a.height / 2, b.x, b.y + b.height / 2, arrow = true)
            }
        }
    }

    private fun paintCurve(g2: Graphics2D, startX: Int, startY: Int, endX: Int, endY: Int, arrow: Boolean) {
        val dx = (endX - startX) * 0.5
        val c1x = startX + dx
        val c2x = endX - dx
        val path = Path2D.Double()
        path.moveTo(startX.toDouble(), startY.toDouble())
        path.curveTo(c1x, startY.toDouble(), c2x, endY.toDouble(), endX.toDouble(), endY.toDouble())
        g2.draw(path)
        if (arrow) paintArrowHead(g2, c2x, endY.toDouble(), endX.toDouble(), endY.toDouble())
    }

    private fun paintArrowHead(g2: Graphics2D, x1: Double, y1: Double, x2: Double, y2: Double) {
        val size = JBUI.scale(8).toDouble()
        val angle = Math.atan2(y2 - y1, x2 - x1)
        val path = Path2D.Double()
        path.moveTo(x2, y2)
        path.lineTo(x2 - size * Math.cos(angle - Math.PI / 7), y2 - size * Math.sin(angle - Math.PI / 7))
        path.lineTo(x2 - size * Math.cos(angle + Math.PI / 7), y2 - size * Math.sin(angle + Math.PI / 7))
        path.closePath()
        g2.fill(path)
    }

    private fun paintNode(g2: Graphics2D, id: String, rect: Rectangle) {
        when (val node = map.node(id)) {
            is ServiceMapNode -> paintServiceNode(g2, node, rect)
            is FileMapNode -> paintFileNode(g2, node, rect)
            null -> {}
        }
    }

    /** Shared node chrome (shadow, glow, body, accent bar, border). Returns the text inset x. */
    private fun paintNodeBody(g2: Graphics2D, id: String, rect: Rectangle, severity: Severity?, accent: Color): Int {
        val isActive = id == selected
        val isHover = id == hovered

        g2.color = StackDoctorColors.SHADOW
        val sh = JBUI.scale(3)
        g2.fillRoundRect(rect.x + sh, rect.y + sh, rect.width, rect.height, arc, arc)

        if (isActive) {
            g2.color = withAlpha(StackDoctorColors.SELECTED_GLOW, 60)
            val grow = JBUI.scale(3)
            g2.fillRoundRect(rect.x - grow, rect.y - grow, rect.width + grow * 2, rect.height + grow * 2, arc + grow, arc + grow)
        }

        val (top, bottom) = fillColors(severity)
        g2.paint = GradientPaint(
            rect.x.toFloat(), rect.y.toFloat(), top,
            rect.x.toFloat(), (rect.y + rect.height).toFloat(), bottom,
        )
        g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, arc, arc)

        val accentClip = g2.clip
        g2.clipRect(rect.x, rect.y, accentWidth, rect.height)
        g2.color = accent
        g2.fillRoundRect(rect.x, rect.y, accentWidth + arc, rect.height, arc, arc)
        g2.clip = accentClip

        g2.stroke = BasicStroke(JBUIScale.scale(if (isActive) 2.2f else if (isHover) 1.8f else 1.2f))
        g2.color = if (isActive || isHover) StackDoctorColors.SELECTED_BORDER else StackDoctorColors.NODE_BORDER
        g2.drawRoundRect(rect.x, rect.y, rect.width, rect.height, arc, arc)

        return rect.x + accentWidth + JBUI.scale(10)
    }

    private fun paintServiceNode(g2: Graphics2D, node: ServiceMapNode, rect: Rectangle) {
        val svc = node.service
        val category = ServiceCategory.of(svc)
        val left = paintNodeBody(g2, node.id, rect, map.worstSeverity[node.id], category.accent)
        val textWidth = rect.width - accentWidth - JBUI.scale(20)

        g2.font = JBUI.Fonts.label().asBold()
        val fm = g2.fontMetrics
        g2.color = category.accent
        val glyph = category.glyph + " "
        g2.drawString(glyph, left, rect.y + JBUI.scale(21))
        val glyphW = fm.stringWidth(glyph)
        g2.color = UIUtil.getLabelForeground()
        g2.drawString(clip(svc.name, fm, textWidth - glyphW), left + glyphW, rect.y + JBUI.scale(21))

        g2.font = JBUI.Fonts.smallFont()
        g2.color = UIUtil.getContextHelpForeground()
        g2.drawString(clip(subLine(svc), g2.fontMetrics, textWidth), left, rect.y + JBUI.scale(38))

        paintServiceBadges(g2, node, rect, left)
    }

    private fun paintFileNode(g2: Graphics2D, node: FileMapNode, rect: Rectangle) {
        val left = paintNodeBody(g2, node.id, rect, map.worstSeverity[node.id], StackDoctorColors.ACCENT_FILE)
        val textWidth = rect.width - accentWidth - JBUI.scale(20)

        g2.font = JBUI.Fonts.label().asBold()
        val fm = g2.fontMetrics
        g2.color = StackDoctorColors.ACCENT_FILE
        val glyph = "❐ " // file glyph
        g2.drawString(glyph, left, rect.y + JBUI.scale(21))
        val glyphW = fm.stringWidth(glyph)
        g2.color = UIUtil.getLabelForeground()
        g2.drawString(clip(node.displayName, fm, textWidth - glyphW), left + glyphW, rect.y + JBUI.scale(21))

        val count = node.analysis.project.services.size
        g2.font = JBUI.Fonts.smallFont()
        g2.color = UIUtil.getContextHelpForeground()
        g2.drawString("compose file · $count service${if (count == 1) "" else "s"}", left, rect.y + JBUI.scale(38))

        paintFileBadges(g2, node, rect, left)
    }

    private fun paintServiceBadges(g2: Graphics2D, node: ServiceMapNode, rect: Rectangle, left: Int) {
        val svc = node.service
        val parts = mutableListOf<Pair<String, JBColor>>()

        // This service's own findings come first, so a node always advertises its issue count.
        val own = node.analysis.diagnostics.filter { it.service == svc.name }
        val errors = own.count { it.severity == Severity.ERROR }
        val warnings = own.count { it.severity == Severity.WARNING }
        if (errors > 0) parts += "✖ $errors" to StackDoctorColors.ERROR_TEXT
        if (warnings > 0) parts += "⚠ $warnings" to StackDoctorColors.WARNING_TEXT

        val published = svc.ports.count { it.isPublished }
        if (published > 0) parts += "▸ $published port" + (if (published > 1) "s" else "") to StackDoctorColors.BADGE_NEUTRAL
        if (svc.ports.any { it.isLoopbackBound }) parts += "local-only" to StackDoctorColors.WARNING_TEXT
        when {
            svc.healthcheckDisabled -> parts += "healthcheck off" to StackDoctorColors.WARNING_TEXT
            svc.hasHealthcheck -> parts += "♥ healthy" to StackDoctorColors.OK_TEXT
        }
        paintBadges(g2, parts, rect, left)
    }

    private fun paintFileBadges(g2: Graphics2D, node: FileMapNode, rect: Rectangle, left: Int) {
        val diags = node.analysis.diagnostics
        val errors = diags.count { it.severity == Severity.ERROR }
        val warnings = diags.count { it.severity == Severity.WARNING }
        val parts = mutableListOf<Pair<String, JBColor>>()
        if (errors > 0) parts += "✖ $errors error${if (errors == 1) "" else "s"}" to StackDoctorColors.ERROR_TEXT
        if (warnings > 0) parts += "⚠ $warnings warning${if (warnings == 1) "" else "s"}" to StackDoctorColors.WARNING_TEXT
        if (parts.isEmpty()) parts += "✓ no issues" to StackDoctorColors.OK_TEXT
        paintBadges(g2, parts, rect, left)
    }

    private fun paintBadges(g2: Graphics2D, parts: List<Pair<String, JBColor>>, rect: Rectangle, left: Int) {
        g2.font = JBUI.Fonts.smallFont()
        var x = left
        val y = rect.y + JBUI.scale(54)
        for ((text, color) in parts) {
            g2.color = color
            g2.drawString(text, x, y)
            x += g2.fontMetrics.stringWidth(text) + JBUI.scale(10)
            if (x > rect.x + rect.width - JBUI.scale(20)) break
        }
    }

    private fun fillColors(severity: Severity?): Pair<Color, Color> = when (severity) {
        Severity.ERROR -> StackDoctorColors.ERROR_FILL_TOP to StackDoctorColors.ERROR_FILL_BOTTOM
        Severity.WARNING -> StackDoctorColors.WARNING_FILL_TOP to StackDoctorColors.WARNING_FILL_BOTTOM
        else -> StackDoctorColors.NODE_FILL_TOP to StackDoctorColors.NODE_FILL_BOTTOM
    }

    private fun withAlpha(c: Color, alpha: Int): Color = Color(c.red, c.green, c.blue, alpha)

    private fun subLine(svc: ComposeService): String = when {
        svc.image != null -> svc.image
        svc.hasBuild -> "build"
        else -> ""
    }

    private fun clip(text: String, fm: java.awt.FontMetrics, maxWidth: Int): String {
        if (fm.stringWidth(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && fm.stringWidth("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    // ---- Breakdown popup --------------------------------------------------------------------

    private fun showBreakdown(node: MapNode) {
        val rect = bounds[node.id] ?: return
        openPopup?.cancel()

        val (title, html) = when (node) {
            is ServiceMapNode -> "Service: ${node.service.name}" to serviceBreakdownHtml(node)
            is FileMapNode -> "File: ${node.displayName}" to fileBreakdownHtml(node)
        }

        val pane = JEditorPane("text/html", html).apply {
            isEditable = false
            isOpaque = true
            background = UIUtil.getToolTipBackground()
            border = JBUI.Borders.empty(4, 8)
            caretPosition = 0
            addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED && e.description == ADD_HEALTHCHECK_HREF && node is ServiceMapNode) {
                    openPopup?.cancel()
                    onGenerateHealthcheck(node)
                }
            }
        }
        val scroll = JBScrollPane(pane).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(JBUI.scale(380), JBUI.scale(260))
        }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scroll, pane)
            .setTitle(title)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
        openPopup = popup
        popup.show(RelativePoint(this, Point(rect.x + rect.width + JBUI.scale(8), rect.y)))
    }

    private fun serviceBreakdownHtml(node: ServiceMapNode): String {
        val svc = node.service
        val fg = hex(UIUtil.getLabelForeground())
        val muted = hex(UIUtil.getContextHelpForeground())
        val accent = hex(ServiceCategory.of(svc).accent)
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:sans-serif;color:$fg;'>")

        val svcDiags = node.analysis.diagnostics.filter { it.service == svc.name }
        appendDiagnostics(sb, svcDiags, muted)

        // Offer a per-service healthcheck when this one has none.
        if (!svc.hasHealthcheck || svc.healthcheckDisabled) {
            val link = hex(StackDoctorColors.SELECTED_BORDER)
            sb.append("<div style='margin-bottom:8px;'>")
            sb.append("<a href='$ADD_HEALTHCHECK_HREF' style='color:$link;text-decoration:none;'>")
            sb.append("&#9658; Add a generated healthcheck</a>")
            sb.append("</div>")
        }

        sb.append("<div style='color:$accent;font-weight:bold;margin-bottom:4px;'>Configuration breakdown</div>")
        sb.append("<table cellpadding='0' cellspacing='0' style='width:100%;'>")
        for (line in ServiceExplainer.explain(svc)) {
            sb.append("<tr><td valign='top' style='padding:3px 8px 3px 0;white-space:nowrap;color:$accent;'>")
            sb.append(esc(line.label))
            sb.append("</td><td valign='top' style='padding:3px 0;'>")
            if (line.value.isNotBlank()) {
                sb.append("<code style='color:$fg;'>").append(esc(line.value)).append("</code><br>")
            }
            sb.append("<span style='color:$muted;font-size:92%;'>").append(esc(line.explanation)).append("</span>")
            sb.append("</td></tr>")
        }
        sb.append("</table>")
        sb.append("<div style='color:$muted;font-size:88%;margin-top:8px;'>Double-click the node to open it in the editor.</div>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun fileBreakdownHtml(node: FileMapNode): String {
        val fg = hex(UIUtil.getLabelForeground())
        val muted = hex(UIUtil.getContextHelpForeground())
        val accent = hex(StackDoctorColors.ACCENT_FILE)
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:sans-serif;color:$fg;'>")

        // Project-wide findings for this file (those not tied to a single service).
        val fileDiags = node.analysis.diagnostics.filter { it.service == null }
        appendDiagnostics(sb, fileDiags, muted)

        sb.append("<div style='color:$accent;font-weight:bold;margin-bottom:4px;'>Services</div>")
        sb.append("<table cellpadding='0' cellspacing='0' style='width:100%;'>")
        for (svc in node.analysis.project.services) {
            val cat = ServiceCategory.of(svc)
            val errors = node.analysis.diagnostics.count { it.service == svc.name && it.severity == Severity.ERROR }
            val warnings = node.analysis.diagnostics.count { it.service == svc.name && it.severity == Severity.WARNING }
            val status = when {
                errors > 0 -> "<span style='color:${hex(StackDoctorColors.ERROR_TEXT)};'>✖ $errors</span>"
                warnings > 0 -> "<span style='color:${hex(StackDoctorColors.WARNING_TEXT)};'>⚠ $warnings</span>"
                else -> "<span style='color:${hex(StackDoctorColors.OK_TEXT)};'>✓</span>"
            }
            sb.append("<tr><td valign='top' style='padding:3px 8px 3px 0;white-space:nowrap;color:${hex(cat.accent)};'>")
            sb.append(esc(cat.glyph)).append(' ').append(esc(svc.name))
            sb.append("</td><td valign='top' style='padding:3px 8px 3px 0;'>")
            sb.append("<span style='color:$muted;font-size:92%;'>").append(esc(subLine(svc))).append("</span>")
            sb.append("</td><td valign='top' style='padding:3px 0;white-space:nowrap;'>").append(status).append("</td></tr>")
        }
        sb.append("</table>")
        sb.append("<div style='color:$muted;font-size:88%;margin-top:8px;'>Click a service node for its breakdown · double-click to open the file.</div>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun appendDiagnostics(sb: StringBuilder, diags: List<Diagnostic>, muted: String) {
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

    private fun hex(c: Color): String = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val ADD_HEALTHCHECK_HREF = "stackdoctor:add-healthcheck"
    }
}
