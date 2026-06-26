package com.qrowsolutions.stackdoctor.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile
import com.qrowsolutions.stackdoctor.analysis.ComposeScanner
import com.qrowsolutions.stackdoctor.diagnostics.Diagnostic
import com.qrowsolutions.stackdoctor.diagnostics.Severity
import com.qrowsolutions.stackdoctor.diagnostics.StackDoctor
import com.qrowsolutions.stackdoctor.parser.ComposeParser
import com.qrowsolutions.stackdoctor.parser.ComposePsi
import org.jetbrains.yaml.psi.YAMLFile
import java.io.File

/**
 * Surfaces the Stack Doctor checks directly in the compose-file editor: each finding is highlighted
 * on the precise YAML element it concerns (via its [com.qrowsolutions.stackdoctor.diagnostics.DiagnosticAnchor]),
 * carries its explanation as a tooltip, and offers quick-fixes where one is safe.
 *
 * This reuses the same pure [StackDoctor] checks as the tool window, so the inline view and the map
 * never disagree.
 */
class ComposeDoctorInspection : LocalInspectionTool() {

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor>? {
        if (file !is YAMLFile) return null
        if (!ComposeScanner.isComposeName(file.name)) return null

        val project = ComposeParser.parse(file) ?: return null
        if (project.services.isEmpty()) return null

        val baseDir = file.virtualFile?.parent?.path?.let { File(it) }
        val diagnostics = StackDoctor.run(project, baseDir)

        val problems = ArrayList<ProblemDescriptor>(diagnostics.size)
        for (d in diagnostics) {
            val anchor = d.anchor ?: continue
            val element = ComposePsi.resolveAnchor(file, anchor) ?: continue
            problems += manager.createProblemDescriptor(
                element,
                descriptionFor(d),
                isOnTheFly,
                ComposeQuickFixes.forDiagnostic(d),
                highlightFor(d.severity),
            )
        }
        return problems.toTypedArray()
    }

    private fun descriptionFor(d: Diagnostic): String =
        d.hint?.let { "${d.title} — $it" } ?: d.title

    private fun highlightFor(severity: Severity): ProblemHighlightType = when (severity) {
        Severity.ERROR -> ProblemHighlightType.GENERIC_ERROR
        Severity.WARNING -> ProblemHighlightType.WARNING
        Severity.INFO -> ProblemHighlightType.WEAK_WARNING
    }
}
