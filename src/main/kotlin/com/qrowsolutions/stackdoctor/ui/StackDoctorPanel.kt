package com.qrowsolutions.stackdoctor.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.qrowsolutions.stackdoctor.parser.ComposePsi
import org.jetbrains.yaml.psi.YAMLFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.qrowsolutions.stackdoctor.analysis.ComposeAnalysis
import com.qrowsolutions.stackdoctor.analysis.ComposeScanner
import com.qrowsolutions.stackdoctor.analysis.FileMapNode
import com.qrowsolutions.stackdoctor.analysis.HealthcheckGenerator
import com.qrowsolutions.stackdoctor.analysis.MapNode
import com.qrowsolutions.stackdoctor.analysis.ServiceMap
import com.qrowsolutions.stackdoctor.analysis.ServiceMapNode
import com.qrowsolutions.stackdoctor.diagnostics.Diagnostic
import com.qrowsolutions.stackdoctor.diagnostics.Severity
import com.qrowsolutions.stackdoctor.inspection.HealthcheckWriter
import com.qrowsolutions.stackdoctor.inspection.ServiceFieldWriter
import com.qrowsolutions.stackdoctor.parser.ServiceField
import com.qrowsolutions.stackdoctor.parser.ServiceFieldEdit
import com.qrowsolutions.stackdoctor.parser.ServiceFields
import org.jetbrains.yaml.psi.YAMLMapping
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Root component of the Stack Doctor tool window. Renders every compose file in the project as a
 * root node in one map — each file's services branch off to the right — and lists the doctor's
 * diagnostics across all of them. Selection is linked both ways between the map and the list.
 */
class StackDoctorPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val summaryLabel = JBLabel()
    private val graphHost = JPanel(BorderLayout())
    private val graphScroll = JBScrollPane(graphHost)

    private val diagModel = DefaultListModel<MapDiagnostic>()
    private val diagList = JBList(diagModel)

    // Collapsible diagnostics block: a clickable header over the list, inside the top/bottom split.
    private val diagScroll = JBScrollPane(diagList)
    private val diagChevron = JBLabel(AllIcons.General.ChevronDown)
    private val diagHeaderLabel = JBLabel("Warnings & errors")
    private lateinit var diagHeader: JPanel
    private val splitter = OnePixelSplitter(true, 0.62f)
    private var diagCollapsed = false
    private var savedProportion = 0.62f

    private var graphPanel: ServiceMapPanel? = null
    private var currentMap: ServiceMap? = null

    /** A diagnostic plus the map node it belongs to, so list selection can highlight the right node. */
    private data class MapDiagnostic(
        val analysis: ComposeAnalysis,
        val diagnostic: Diagnostic,
        val node: MapNode?,
        val fileLabel: String,
    )

    init {
        StackDoctorService.getInstance(project).panel = this
        // Keep the map area non-opaque all the way down the scroll chain so a user-set IDE
        // background image shows through it. Any opaque component here repaints a solid fill that
        // covers the image and flashes on activation. See memory: ide-background-image.
        isOpaque = false
        graphHost.isOpaque = false
        graphScroll.isOpaque = false
        graphScroll.viewport.isOpaque = false
        add(buildToolbar(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)
        configureDiagList()
        refresh()
    }

    private fun buildToolbar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4)))
        bar.isOpaque = false
        bar.add(JBLabel("Compose map:"))

        val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "Re-scan project and re-run diagnostics"
            addActionListener { refresh() }
        }
        bar.add(refreshButton)

        val legendButton = JButton("Legend", AllIcons.Actions.Help).apply {
            toolTipText = "What the symbols, lines and colours on the map mean"
            addActionListener { showLegend(this) }
        }
        bar.add(legendButton)

        val healthcheckButton = JButton("Add healthchecks", AllIcons.General.InspectionsEye).apply {
            toolTipText = "Generate a context-aware healthcheck for each service that needs one"
            addActionListener { generateHealthchecks() }
        }
        bar.add(healthcheckButton)

        val mergeButton = JButton("Merged preview", AllIcons.Actions.Diff).apply {
            toolTipText = "Preview two compose files merged into one (e.g. a base file + its override)"
            addActionListener { showMergedPreview() }
        }
        bar.add(mergeButton)

        val resetLayoutButton = JButton("Reset layout", AllIcons.General.Reset).apply {
            toolTipText = "Clear any dragged node positions and restore the automatic layout"
            addActionListener { resetLayout() }
        }
        bar.add(resetLayoutButton)

        bar.add(summaryLabel)
        return bar
    }

    /** Opens the two-file merge preview, or explains when there aren't two compose files to merge. */
    private fun showMergedPreview() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val files = ComposeScanner.findComposeFiles(project)
            SwingUtilities.invokeLater {
                if (project.isDisposed) return@invokeLater
                if (files.size < 2) {
                    Messages.showInfoMessage(
                        project,
                        "Need at least two compose files in the project to merge.",
                        "Merged Preview",
                    )
                } else {
                    MergePreviewDialog(project, files).show()
                }
            }
        }
    }

    /** Discards hand-dragged node positions and rebuilds the map with the automatic layout. */
    private fun resetLayout() {
        StackDoctorService.getInstance(project).nodePositions.clear()
        refresh()
    }

    private fun showLegend(anchor: Component) {
        val pane = JEditorPane("text/html", MapLegend.html()).apply {
            isEditable = false
            isOpaque = true
            background = UIUtil.getToolTipBackground()
            border = JBUI.Borders.empty(4, 8)
            caretPosition = 0
        }
        val scroll = JBScrollPane(pane).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(JBUI.scale(440), JBUI.scale(420))
        }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scroll, pane)
            .setTitle("Map legend")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
        popup.show(RelativePoint(anchor, Point(0, anchor.height)))
    }

    /** Generates context-aware healthchecks for every service flagged as depended-on but lacking one. */
    private fun generateHealthchecks() {
        val map = currentMap ?: return
        val targets = LinkedHashMap<ComposeAnalysis, MutableList<Pair<String, HealthcheckGenerator.Healthcheck>>>()
        val skipped = mutableListOf<String>()

        for (analysis in map.analyses) {
            for (d in analysis.diagnostics) {
                if (d.id != "missing-healthcheck") continue
                val name = d.service ?: continue
                val svc = analysis.project.service(name) ?: continue
                val hc = HealthcheckGenerator.generate(svc)
                if (hc == null) {
                    skipped += "${analysis.project.fileName} · $name"
                } else {
                    targets.getOrPut(analysis) { mutableListOf() }.add(name to hc)
                }
            }
        }

        if (targets.isEmpty()) {
            val msg = if (skipped.isEmpty()) {
                "No services need a healthcheck — every service that others depend on already has one."
            } else {
                "Couldn't infer a healthcheck for:\n  ${skipped.joinToString("\n  ")}\n\nAdd one manually for these."
            }
            Messages.showInfoMessage(project, msg, "Add Healthchecks")
            return
        }

        val preview = buildString {
            append("Add a generated healthcheck to ")
            append(targets.values.sumOf { it.size }).append(" service(s)?\n")
            for ((analysis, list) in targets) {
                append("\n").append(analysis.project.fileName).append(":")
                for ((name, hc) in list) append("\n  • ").append(name).append("  — ").append(hc.rationale)
            }
            if (skipped.isNotEmpty()) append("\n\nSkipped (couldn't infer): ").append(skipped.joinToString(", "))
        }
        val choice = Messages.showYesNoDialog(project, preview, "Add Healthchecks", "Add", "Cancel", null)
        if (choice != Messages.YES) return

        for ((analysis, list) in targets) applyHealthchecks(analysis, list)
        refresh()
    }

    /** Generates and writes a healthcheck for a single service (triggered from its breakdown popup). */
    private fun generateHealthcheckFor(node: ServiceMapNode) {
        val svc = node.service
        val hc = HealthcheckGenerator.generate(svc)
        if (hc == null) {
            Messages.showInfoMessage(
                project,
                "Couldn't infer a healthcheck for '${svc.name}' — its image/name and ports don't match a " +
                    "known probe. Add one manually.",
                "Add Healthcheck",
            )
            return
        }
        applyHealthchecks(node.analysis, listOf(svc.name to hc))
        refresh()
    }

    /** Reads a service's currently-present parameters from its compose file as editable form fields. */
    private fun readServiceFields(node: ServiceMapNode): List<ServiceField> {
        val vf = LocalFileSystem.getInstance().findFileByPath(node.analysis.project.filePath) ?: return emptyList()
        return ApplicationManager.getApplication().runReadAction(Computable {
            if (project.isDisposed || !vf.isValid) return@Computable emptyList()
            val yamlFile = PsiManager.getInstance(project).findFile(vf) as? YAMLFile ?: return@Computable emptyList()
            val body = ComposePsi.serviceKeyValue(yamlFile, node.service.name)?.value as? YAMLMapping
                ?: return@Computable emptyList()
            ServiceFields.read(body)
        })
    }

    /** Writes the form's changed fields back into the service's compose file in one undoable edit. */
    private fun applyServiceEdits(node: ServiceMapNode, edits: List<ServiceFieldEdit>) {
        if (edits.isEmpty()) return
        val vf = LocalFileSystem.getInstance().findFileByPath(node.analysis.project.filePath) ?: return
        val yamlFile = PsiManager.getInstance(project).findFile(vf) as? YAMLFile ?: return
        WriteCommandAction.runWriteCommandAction(project, "Edit Service '${node.service.name}'", null, {
            ServiceFieldWriter.apply(project, yamlFile, node.service.name, edits)
        })
        refresh()
    }

    /** Writes the given healthchecks into the analysis's compose file in one undoable edit. */
    private fun applyHealthchecks(
        analysis: ComposeAnalysis,
        list: List<Pair<String, HealthcheckGenerator.Healthcheck>>,
    ) {
        val vf = LocalFileSystem.getInstance().findFileByPath(analysis.project.filePath) ?: return
        val yamlFile = PsiManager.getInstance(project).findFile(vf) as? YAMLFile ?: return
        WriteCommandAction.runWriteCommandAction(project, "Add Healthchecks", null, {
            HealthcheckWriter.apply(project, yamlFile, list)
        })
    }

    private fun buildCenter(): OnePixelSplitter {
        splitter.firstComponent = graphScroll
        splitter.secondComponent = buildDiagnosticsPanel()
        // While collapsed, keep the bottom pinned to just the header as the window is resized.
        splitter.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (diagCollapsed) pinCollapsed()
            }
        })
        return splitter
    }

    /** The diagnostics list under a clickable header that collapses it down to just the header. */
    private fun buildDiagnosticsPanel(): JPanel {
        diagHeader = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3))).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Show or hide the warnings & errors list"
            add(diagChevron)
            add(diagHeaderLabel)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = toggleDiagnostics()
            })
        }
        return JPanel(BorderLayout()).apply {
            add(diagHeader, BorderLayout.NORTH)
            add(diagScroll, BorderLayout.CENTER)
        }
    }

    private fun toggleDiagnostics() {
        diagCollapsed = !diagCollapsed
        diagChevron.icon = if (diagCollapsed) AllIcons.General.ChevronRight else AllIcons.General.ChevronDown
        if (diagCollapsed) {
            savedProportion = splitter.proportion
            pinCollapsed()
        } else {
            splitter.proportion = savedProportion
        }
    }

    /** Pins the splitter so the bottom region shows only the diagnostics header. */
    private fun pinCollapsed() {
        val h = splitter.height
        if (h <= 0) {
            SwingUtilities.invokeLater { if (diagCollapsed) pinCollapsed() }
            return
        }
        val headerH = diagHeader.preferredSize.height
        splitter.proportion = (1f - headerH.toFloat() / h).coerceIn(0.05f, 0.95f)
    }

    private fun diagHeaderText(errors: Int, warnings: Int): String {
        if (errors == 0 && warnings == 0) return "Warnings & errors"
        val parts = mutableListOf<String>()
        if (errors > 0) parts += "$errors error${if (errors == 1) "" else "s"}"
        if (warnings > 0) parts += "$warnings warning${if (warnings == 1) "" else "s"}"
        return "Warnings & errors · ${parts.joinToString(" · ")}"
    }

    private fun configureDiagList() {
        diagList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        diagList.cellRenderer = DiagnosticRenderer()
        diagList.emptyText.text = "No issues found"
        diagList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            graphPanel?.select(diagList.selectedValue?.node)
        }
    }

    /** Re-scan the project for compose files, analyse them all, and rebuild the map. */
    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val files = ComposeScanner.findComposeFiles(project)
            val analyses = files.mapNotNull { ComposeScanner.analyze(project, it) }
            val map = if (analyses.isEmpty()) null else ServiceMap(analyses, project.basePath)
            SwingUtilities.invokeLater { render(map) }
        }
    }

    private fun render(map: ServiceMap?) {
        currentMap = map
        if (map == null) {
            showEmpty()
            return
        }

        val panel = ServiceMapPanel(
            map,
            onSelect = { node -> selectFirstDiagnosticFor(node) },
            onActivate = { node -> openInEditor(node) },
            onGenerateHealthcheck = { node -> generateHealthcheckFor(node) },
            fieldsFor = { node -> readServiceFields(node) },
            onApplyServiceEdits = { node, edits -> applyServiceEdits(node, edits) },
            positions = StackDoctorService.getInstance(project).nodePositions,
        )
        graphPanel = panel
        graphHost.removeAll()
        graphHost.add(panel, BorderLayout.CENTER)
        graphHost.revalidate()
        graphHost.repaint()

        // Diagnostics across every file, with the file they came from.
        diagModel.clear()
        for ((idx, analysis) in map.analyses.withIndex()) {
            val label = fileDisplayName(map, idx)
            analysis.diagnostics.forEach { d ->
                val node = map.node(if (d.service != null) "$idx/${d.service}" else "file/$idx")
                diagModel.addElement(MapDiagnostic(analysis, d, node, label))
            }
        }

        // Summary.
        val services = map.analyses.sumOf { it.project.services.size }
        val errors = map.analyses.sumOf { a -> a.diagnostics.count { it.severity == Severity.ERROR } }
        val warnings = map.analyses.sumOf { a -> a.diagnostics.count { it.severity == Severity.WARNING } }
        val fileWord = if (map.analyses.size == 1) "file" else "files"
        summaryLabel.text = "  ${map.analyses.size} $fileWord · $services services · $errors errors · $warnings warnings"
        diagHeaderLabel.text = diagHeaderText(errors, warnings)
    }

    private fun fileDisplayName(map: ServiceMap, idx: Int): String =
        (map.node("file/$idx") as? FileMapNode)?.displayName ?: map.analyses[idx].project.fileName

    private fun showEmpty() {
        currentMap = null
        graphPanel = null
        graphHost.removeAll()
        val placeholder = JBLabel("No docker-compose files found in this project.").apply {
            horizontalAlignment = JBLabel.CENTER
        }
        graphHost.add(placeholder, BorderLayout.CENTER)
        graphHost.revalidate()
        graphHost.repaint()
        diagModel.clear()
        summaryLabel.text = ""
        diagHeaderLabel.text = diagHeaderText(0, 0)
    }

    private fun selectFirstDiagnosticFor(node: MapNode?) {
        if (node == null) {
            diagList.clearSelection()
            return
        }
        for (i in 0 until diagModel.size()) {
            if (diagModel.get(i).node?.id == node.id) {
                diagList.selectedIndex = i
                diagList.ensureIndexIsVisible(i)
                return
            }
        }
        diagList.clearSelection()
    }

    /** Opens the compose file behind a node in the editor, scrolled to the service when applicable. */
    private fun openInEditor(node: MapNode) {
        val analysis = when (node) {
            is ServiceMapNode -> node.analysis
            is FileMapNode -> node.analysis
        }
        val serviceName = (node as? ServiceMapNode)?.service?.name
        val file = LocalFileSystem.getInstance().findFileByPath(analysis.project.filePath) ?: return
        val offset = ApplicationManager.getApplication().runReadAction(Computable {
            if (project.isDisposed || !file.isValid) return@Computable null
            if (serviceName == null) return@Computable 0
            val yamlFile = PsiManager.getInstance(project).findFile(file) as? YAMLFile ?: return@Computable null
            ComposePsi.serviceKeyValue(yamlFile, serviceName)?.textOffset
        })
        OpenFileDescriptor(project, file, offset ?: 0).navigate(true)
    }

    private class DiagnosticRenderer : ColoredListCellRenderer<MapDiagnostic>() {
        override fun customizeCellRenderer(
            list: JList<out MapDiagnostic>,
            value: MapDiagnostic?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            value ?: return
            val d = value.diagnostic
            icon = when (d.severity) {
                Severity.ERROR -> AllIcons.General.BalloonError
                Severity.WARNING -> AllIcons.General.BalloonWarning
                Severity.INFO -> AllIcons.General.BalloonInformation
            }
            append("${value.fileLabel}  ", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            d.service?.let { append("$it  ", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            append(d.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            d.hint?.let { append("   — $it", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES) }
            toolTipText = "<html><body style='width:320px'>${d.detail}</body></html>"
        }
    }
}
