package com.qrowsolutions.stackdoctor.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.qrowsolutions.stackdoctor.analysis.ComposeMerge
import com.qrowsolutions.stackdoctor.analysis.ComposeScanner
import com.qrowsolutions.stackdoctor.analysis.MergeResult
import com.qrowsolutions.stackdoctor.diagnostics.Diagnostic
import com.qrowsolutions.stackdoctor.diagnostics.StackDoctor
import com.qrowsolutions.stackdoctor.model.ComposeProject
import com.qrowsolutions.stackdoctor.model.ComposeService
import com.qrowsolutions.stackdoctor.parser.ComposeParser
import org.jetbrains.yaml.psi.YAMLFile
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.io.File
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel

/**
 * A read-only preview of what `docker compose` would actually run after overlaying one compose
 * file on top of another (e.g. a base file plus a `*.override.yml`). The user picks the two files;
 * the merge is computed on the model ([ComposeMerge]) and the full doctor suite is re-run on the
 * merged result, so the preview also surfaces problems that only exist after the merge.
 *
 * It is a preview only — services aren't editable here because a field can come from either file.
 */
class MergePreviewDialog(
    private val project: Project,
    private val files: List<VirtualFile>,
) : DialogWrapper(project) {

    private val baseCombo = ComboBox(files.toTypedArray())
    private val overlayCombo = ComboBox(files.toTypedArray())
    private val previewPane = JEditorPane("text/html", "").apply {
        isEditable = false
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(6, 8)
    }

    init {
        title = "Merged Compose Preview"
        isModal = false
        // ColoredListCellRenderer (as used elsewhere in the plugin) instead of the convenience
        // SimpleListCellRenderer.create(...) factories — both of those are deprecated / scheduled
        // for removal on 262 and would dirty the verifier report.
        val pathRenderer = object : ColoredListCellRenderer<VirtualFile>() {
            override fun customizeCellRenderer(
                list: JList<out VirtualFile>,
                value: VirtualFile?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                append(value?.let { relativePath(it) } ?: "")
            }
        }
        baseCombo.renderer = pathRenderer
        overlayCombo.renderer = pathRenderer

        val defaultBase = files.firstOrNull()
        baseCombo.selectedItem = defaultBase
        overlayCombo.selectedItem =
            defaultBase?.let { ComposeScanner.defaultOverlayFor(it, files) } ?: files.getOrNull(1)

        baseCombo.addActionListener { render() }
        overlayCombo.addActionListener { render() }

        init()
        setOKButtonText("Close")
        render()
    }

    /** Close is the only action — this is a viewer, there is nothing to apply. */
    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val pickers = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(2, 4)
            anchor = GridBagConstraints.WEST
        }
        gbc.gridx = 0; gbc.gridy = 0
        pickers.add(JBLabel("Base file:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        pickers.add(baseCombo, gbc)
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        pickers.add(JBLabel("Overlay (merged on top):"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        pickers.add(overlayCombo, gbc)

        val previewScroll = JBScrollPane(previewPane).apply {
            border = JBUI.Borders.customLine(StackDoctorColors.NODE_BORDER, 1)
            preferredSize = Dimension(JBUI.scale(660), JBUI.scale(470))
        }

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(pickers, BorderLayout.NORTH)
            add(previewScroll, BorderLayout.CENTER)
        }
    }

    private fun render() {
        val base = baseCombo.selectedItem as? VirtualFile
        val overlay = overlayCombo.selectedItem as? VirtualFile
        previewPane.text = when {
            base == null || overlay == null -> wrap("<p>Select a base and an overlay file.</p>")
            base == overlay -> wrap("<p>Pick two <b>different</b> files to see their merged result.</p>")
            else -> buildPreview(base, overlay) ?: wrap(
                "<p>Couldn't parse one of the selected files as a compose file. " +
                    "Pick two valid compose files.</p>",
            )
        }
        previewPane.caretPosition = 0
    }

    /** Parses and merges the two files, runs the doctor on the result, and renders it. */
    private fun buildPreview(base: VirtualFile, overlay: VirtualFile): String? {
        val baseProject = parse(base) ?: return null
        val overlayProject = parse(overlay) ?: return null
        val result = ComposeMerge.merge(baseProject, listOf(overlayProject))
        val baseDir = base.parent?.path?.let { File(it) }
        val diagnostics = StackDoctor.run(result.merged, baseDir)
        return renderHtml(base, overlay, result, diagnostics)
    }

    private fun parse(vf: VirtualFile): ComposeProject? =
        ApplicationManager.getApplication().runReadAction(Computable {
            if (project.isDisposed || !vf.isValid) return@Computable null
            val yamlFile = PsiManager.getInstance(project).findFile(vf) as? YAMLFile ?: return@Computable null
            ComposeParser.parse(yamlFile)
        })

    // ---- HTML rendering ---------------------------------------------------------------------

    private fun renderHtml(
        base: VirtualFile,
        overlay: VirtualFile,
        result: MergeResult,
        diagnostics: List<Diagnostic>,
    ): String {
        val fg = ComposeHtml.hex(UIUtil.getLabelForeground())
        val muted = ComposeHtml.hex(UIUtil.getContextHelpForeground())
        val accent = ComposeHtml.hex(StackDoctorColors.ACCENT_FILE)

        val sb = StringBuilder()
        sb.append("<html><body style='font-family:sans-serif;color:$fg;'>")
        sb.append("<div style='color:$accent;font-weight:bold;'>Merged result</div>")
        sb.append("<div style='color:$muted;font-size:90%;margin-bottom:8px;'>")
        sb.append(ComposeHtml.esc(relativePath(base))).append("  +  ").append(ComposeHtml.esc(relativePath(overlay)))
        sb.append("  —  the effective configuration <code>docker compose</code> would run")
        sb.append("</div>")

        if (diagnostics.isEmpty()) {
            sb.append("<div style='color:${ComposeHtml.hex(StackDoctorColors.OK_TEXT)};margin-bottom:8px;'>")
            sb.append("&#10003; No issues in the merged stack</div>")
        } else {
            ComposeHtml.appendDiagnostics(sb, diagnostics, muted)
        }

        sb.append("<div style='color:$accent;font-weight:bold;margin-bottom:2px;'>Services</div>")
        for (svc in result.merged.services) {
            appendService(sb, svc, result.overriddenKeys[svc.name].orEmpty(), muted)
        }

        sb.append("<div style='color:$muted;font-size:85%;margin-top:10px;'>")
        sb.append("Values contributed by the overlay are highlighted. Read-only preview — edit the files directly.")
        sb.append("</div></body></html>")
        return sb.toString()
    }

    private fun appendService(sb: StringBuilder, svc: ComposeService, overridden: Set<String>, muted: String) {
        val category = ServiceCategory.of(svc)
        sb.append("<div style='margin:7px 0 1px 0;'>")
        sb.append("<span style='color:${ComposeHtml.hex(category.accent)};font-weight:bold;'>")
        sb.append(ComposeHtml.esc(category.glyph)).append(' ').append(ComposeHtml.esc(svc.name)).append("</span>")
        if (MergeResult.NEW_SERVICE in overridden) {
            sb.append(" <span style='color:${ComposeHtml.hex(StackDoctorColors.OK_TEXT)};font-size:85%;'>from overlay</span>")
        }
        sb.append("</div>")

        sb.append("<table cellpadding='0' cellspacing='0' style='margin:0 0 2px 14px;'>")
        scalarRow(sb, "image", svc.image, overridden, muted)
        listRow(sb, "ports", svc.ports.map { it.raw }, overridden, muted)
        listRow(sb, "expose", svc.expose, overridden, muted)
        listRow(sb, "depends_on", svc.dependsOn, overridden, muted)
        listRow(sb, "networks", svc.networks.toList(), overridden, muted)
        listRow(sb, "volumes", svc.volumes.map { it.raw }, overridden, muted)
        listRow(sb, "env_file", svc.envFiles, overridden, muted)
        listRow(sb, "environment", svc.environmentKeys.toList(), overridden, muted)
        if (svc.hasHealthcheck) {
            row(sb, "healthcheck", if (svc.healthcheckDisabled) "disabled" else "configured", "healthcheck" in overridden, muted)
        }
        scalarRow(sb, "restart", svc.restart, overridden, muted)
        sb.append("</table>")
    }

    private fun scalarRow(sb: StringBuilder, key: String, value: String?, overridden: Set<String>, muted: String) {
        if (value.isNullOrBlank()) return
        row(sb, key, ComposeHtml.esc(value), key in overridden, muted)
    }

    private fun listRow(sb: StringBuilder, key: String, values: List<String>, overridden: Set<String>, muted: String) {
        if (values.isEmpty()) return
        row(sb, key, values.joinToString(", ") { ComposeHtml.esc(it) }, key in overridden, muted)
    }

    private fun row(sb: StringBuilder, key: String, valueHtml: String, highlighted: Boolean, muted: String) {
        val valueColor = if (highlighted) {
            ComposeHtml.hex(StackDoctorColors.ACCENT)
        } else {
            ComposeHtml.hex(UIUtil.getLabelForeground())
        }
        sb.append("<tr><td valign='top' style='color:$muted;padding:1px 8px 1px 0;white-space:nowrap;'>")
        sb.append(label(key)).append("</td>")
        sb.append("<td valign='top' style='color:$valueColor;padding:1px 0;'>").append(valueHtml)
        if (highlighted) {
            sb.append(" <span style='color:${ComposeHtml.hex(StackDoctorColors.OK_TEXT)};font-size:80%;'>(overlay)</span>")
        }
        sb.append("</td></tr>")
    }

    private fun label(key: String): String = when (key) {
        "image" -> "Image"
        "ports" -> "Ports"
        "expose" -> "Expose"
        "depends_on" -> "Depends on"
        "networks" -> "Networks"
        "volumes" -> "Volumes"
        "env_file" -> "Env files"
        "environment" -> "Environment"
        "healthcheck" -> "Healthcheck"
        "restart" -> "Restart"
        else -> key
    }

    private fun wrap(body: String): String {
        val fg = ComposeHtml.hex(UIUtil.getLabelForeground())
        return "<html><body style='font-family:sans-serif;color:$fg;padding:8px;'>$body</body></html>"
    }

    /** Project-relative path for display, falling back to the bare file name. */
    private fun relativePath(vf: VirtualFile): String {
        val base = project.basePath
        val path = vf.path
        return if (base != null && path.startsWith(base)) {
            path.removePrefix(base).trimStart('/', '\\').ifEmpty { vf.name }
        } else {
            vf.name
        }
    }
}
