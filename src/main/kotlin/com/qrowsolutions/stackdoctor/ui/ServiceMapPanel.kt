package com.qrowsolutions.stackdoctor.ui

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
import com.qrowsolutions.stackdoctor.parser.FieldKind
import com.qrowsolutions.stackdoctor.parser.ServiceField
import com.qrowsolutions.stackdoctor.parser.ServiceFieldEdit
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

        if (fields.isEmpty()) {
            form.add(mutedLabel("This service declares no editable parameters.", muted))
        }

        for (field in fields) {
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

            if (field.editable) {
                val editor = buildEditor(field)
                editor.component.alignmentX = Component.LEFT_ALIGNMENT
                row.add(editor.component)
                if (firstEditor == null) firstEditor = editor.focus
                collectors += field to editor.read
            } else {
                row.add(JBLabel(field.value.replace("\n", ", ")).apply { alignmentX = Component.LEFT_ALIGNMENT })
            }

            val help = field.note?.let { "${field.explanation}  $it" } ?: field.explanation
            row.add(mutedLabel(help, muted))
            row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
            form.add(row)
        }

        val scroll = JBScrollPane(form).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(JBUI.scale(400), JBUI.scale(380))
        }

        val saveButton = JButton("Save")
        val cancelButton = JButton("Cancel")
        val content = JPanel(BorderLayout()).apply {
            add(scroll, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), JBUI.scale(4))).apply {
                add(cancelButton)
                add(saveButton)
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

    private fun mutedLabel(text: String, color: Color): JBLabel =
        JBLabel("<html><div style='width:340px;'>${esc(text)}</div></html>").apply {
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
