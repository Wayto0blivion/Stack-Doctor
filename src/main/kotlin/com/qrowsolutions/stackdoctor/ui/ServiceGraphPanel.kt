package com.qrowsolutions.stackdoctor.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.qrowsolutions.stackdoctor.analysis.ServiceExplainer
import com.qrowsolutions.stackdoctor.diagnostics.Diagnostic
import com.qrowsolutions.stackdoctor.diagnostics.ServiceGraph
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

/**
 * Custom-drawn dependency graph: services laid out in dependency layers (most-dependent on the
 * left, their dependencies flowing to the right) with curved arrows for `depends_on` edges. Each
 * node shows a category accent, quick health/port badges and a severity tint when it has
 * diagnostics. Clicking a node opens a line-by-line breakdown; double-clicking opens the file.
 */
class ServiceGraphPanel(
    private val graph: ServiceGraph,
    private val diagnostics: List<Diagnostic>,
    private val onSelect: (String?) -> Unit,
    private val onActivate: (String) -> Unit = {},
) : JPanel() {

    private val nodeWidth = JBUI.scale(172)
    private val nodeHeight = JBUI.scale(64)
    private val hGap = JBUI.scale(84)
    private val vGap = JBUI.scale(28)
    private val margin = JBUI.scale(28)
    private val accentWidth = JBUI.scale(6)
    private val arc = JBUI.scale(14)

    private val bounds = LinkedHashMap<String, Rectangle>()
    private var selected: String? = null
    private var hovered: String? = null
    private var openPopup: JBPopup? = null

    /** Worst severity per service, for tinting. */
    private val worstSeverity: Map<String, Severity> = diagnostics
        .filter { it.service != null }
        .groupBy { it.service!! }
        .mapValues { (_, ds) -> ds.minByOrNull { it.severity.ordinal }!!.severity }

    init {
        isOpaque = true
        background = UIUtil.getTreeBackground()
        computeLayout()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val hit = nodeAt(e.point)
                selected = hit
                onSelect(hit)
                repaint()
                if (hit != null) {
                    if (e.clickCount >= 2) onActivate(hit) else showBreakdown(hit)
                }
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

    fun select(service: String?) {
        selected = service
        repaint()
    }

    private fun computeLayout() {
        bounds.clear()
        val layers = graph.layers()
        val maxLayer = (layers.values.maxOrNull() ?: 0)
        // Column index 0 = highest layer (top-level consumer like frontend) on the left.
        val columns = LinkedHashMap<Int, MutableList<String>>()
        for (node in graph.nodes) {
            val col = maxLayer - (layers[node] ?: 0)
            columns.getOrPut(col) { mutableListOf() }.add(node)
        }

        val maxRows = (columns.values.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
        val rowPitch = nodeHeight + vGap
        for ((col, members) in columns) {
            // Centre shorter columns vertically against the tallest one so the graph looks balanced.
            val offset = (maxRows - members.size) * rowPitch / 2
            members.forEachIndexed { row, node ->
                val x = margin + col * (nodeWidth + hGap)
                val y = margin + offset + row * rowPitch
                bounds[node] = Rectangle(x, y, nodeWidth, nodeHeight)
            }
        }

        val width = margin * 2 + columns.size.coerceAtLeast(1) * (nodeWidth + hGap)
        val height = margin * 2 + maxRows * rowPitch
        preferredSize = Dimension(width, height)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            paintEdges(g2)
            for ((name, rect) in bounds) paintNode(g2, name, rect)
        } finally {
            g2.dispose()
        }
    }

    private fun paintEdges(g2: Graphics2D) {
        val active = selected ?: hovered
        for ((from, tos) in graph.edges) {
            val a = bounds[from] ?: continue
            for (to in tos) {
                val b = bounds[to] ?: continue
                val highlight = active != null && (from == active || to == active)
                // 'from' depends on 'to' -> arrow points from dependent toward its dependency.
                val startX = a.x + a.width
                val startY = a.y + a.height / 2
                val endX = b.x
                val endY = b.y + b.height / 2
                g2.color = if (highlight) StackDoctorColors.EDGE_HIGHLIGHT else StackDoctorColors.EDGE
                g2.stroke = BasicStroke(JBUIScale.scale(if (highlight) 2.2f else 1.4f))
                paintCurve(g2, startX, startY, endX, endY)
            }
        }
    }

    private fun paintCurve(g2: Graphics2D, startX: Int, startY: Int, endX: Int, endY: Int) {
        val dx = (endX - startX) * 0.5
        val c1x = startX + dx
        val c2x = endX - dx
        val path = Path2D.Double()
        path.moveTo(startX.toDouble(), startY.toDouble())
        path.curveTo(c1x, startY.toDouble(), c2x, endY.toDouble(), endX.toDouble(), endY.toDouble())
        g2.draw(path)
        // Arrow head oriented along the curve's tangent at the end (approx. c2 -> end).
        paintArrowHead(g2, c2x, endY.toDouble(), endX.toDouble(), endY.toDouble())
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

    private fun paintNode(g2: Graphics2D, name: String, rect: Rectangle) {
        val svc = graph.project.service(name)
        val severity = worstSeverity[name]
        val isActive = name == selected
        val isHover = name == hovered

        // Drop shadow.
        g2.color = StackDoctorColors.SHADOW
        val sh = JBUI.scale(3)
        g2.fillRoundRect(rect.x + sh, rect.y + sh, rect.width, rect.height, arc, arc)

        // Selection glow.
        if (isActive) {
            g2.color = withAlpha(StackDoctorColors.SELECTED_GLOW, 60)
            val grow = JBUI.scale(3)
            g2.fillRoundRect(rect.x - grow, rect.y - grow, rect.width + grow * 2, rect.height + grow * 2, arc + grow, arc + grow)
        }

        // Gradient body.
        val (top, bottom) = fillColors(severity)
        g2.paint = GradientPaint(
            rect.x.toFloat(), rect.y.toFloat(), top,
            rect.x.toFloat(), (rect.y + rect.height).toFloat(), bottom,
        )
        g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, arc, arc)

        // Category accent bar down the left edge (clipped to the rounded corners).
        val category = svc?.let { ServiceCategory.of(it) } ?: ServiceCategory.GENERIC
        val accentClip = g2.clip
        g2.clipRect(rect.x, rect.y, accentWidth, rect.height)
        g2.color = category.accent
        g2.fillRoundRect(rect.x, rect.y, accentWidth + arc, rect.height, arc, arc)
        g2.clip = accentClip

        // Border.
        g2.stroke = BasicStroke(JBUIScale.scale(if (isActive) 2.2f else if (isHover) 1.8f else 1.2f))
        g2.color = when {
            isActive -> StackDoctorColors.SELECTED_BORDER
            isHover -> StackDoctorColors.SELECTED_BORDER
            else -> StackDoctorColors.NODE_BORDER
        }
        g2.drawRoundRect(rect.x, rect.y, rect.width, rect.height, arc, arc)

        val left = rect.x + accentWidth + JBUI.scale(10)
        val textWidth = rect.width - accentWidth - JBUI.scale(20)

        // Category glyph + service name.
        g2.font = JBUI.Fonts.label().asBold()
        val fm = g2.fontMetrics
        g2.color = category.accent
        val glyph = category.glyph + " "
        g2.drawString(glyph, left, rect.y + JBUI.scale(21))
        val glyphW = fm.stringWidth(glyph)
        g2.color = UIUtil.getLabelForeground()
        g2.drawString(clip(name, fm, textWidth - glyphW), left + glyphW, rect.y + JBUI.scale(21))

        // Subline: image / build.
        g2.font = JBUI.Fonts.smallFont()
        g2.color = UIUtil.getContextHelpForeground()
        val sub = svc?.let { subLine(it) } ?: ""
        g2.drawString(clip(sub, g2.fontMetrics, textWidth), left, rect.y + JBUI.scale(38))

        // Badges row.
        if (svc != null) paintBadges(g2, svc, rect, left)
    }

    private fun paintBadges(g2: Graphics2D, svc: ComposeService, rect: Rectangle, left: Int) {
        g2.font = JBUI.Fonts.smallFont()
        val parts = mutableListOf<Pair<String, JBColor>>()
        val published = svc.ports.count { it.isPublished }
        if (published > 0) parts += "▸ $published port" + (if (published > 1) "s" else "") to StackDoctorColors.BADGE_NEUTRAL
        if (svc.ports.any { it.isLoopbackBound }) parts += "local-only" to StackDoctorColors.WARNING_TEXT
        when {
            svc.healthcheckDisabled -> parts += "healthcheck off" to StackDoctorColors.WARNING_TEXT
            svc.hasHealthcheck -> parts += "♥ healthy" to StackDoctorColors.OK_TEXT
        }
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

    private fun showBreakdown(name: String) {
        val svc = graph.project.service(name) ?: return
        val rect = bounds[name] ?: return
        openPopup?.cancel()

        val pane = JEditorPane("text/html", breakdownHtml(name, svc)).apply {
            isEditable = false
            isOpaque = true
            background = UIUtil.getToolTipBackground()
            border = JBUI.Borders.empty(4, 8)
            caretPosition = 0
        }
        val scroll = JBScrollPane(pane).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(JBUI.scale(380), JBUI.scale(260))
        }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scroll, pane)
            .setTitle("Service: $name")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
        openPopup = popup
        popup.show(RelativePoint(this, Point(rect.x + rect.width + JBUI.scale(8), rect.y)))
    }

    private fun breakdownHtml(name: String, svc: ComposeService): String {
        val fg = hex(UIUtil.getLabelForeground())
        val muted = hex(UIUtil.getContextHelpForeground())
        val accent = hex(ServiceCategory.of(svc).accent)
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:sans-serif;color:$fg;'>")

        val svcDiags = diagnostics.filter { it.service == name }
        if (svcDiags.isNotEmpty()) {
            sb.append("<div style='margin-bottom:8px;'>")
            for (d in svcDiags) {
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

    private fun hex(c: Color): String = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
