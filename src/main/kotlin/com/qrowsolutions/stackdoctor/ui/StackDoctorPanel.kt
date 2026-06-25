package com.qrowsolutions.stackdoctor.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.qrowsolutions.stackdoctor.analysis.ComposeAnalysis
import com.qrowsolutions.stackdoctor.analysis.ComposeScanner
import com.qrowsolutions.stackdoctor.diagnostics.Diagnostic
import com.qrowsolutions.stackdoctor.diagnostics.Severity
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Root component of the Stack Doctor tool window. Lets the user pick a compose file, renders its
 * service dependency graph, and lists the doctor's diagnostics. Selection is linked both ways
 * between the graph and the list.
 */
class StackDoctorPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val fileCombo = ComboBox<VirtualFile>()
    private val summaryLabel = JBLabel()
    private val graphHost = JPanel(BorderLayout())
    private val graphScroll = JBScrollPane(graphHost)

    private val diagModel = DefaultListModel<Diagnostic>()
    private val diagList = JBList(diagModel)

    private var graphPanel: ServiceGraphPanel? = null
    private var currentAnalysis: ComposeAnalysis? = null
    private var suppressComboEvent = false

    init {
        StackDoctorService.getInstance(project).panel = this
        add(buildToolbar(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)
        configureDiagList()
        refresh()
    }

    private fun buildToolbar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4)))
        bar.add(JBLabel("Compose file:"))
        fileCombo.renderer = composeFileRenderer()
        fileCombo.addActionListener {
            if (!suppressComboEvent) (fileCombo.selectedItem as? VirtualFile)?.let { analyzeAndShow(it) }
        }
        bar.add(fileCombo)

        val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "Re-scan project and re-run diagnostics"
            addActionListener { refresh() }
        }
        bar.add(refreshButton)
        bar.add(summaryLabel)
        return bar
    }

    private fun buildCenter(): OnePixelSplitter {
        val splitter = OnePixelSplitter(true, 0.62f)
        splitter.firstComponent = graphScroll
        splitter.secondComponent = JBScrollPane(diagList)
        return splitter
    }

    private fun configureDiagList() {
        diagList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        diagList.cellRenderer = DiagnosticRenderer()
        diagList.emptyText.text = "No issues found"
        diagList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            graphPanel?.select(diagList.selectedValue?.service)
        }
    }

    /** Re-scan the project for compose files and refresh the dropdown, keeping the current selection if possible. */
    fun refresh() {
        val previouslySelected = fileCombo.selectedItem as? VirtualFile
        ApplicationManager.getApplication().executeOnPooledThread {
            val files = ComposeScanner.findComposeFiles(project)
            SwingUtilities.invokeLater {
                suppressComboEvent = true
                fileCombo.model = DefaultComboBoxModel(files.toTypedArray())
                suppressComboEvent = false
                val toSelect = previouslySelected?.takeIf { it in files } ?: files.firstOrNull()
                if (toSelect != null) {
                    fileCombo.selectedItem = toSelect
                    analyzeAndShow(toSelect)
                } else {
                    showEmpty()
                }
            }
        }
    }

    private fun analyzeAndShow(file: VirtualFile) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val analysis = ComposeScanner.analyze(project, file)
            SwingUtilities.invokeLater { render(analysis) }
        }
    }

    private fun render(analysis: ComposeAnalysis?) {
        currentAnalysis = analysis
        if (analysis == null) {
            showEmpty()
            return
        }
        // Graph.
        val panel = ServiceGraphPanel(analysis.graph, analysis.diagnostics) { service ->
            selectFirstDiagnosticFor(service)
        }
        graphPanel = panel
        graphHost.removeAll()
        graphHost.add(panel, BorderLayout.CENTER)
        graphHost.revalidate()
        graphHost.repaint()

        // Diagnostics.
        diagModel.clear()
        analysis.diagnostics.forEach { diagModel.addElement(it) }

        // Summary.
        val errors = analysis.diagnostics.count { it.severity == Severity.ERROR }
        val warnings = analysis.diagnostics.count { it.severity == Severity.WARNING }
        summaryLabel.text = "  ${analysis.project.services.size} services · $errors errors · $warnings warnings"
    }

    private fun showEmpty() {
        currentAnalysis = null
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
    }

    private fun selectFirstDiagnosticFor(service: String?) {
        if (service == null) {
            diagList.clearSelection()
            return
        }
        for (i in 0 until diagModel.size()) {
            if (diagModel.get(i).service == service) {
                diagList.selectedIndex = i
                diagList.ensureIndexIsVisible(i)
                return
            }
        }
        diagList.clearSelection()
    }

    /** Opens the compose file in the editor; double-click on a diagnostic could call this later. */
    @Suppress("unused")
    private fun openFileInEditor() {
        currentAnalysis ?: return
        (fileCombo.selectedItem as? VirtualFile)?.let {
            FileEditorManager.getInstance(project).openFile(it, true)
        }
    }

    private fun composeFileRenderer() = object : ColoredListCellRenderer<VirtualFile>() {
        override fun customizeCellRenderer(
            list: JList<out VirtualFile>,
            value: VirtualFile?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            value ?: return
            val base = project.basePath
            val rel = if (base != null && value.path.startsWith(base)) {
                value.path.removePrefix(base).trimStart('/', '\\').ifEmpty { value.name }
            } else {
                value.path
            }
            append(rel)
        }
    }

    private class DiagnosticRenderer : ColoredListCellRenderer<Diagnostic>() {
        override fun customizeCellRenderer(
            list: JList<out Diagnostic>,
            value: Diagnostic?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            value ?: return
            icon = when (value.severity) {
                Severity.ERROR -> AllIcons.General.BalloonError
                Severity.WARNING -> AllIcons.General.BalloonWarning
                Severity.INFO -> AllIcons.General.BalloonInformation
            }
            value.service?.let {
                append("$it  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            append(value.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            value.hint?.let { append("   — $it", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES) }
            toolTipText = "<html><body style='width:320px'>${value.detail}</body></html>"
        }
    }
}
