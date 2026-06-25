package com.qrowsolutions.stackdoctor.ui

import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.qrowsolutions.stackdoctor.diagnostics.Diagnostic
import com.qrowsolutions.stackdoctor.diagnostics.ServiceGraph
import com.qrowsolutions.stackdoctor.diagnostics.Severity
import com.qrowsolutions.stackdoctor.model.ComposeService
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.swing.JPanel

/**
 * Custom-drawn dependency graph: services laid out in dependency layers (most-dependent on the
 * left, their dependencies flowing to the right) with arrows for `depends_on` edges. Each node
 * shows quick health/port badges and a severity tint when it has diagnostics.
 */
class ServiceGraphPanel(
    private val graph: ServiceGraph,
    private val diagnostics: List<Diagnostic>,
    private val onSelect: (String?) -> Unit,
) : JPanel() {

    private val nodeWidth = JBUI.scale(150)
    private val nodeHeight = JBUI.scale(54)
    private val hGap = JBUI.scale(70)
    private val vGap = JBUI.scale(24)
    private val margin = JBUI.scale(24)

    private val bounds = LinkedHashMap<String, Rectangle>()
    private var selected: String? = null

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
                val hit = bounds.entries.firstOrNull { it.value.contains(e.point) }?.key
                selected = hit
                onSelect(hit)
                repaint()
            }
        })
    }

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

        var maxRows = 0
        for ((col, members) in columns) {
            members.forEachIndexed { row, node ->
                val x = margin + col * (nodeWidth + hGap)
                val y = margin + row * (nodeHeight + vGap)
                bounds[node] = Rectangle(x, y, nodeWidth, nodeHeight)
            }
            maxRows = maxOf(maxRows, members.size)
        }

        val width = margin * 2 + (columns.size.coerceAtLeast(1)) * (nodeWidth + hGap)
        val height = margin * 2 + maxRows.coerceAtLeast(1) * (nodeHeight + vGap)
        preferredSize = Dimension(width, height)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            paintEdges(g2)
            for ((name, rect) in bounds) paintNode(g2, name, rect)
        } finally {
            g2.dispose()
        }
    }

    private fun paintEdges(g2: Graphics2D) {
        g2.stroke = BasicStroke(JBUIScale.scale(1.4f))
        for ((from, tos) in graph.edges) {
            val a = bounds[from] ?: continue
            for (to in tos) {
                val b = bounds[to] ?: continue
                // 'from' depends on 'to' -> arrow points from dependent toward its dependency.
                val startX = a.x + a.width
                val startY = a.y + a.height / 2
                val endX = b.x
                val endY = b.y + b.height / 2
                g2.color = StackDoctorColors.EDGE
                g2.drawLine(startX, startY, endX, endY)
                paintArrowHead(g2, startX, startY, endX, endY)
            }
        }
    }

    private fun paintArrowHead(g2: Graphics2D, x1: Int, y1: Int, x2: Int, y2: Int) {
        val size = JBUI.scale(7).toDouble()
        val angle = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val path = Path2D.Double()
        path.moveTo(x2.toDouble(), y2.toDouble())
        path.lineTo(x2 - size * Math.cos(angle - Math.PI / 7), y2 - size * Math.sin(angle - Math.PI / 7))
        path.lineTo(x2 - size * Math.cos(angle + Math.PI / 7), y2 - size * Math.sin(angle + Math.PI / 7))
        path.closePath()
        g2.fill(path)
    }

    private fun paintNode(g2: Graphics2D, name: String, rect: Rectangle) {
        val svc = graph.project.service(name)
        val arc = JBUI.scale(12)

        val fill = when (worstSeverity[name]) {
            Severity.ERROR -> StackDoctorColors.ERROR_FILL
            Severity.WARNING -> StackDoctorColors.WARNING_FILL
            else -> StackDoctorColors.NODE_FILL
        }
        g2.color = fill
        g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, arc, arc)

        g2.stroke = BasicStroke(JBUIScale.scale(if (name == selected) 2.2f else 1.2f))
        g2.color = if (name == selected) StackDoctorColors.SELECTED_BORDER else StackDoctorColors.NODE_BORDER
        g2.drawRoundRect(rect.x, rect.y, rect.width, rect.height, arc, arc)

        // Service name.
        g2.color = UIUtil.getLabelForeground()
        g2.font = JBUI.Fonts.label().asBold()
        val fm = g2.fontMetrics
        val title = clip(name, fm, rect.width - JBUI.scale(16))
        g2.drawString(title, rect.x + JBUI.scale(10), rect.y + JBUI.scale(20))

        // Subline: image / build.
        g2.font = JBUI.Fonts.smallFont()
        g2.color = UIUtil.getContextHelpForeground()
        val sub = svc?.let { subLine(it) } ?: ""
        g2.drawString(clip(sub, g2.fontMetrics, rect.width - JBUI.scale(16)), rect.x + JBUI.scale(10), rect.y + JBUI.scale(36))

        // Badges row.
        if (svc != null) paintBadges(g2, svc, rect)
    }

    private fun paintBadges(g2: Graphics2D, svc: ComposeService, rect: Rectangle) {
        g2.font = JBUI.Fonts.smallFont()
        val parts = mutableListOf<Pair<String, JBColor>>()
        val published = svc.ports.count { it.isPublished }
        if (published > 0) parts += "▸ $published port" + (if (published > 1) "s" else "") to StackDoctorColors.BADGE_NEUTRAL
        if (svc.ports.any { it.isLoopbackBound }) parts += "local-only" to StackDoctorColors.WARNING_TEXT
        when {
            svc.healthcheckDisabled -> parts += "healthcheck off" to StackDoctorColors.WARNING_TEXT
            svc.hasHealthcheck -> parts += "♥" to StackDoctorColors.OK_TEXT
        }
        var x = rect.x + JBUI.scale(10)
        val y = rect.y + JBUI.scale(50)
        for ((text, color) in parts) {
            g2.color = color
            g2.drawString(text, x, y)
            x += g2.fontMetrics.stringWidth(text) + JBUI.scale(10)
            if (x > rect.x + rect.width - JBUI.scale(20)) break
        }
    }

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
}
