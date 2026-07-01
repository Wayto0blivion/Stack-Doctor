package com.qrowsolutions.stackdoctor.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.qrowsolutions.stackdoctor.analysis.FileMapNode
import com.qrowsolutions.stackdoctor.analysis.MapNode
import com.qrowsolutions.stackdoctor.analysis.ServiceMap
import com.qrowsolutions.stackdoctor.analysis.ServiceMapNode
import com.qrowsolutions.stackdoctor.diagnostics.Severity
import com.qrowsolutions.stackdoctor.model.ComposeService
import com.qrowsolutions.stackdoctor.model.VolumeMount
import com.qrowsolutions.stackdoctor.model.VolumeSummary
import com.qrowsolutions.stackdoctor.parser.FieldKind
import com.qrowsolutions.stackdoctor.parser.ServiceField
import com.qrowsolutions.stackdoctor.parser.ServiceFieldEdit
import com.qrowsolutions.stackdoctor.parser.ServiceFields
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Path2D
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.event.HyperlinkEvent
import kotlin.math.abs

/**
 * Custom-drawn project map. Every compose file is a root node on the left; its services branch off
 * to the right, laid out in dependency layers (most-dependent nearest the file, dependencies flowing
 * right) with curved arrows for `depends_on` edges. Each service node shows a category accent, quick
 * health/port badges and a severity tint; file nodes show a service count and an error/warning
 * summary. Clicking a service node opens an editable form for its parameters; clicking a file node
 * opens a breakdown of its services; double-clicking either opens the file.
 */
class ServiceMapPanel(
    private val map: ServiceMap,
    private val onSelect: (MapNode?) -> Unit,
    private val onActivate: (MapNode) -> Unit = {},
    private val onGenerateHealthcheck: (ServiceMapNode) -> Unit = {},
    /** Supplies the editable parameters for a service node (read from YAML on the EDT). */
    private val fieldsFor: (ServiceMapNode) -> List<ServiceField> = { emptyList() },
    /** Persists the form's changed fields back into the compose file. */
    private val onApplyServiceEdits: (ServiceMapNode, List<ServiceFieldEdit>) -> Unit = { _, _ -> },
    /** User-dragged node positions by node id; updated on drag so the layout survives a refresh. */
    private val positions: MutableMap<String, Point> = LinkedHashMap(),
) : JPanel() {

    private val nodeWidth = JBUI.scale(172)
    // Tall enough for title + sub-line + two badge rows, so a busy node (ports + healthcheck +
    // volumes) wraps its badges onto a second line that still sits inside the card. Baselines below
    // keep a steady 16px rhythm: 21 (title), 38 (sub-line), 54 (badge row 1), 70 (badge row 2).
    private val nodeHeight = JBUI.scale(80)
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

    // Drag-to-reposition state. A press records the node under the cursor and its origin; a drag
    // past [dragThreshold] moves it (updating [positions]); the trailing click is suppressed so a
    // drag never opens the edit form.
    private var dragId: String? = null
    private var dragStart: Point? = null
    private var dragOrigin: Point? = null
    private var movedDuringDrag = false
    private var justDragged = false
    private val dragThreshold = JBUI.scale(4)

    init {
        // Non-opaque so a user-set IDE background image shows through the canvas between nodes
        // instead of being hidden behind a solid fill (which also flashed on activation). The nodes
        // paint their own fills in paintNode, so they stay solid. See memory: ide-background-image.
        isOpaque = false
        // Enable per-node tooltips (getToolTipText resolves the node under the cursor); used to list
        // a service's attached volumes and whether each one persists.
        ToolTipManager.sharedInstance().registerComponent(this)
        computeLayout()
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragId = nodeAt(e.point)
                dragStart = e.point
                dragOrigin = dragId?.let { bounds[it]?.location }
                movedDuringDrag = false
                justDragged = false
            }

            override fun mouseReleased(e: MouseEvent) {
                if (movedDuringDrag) {
                    justDragged = true // swallow the click Swing may still deliver after a drag
                    revalidate()
                    repaint()
                }
                dragId = null
                dragStart = null
                dragOrigin = null
            }

            override fun mouseClicked(e: MouseEvent) {
                if (justDragged) { justDragged = false; return }
                val hit = nodeAt(e.point)
                selected = hit
                onSelect(map.node(hit))
                repaint()
                val node = map.node(hit) ?: return
                when {
                    e.clickCount >= 2 -> { openPopup?.cancel(); onActivate(node) }
                    node is ServiceMapNode -> showServiceForm(node)
                    node is FileMapNode -> showBreakdown(node)
                }
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val hit = nodeAt(e.point)
                if (hit != hovered) {
                    hovered = hit
                    cursor = Cursor.getPredefinedCursor(if (hit != null) Cursor.MOVE_CURSOR else Cursor.DEFAULT_CURSOR)
                    repaint()
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                val id = dragId ?: return
                val origin = dragOrigin ?: return
                val start = dragStart ?: return
                val rect = bounds[id] ?: return
                val dx = e.x - start.x
                val dy = e.y - start.y
                if (!movedDuringDrag && (abs(dx) > dragThreshold || abs(dy) > dragThreshold)) {
                    movedDuringDrag = true
                }
                val nx = (origin.x + dx).coerceAtLeast(margin)
                val ny = (origin.y + dy).coerceAtLeast(margin)
                rect.setLocation(nx, ny)
                positions[id] = Point(nx, ny)
                growCanvas()
                repaint()
            }
        })
    }

    private fun nodeAt(p: Point): String? =
        bounds.entries.firstOrNull { it.value.contains(p) }?.key

    /** Tooltip for the node under the cursor: a service's attached volumes and their persistence. */
    override fun getToolTipText(event: MouseEvent): String? {
        val node = map.node(nodeAt(event.point)) as? ServiceMapNode ?: return null
        val summary = VolumeSummary.of(node.service)
        return if (summary.isEmpty) null else volumeTooltipHtml(summary)
    }

    fun select(node: MapNode?) {
        selected = node?.id
        repaint()
    }

    private fun computeLayout() {
        bounds.clear()
        val rowPitch = nodeHeight + vGap
        var bandTop = margin

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
            }

            // File node in column 0, vertically centred against its band.
            val fy = (bandTop + (bandHeight - nodeHeight) / 2).coerceAtLeast(bandTop)
            bounds[fileId] = Rectangle(margin, fy, nodeWidth, nodeHeight)

            bandTop += bandHeight + bandGap
        }

        applySavedPositions()
        updatePreferredSize()
    }

    /** Overrides computed locations with any user-dragged positions for the same node ids. */
    private fun applySavedPositions() {
        for ((id, p) in positions) bounds[id]?.setLocation(p)
    }

    /** Sizes the canvas to fit every node's bounds (plus a margin), so the scroll pane can reach them. */
    private fun updatePreferredSize() {
        val maxX = bounds.values.maxOfOrNull { it.x + it.width } ?: nodeWidth
        val maxY = bounds.values.maxOfOrNull { it.y + it.height } ?: nodeHeight
        preferredSize = Dimension(maxX + margin, (maxY + margin).coerceAtLeast(margin * 2 + nodeHeight))
    }

    /** During a drag, grows (never shrinks) the canvas so a node dragged past the edge stays reachable. */
    private fun growCanvas() {
        val needX = (bounds.values.maxOfOrNull { it.x + it.width } ?: 0) + margin
        val needY = (bounds.values.maxOfOrNull { it.y + it.height } ?: 0) + margin
        val cur = preferredSize
        if (needX > cur.width || needY > cur.height) {
            preferredSize = Dimension(maxOf(needX, cur.width), maxOf(needY, cur.height))
            revalidate()
        }
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

    /**
     * Shared node chrome (shadow, glow, body, accent bar, border). Returns the text inset x.
     * [volumeBorder], when set, tints the idle border to advertise the node's storage persistence;
     * hover/selection still take over the border so the interaction feedback is never lost.
     */
    private fun paintNodeBody(
        g2: Graphics2D,
        id: String,
        rect: Rectangle,
        severity: Severity?,
        accent: Color,
        volumeBorder: VolumeBorder? = null,
    ): Int {
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

        // Idle border reflects volume persistence; hover/selection override it with the accent so the
        // active node still reads as active.
        val idleVolume = volumeBorder?.takeIf { !isActive && !isHover }
        g2.stroke = when {
            isActive -> BasicStroke(JBUIScale.scale(2.2f))
            isHover -> BasicStroke(JBUIScale.scale(1.8f))
            idleVolume != null -> idleVolume.stroke()
            else -> BasicStroke(JBUIScale.scale(1.2f))
        }
        g2.color = when {
            isActive || isHover -> StackDoctorColors.SELECTED_BORDER
            idleVolume != null -> idleVolume.color
            else -> StackDoctorColors.NODE_BORDER
        }
        g2.drawRoundRect(rect.x, rect.y, rect.width, rect.height, arc, arc)

        return rect.x + accentWidth + JBUI.scale(10)
    }

    private fun paintServiceNode(g2: Graphics2D, node: ServiceMapNode, rect: Rectangle) {
        val svc = node.service
        val category = ServiceCategory.of(svc)
        val volumes = VolumeSummary.of(svc)
        val left = paintNodeBody(g2, node.id, rect, map.worstSeverity[node.id], category.accent, volumeBorderFor(volumes))
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

        paintServiceBadges(g2, node, rect, left, volumes)
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

    private fun paintServiceBadges(g2: Graphics2D, node: ServiceMapNode, rect: Rectangle, left: Int, volumes: VolumeSummary) {
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
        // Storage: persistent (named/bind) volumes first, then a caution flag for anonymous ones.
        if (volumes.persistentCount > 0) {
            parts += "▤ ${volumes.persistentCount} persistent" to StackDoctorColors.VOLUME_PERSISTENT
        }
        if (volumes.hasAnonymous) {
            parts += "${volumes.anonymous.size} unnamed" to StackDoctorColors.VOLUME_EPHEMERAL
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

    /**
     * Draws the badges left-to-right, wrapping onto a second row when the next one wouldn't fit
     * before the node's right edge — so no badge ever spills outside the card. A badge that is wider
     * than a full row on its own is clipped with an ellipsis; anything past the second row is dropped.
     */
    private fun paintBadges(g2: Graphics2D, parts: List<Pair<String, JBColor>>, rect: Rectangle, left: Int) {
        g2.font = JBUI.Fonts.smallFont()
        val fm = g2.fontMetrics
        val gap = JBUI.scale(10)
        val rightEdge = rect.x + rect.width - JBUI.scale(10)
        val rowY = intArrayOf(rect.y + JBUI.scale(54), rect.y + JBUI.scale(70))
        var row = 0
        var x = left
        for ((text, color) in parts) {
            val w = fm.stringWidth(text)
            // Wrap to the next row when this badge won't fit and we're not already at the row start.
            if (x > left && x + w > rightEdge) {
                row++
                if (row >= rowY.size) break
                x = left
            }
            g2.color = color
            val drawn = if (x + w > rightEdge) clip(text, fm, rightEdge - x) else text
            g2.drawString(drawn, x, rowY[row])
            x += fm.stringWidth(drawn) + gap
        }
    }

    private fun fillColors(severity: Severity?): Pair<Color, Color> = when (severity) {
        Severity.ERROR -> StackDoctorColors.ERROR_FILL_TOP to StackDoctorColors.ERROR_FILL_BOTTOM
        Severity.WARNING -> StackDoctorColors.WARNING_FILL_TOP to StackDoctorColors.WARNING_FILL_BOTTOM
        else -> StackDoctorColors.NODE_FILL_TOP to StackDoctorColors.NODE_FILL_BOTTOM
    }

    // ---- Volumes -----------------------------------------------------------------------------

    /**
     * The idle border a service node gets from its volumes: a solid green ring when it has any
     * persistent storage (named volume or host bind), else a dashed amber ring when it only has
     * anonymous (unnamed) volumes whose data is lost on recreate. Persistent wins so a service with
     * both still reads as "has real storage" — the "N unnamed" badge/tooltip flags the anonymous risk.
     */
    private fun volumeBorderFor(volumes: VolumeSummary): VolumeBorder? = when {
        volumes.hasPersistent -> VolumeBorder.PERSISTENT
        volumes.hasAnonymous -> VolumeBorder.EPHEMERAL
        else -> null
    }

    /** A node's storage-persistence border style. [color] is a JBColor, usable directly as g2.color. */
    private enum class VolumeBorder(val color: JBColor, private val width: Float, private val dashed: Boolean) {
        PERSISTENT(StackDoctorColors.VOLUME_PERSISTENT, 1.8f, false),
        EPHEMERAL(StackDoctorColors.VOLUME_EPHEMERAL, 1.6f, true);

        fun stroke(): BasicStroke = if (dashed) {
            BasicStroke(
                JBUIScale.scale(width), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                10f, floatArrayOf(JBUIScale.scale(5f), JBUIScale.scale(4f)), 0f,
            )
        } else {
            BasicStroke(JBUIScale.scale(width))
        }
    }

    /** Tooltip HTML: every attached volume as `source → target`, tagged with its kind and persistence. */
    private fun volumeTooltipHtml(volumes: VolumeSummary): String {
        val fg = hex(UIUtil.getLabelForeground())
        val muted = hex(UIUtil.getContextHelpForeground())
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:sans-serif;color:$fg;'>")
        sb.append("<div style='font-weight:bold;margin-bottom:3px;'>Volumes</div>")
        for (v in volumes.named) volumeRow(sb, v, "named · persistent", StackDoctorColors.VOLUME_PERSISTENT, muted)
        for (v in volumes.binds) volumeRow(sb, v, "bind · host path", StackDoctorColors.VOLUME_PERSISTENT, muted)
        for (v in volumes.anonymous) volumeRow(sb, v, "anonymous · ephemeral", StackDoctorColors.VOLUME_EPHEMERAL, muted)
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun volumeRow(sb: StringBuilder, v: VolumeMount, label: String, color: JBColor, muted: String) {
        sb.append("<div style='margin:1px 0;'>")
        sb.append("<span style='color:${hex(color)};'>▤</span> ")
        sb.append(esc(v.source ?: "(anonymous)")).append(" &#8594; ").append(esc(v.target ?: "?")).append(' ')
        sb.append("<span style='color:$muted;font-size:90%;'>").append(esc(label)).append("</span>")
        sb.append("</div>")
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

    // ---- File breakdown popup ---------------------------------------------------------------

    private fun showBreakdown(node: FileMapNode) {
        val rect = bounds[node.id] ?: return
        openPopup?.cancel()

        val pane = JEditorPane("text/html", fileBreakdownHtml(node)).apply {
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
            .setTitle("File: ${node.displayName}")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
        openPopup = popup
        popup.show(RelativePoint(this, Point(rect.x + rect.width + JBUI.scale(8), rect.y)))
    }

    // ---- Service edit form ------------------------------------------------------------------

    /** A field's editor widget, the component to focus, and a getter for its current text. */
    private class FieldEditor(val component: JComponent, val focus: JComponent, val read: () -> String)

    /**
     * Vertical form whose width always tracks its scroll viewport, so fields stretch to fit and never
     * overflow past the right edge (a plain JPanel keeps its content-driven preferred width and gets
     * clipped). Height stays content-driven so the form scrolls vertically.
     */
    private class FormPanel : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(64)
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    /**
     * Opens an editable form for a service node: a header of diagnostics / "add healthcheck", then a
     * row per present parameter. Saving writes only the changed fields back to the compose file via
     * [onApplyServiceEdits]; cancelling discards them. The popup is sticky (it ignores clicks outside
     * and focus loss) so a multi-field edit isn't lost to a stray click.
     */
    private fun showServiceForm(node: ServiceMapNode) {
        val rect = bounds[node.id] ?: return
        openPopup?.cancel()

        val muted = UIUtil.getContextHelpForeground()
        val fields = fieldsFor(node)
        val collectors = mutableListOf<Pair<ServiceField, () -> String>>()
        val presentKeys = fields.mapTo(mutableSetOf()) { it.key }
        val helpAreas = mutableListOf<JBTextArea>()
        var firstEditor: JComponent? = null

        val form = FormPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(6, 8)
        }

        serviceHeaderHtml(node)?.let { html ->
            form.add(JEditorPane("text/html", html).apply {
                isEditable = false
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyBottom(6)
                addHyperlinkListener { e ->
                    if (e.eventType == HyperlinkEvent.EventType.ACTIVATED && e.description == ADD_HEALTHCHECK_HREF) {
                        openPopup?.cancel()
                        onGenerateHealthcheck(node)
                    }
                }
            })
        }

        // Shown only while the service has no editable parameters; removed when the first is added.
        var placeholder: JBTextArea? = null
        if (fields.isEmpty()) {
            placeholder = helpComponent("This service declares no editable parameters. Use “Add field” to add one.", muted)
            helpAreas += placeholder
            form.add(placeholder)
        }

        for (field in fields) {
            val focus = addFieldRow(form, field, collectors, muted, helpAreas)
            if (firstEditor == null) firstEditor = focus
        }

        val scroll = JBScrollPane(form).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(JBUI.scale(400), JBUI.scale(380))
        }

        // Wrap each help line to the live viewport width (and re-wrap on resize), so long hints —
        // like the healthcheck example — wrap instead of bleeding past the popup's right edge.
        fun relayoutHelp() {
            val viewportWidth = scroll.viewport.width
            if (viewportWidth <= 0) return
            val w = (viewportWidth - JBUI.scale(16)).coerceAtLeast(JBUI.scale(120))
            for (area in helpAreas) {
                area.setSize(w, Short.MAX_VALUE.toInt())
                val size = Dimension(w, area.preferredSize.height)
                area.preferredSize = size
                area.maximumSize = size
            }
            form.revalidate()
            form.repaint()
        }
        scroll.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = relayoutHelp()
        })

        val addFieldButton = JButton("Add field", AllIcons.General.Add).apply {
            toolTipText = "Add a parameter this service doesn't declare yet (healthcheck, restart, expose, …)"
        }
        fun refreshAddState() {
            addFieldButton.isEnabled = ServiceFields.addableTemplates(presentKeys).isNotEmpty()
        }
        addFieldButton.addActionListener {
            val templates = ServiceFields.addableTemplates(presentKeys)
            if (templates.isEmpty()) return@addActionListener
            val labels = templates.map { it.label }
            JBPopupFactory.getInstance().createPopupChooserBuilder(labels)
                .setTitle("Add field")
                .setItemChosenCallback { chosen ->
                    val template = templates[labels.indexOf(chosen)]
                    presentKeys.add(template.key)
                    placeholder?.let { form.remove(it); placeholder = null }
                    val focus = addFieldRow(form, template, collectors, muted, helpAreas)
                    refreshAddState()
                    relayoutHelp()
                    focus?.let { f ->
                        SwingUtilities.invokeLater {
                            f.requestFocusInWindow()
                            (f.parent as? JComponent)?.let { row -> form.scrollRectToVisible(row.bounds) }
                        }
                    }
                }
                .createPopup()
                .showUnderneathOf(addFieldButton)
        }
        refreshAddState()

        val saveButton = JButton("Save")
        val cancelButton = JButton("Cancel")
        val content = JPanel(BorderLayout()).apply {
            add(scroll, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 6)
                add(addFieldButton, BorderLayout.WEST)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                    add(cancelButton)
                    add(saveButton)
                }, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, firstEditor ?: saveButton)
            .setTitle("Edit service: ${node.service.name}")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(false)
            .setCancelOnWindowDeactivation(false)
            .createPopup()
        saveButton.addActionListener {
            val edits = collectors.mapNotNull { (field, read) ->
                val value = read()
                if (normalize(field.kind, value) != normalize(field.kind, field.value)) {
                    ServiceFieldEdit(field.key, field.kind, value)
                } else null
            }
            popup.cancel()
            onApplyServiceEdits(node, edits)
        }
        cancelButton.addActionListener { popup.cancel() }

        openPopup = popup
        popup.show(RelativePoint(this, Point(rect.x + rect.width + JBUI.scale(8), rect.y)))
        // Now that the popup has a real width, wrap the help text to it.
        SwingUtilities.invokeLater { relayoutHelp() }
    }

    /**
     * Builds one labelled field row — bold label, an editor (or read-only value), then help text —
     * appends it to [form], and registers an editable field's collector. Returns the editor to focus,
     * or null for a read-only field. Used for both the initially-present fields and ones added later.
     */
    private fun addFieldRow(
        form: JComponent,
        field: ServiceField,
        collectors: MutableList<Pair<ServiceField, () -> String>>,
        muted: Color,
        helpAreas: MutableList<JBTextArea>,
    ): JComponent? {
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(9)
        }
        row.add(JBLabel(field.label).apply {
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
        })

        var focus: JComponent? = null
        if (field.editable) {
            val editor = buildEditor(field)
            editor.component.alignmentX = Component.LEFT_ALIGNMENT
            row.add(editor.component)
            focus = editor.focus
            collectors += field to editor.read
        } else {
            row.add(JBLabel(field.value.replace("\n", ", ")).apply { alignmentX = Component.LEFT_ALIGNMENT })
        }

        val help = field.note?.let { "${field.explanation}  $it" } ?: field.explanation
        val helpArea = helpComponent(help, muted)
        helpAreas += helpArea
        row.add(helpArea)
        form.add(row)
        return focus
    }

    /** Header shown above the form: this service's findings, plus an "add healthcheck" link if needed. */
    private fun serviceHeaderHtml(node: ServiceMapNode): String? {
        val svc = node.service
        val diags = node.analysis.diagnostics.filter { it.service == svc.name }
        val needsHealthcheck = !svc.hasHealthcheck || svc.healthcheckDisabled
        if (diags.isEmpty() && !needsHealthcheck) return null

        val fg = hex(UIUtil.getLabelForeground())
        val muted = hex(UIUtil.getContextHelpForeground())
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:sans-serif;color:$fg;'><div style='width:360px;'>")
        ComposeHtml.appendDiagnostics(sb, diags, muted)
        if (needsHealthcheck) {
            val link = hex(StackDoctorColors.SELECTED_BORDER)
            sb.append("<div><a href='$ADD_HEALTHCHECK_HREF' style='color:$link;text-decoration:none;'>")
            sb.append("&#9658; Add a generated healthcheck</a></div>")
        }
        sb.append("</div></body></html>")
        return sb.toString()
    }

    private fun buildEditor(field: ServiceField): FieldEditor = when (field.kind) {
        FieldKind.SCALAR -> if (field.key == "restart") {
            val combo = ComboBox(arrayOf("", "no", "always", "on-failure", "unless-stopped")).apply {
                isEditable = true
                selectedItem = field.value
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
            FieldEditor(combo, combo) { (combo.editor.item ?: "").toString() }
        } else {
            val tf = JBTextField(field.value).apply { maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height) }
            FieldEditor(tf, tf) { tf.text }
        }
        FieldKind.LIST, FieldKind.ENV, FieldKind.MAP -> {
            val area = JBTextArea(field.value).apply {
                rows = field.value.lines().size.coerceIn(2, 8)
                lineWrap = false
            }
            val h = JBUI.scale(area.rows * 20 + 8)
            val sp = JBScrollPane(area).apply {
                preferredSize = Dimension(JBUI.scale(220), h)
                maximumSize = Dimension(Int.MAX_VALUE, h)
            }
            FieldEditor(sp, area) { area.text }
        }
    }

    /**
     * Muted, wrapping help text. A line-wrapping JBTextArea (not a fixed-width HTML label), so the
     * text wraps to the popup's actual width — set by `relayoutHelp` from the live viewport and
     * updated on resize — instead of bleeding past the right edge the way a fixed-width label did.
     */
    private fun helpComponent(text: String, color: Color): JBTextArea =
        JBTextArea(text).apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
            foreground = color
            font = JBUI.Fonts.smallFont()
            alignmentX = Component.LEFT_ALIGNMENT
        }

    /** Normalised form of a field value for change detection (trim scalars; drop blank list lines). */
    private fun normalize(kind: FieldKind, value: String): String = when (kind) {
        FieldKind.SCALAR -> value.trim()
        FieldKind.LIST, FieldKind.ENV, FieldKind.MAP ->
            value.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun fileBreakdownHtml(node: FileMapNode): String {
        val fg = hex(UIUtil.getLabelForeground())
        val muted = hex(UIUtil.getContextHelpForeground())
        val accent = hex(StackDoctorColors.ACCENT_FILE)
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:sans-serif;color:$fg;'>")

        // Project-wide findings for this file (those not tied to a single service).
        val fileDiags = node.analysis.diagnostics.filter { it.service == null }
        ComposeHtml.appendDiagnostics(sb, fileDiags, muted)

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
        sb.append("<div style='color:$muted;font-size:88%;margin-top:8px;'>Click a service node to edit it · double-click to open the file.</div>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun hex(c: Color): String = ComposeHtml.hex(c)

    private fun esc(s: String): String = ComposeHtml.esc(s)

    private companion object {
        const val ADD_HEALTHCHECK_HREF = "stackdoctor:add-healthcheck"
    }
}
