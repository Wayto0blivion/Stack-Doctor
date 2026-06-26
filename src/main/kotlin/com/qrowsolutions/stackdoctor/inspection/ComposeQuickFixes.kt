package com.qrowsolutions.stackdoctor.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.qrowsolutions.stackdoctor.analysis.HealthcheckGenerator
import com.qrowsolutions.stackdoctor.diagnostics.Diagnostic
import com.qrowsolutions.stackdoctor.parser.ComposeParser
import com.qrowsolutions.stackdoctor.parser.ComposePsi
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/** Maps a [Diagnostic] id to the quick-fixes the inline inspection should offer for it. */
object ComposeQuickFixes {

    fun forDiagnostic(d: Diagnostic): Array<LocalQuickFix> = when (d.id) {
        "loopback-bound-port" -> arrayOf(PublishOnAllInterfacesFix())
        "undeclared-volume" -> d.anchor?.value?.let { arrayOf<LocalQuickFix>(DeclareTopLevelFix("volumes", it)) } ?: LocalQuickFix.EMPTY_ARRAY
        "undeclared-network" -> d.anchor?.value?.let { arrayOf<LocalQuickFix>(DeclareTopLevelFix("networks", it)) } ?: LocalQuickFix.EMPTY_ARRAY
        "missing-healthcheck" -> d.service?.let { arrayOf<LocalQuickFix>(AddHealthcheckFix(it)) } ?: LocalQuickFix.EMPTY_ARRAY
        else -> LocalQuickFix.EMPTY_ARRAY
    }

    /** Drops a `127.0.0.1:` (or any host-IP) prefix from the published port so it binds all interfaces. */
    internal fun stripHostIp(raw: String): String {
        val slash = raw.indexOf('/')
        val proto = if (slash >= 0) raw.substring(slash) else ""
        val body = if (slash >= 0) raw.substring(0, slash) else raw
        val parts = body.split(':')
        // Only host_ip:host:container (3 segments) carries a host IP to drop.
        val newBody = if (parts.size >= 3) parts.takeLast(2).joinToString(":") else body
        return newBody + proto
    }
}

/**
 * Rewrites a loopback-bound port mapping (`127.0.0.1:8000:8000`) to publish on all interfaces
 * (`8000:8000`) by editing the matched scalar in place.
 */
private class PublishOnAllInterfacesFix : LocalQuickFix {
    override fun getFamilyName(): String = "Publish on all interfaces"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val scalar = descriptor.psiElement as? YAMLScalar ?: return
        val file = scalar.containingFile as? YAMLFile ?: return
        val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val range = scalar.textRange
        val newValue = ComposeQuickFixes.stripHostIp(scalar.textValue)
        // Replace only the inner text value, preserving any surrounding quotes the scalar may carry.
        val original = scalar.text
        val replacement = original.replace(scalar.textValue, newValue)
        doc.replaceString(range.startOffset, range.endOffset, replacement)
        PsiDocumentManager.getInstance(project).commitDocument(doc)
    }
}

/**
 * Adds a context-aware `healthcheck:` to a service that is depended on but has none. The probe is
 * inferred from the service's image/name (and a representative port) by [HealthcheckGenerator].
 */
private class AddHealthcheckFix(private val serviceName: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Add a generated healthcheck"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement.containingFile as? YAMLFile ?: return
        val svc = ComposeParser.parse(file)?.service(serviceName) ?: return
        val healthcheck = HealthcheckGenerator.generate(svc) ?: return
        HealthcheckWriter.apply(project, file, listOf(serviceName to healthcheck))
    }
}

/**
 * Declares a named volume/network under the top-level `volumes:`/`networks:` section, creating the
 * section if it doesn't exist yet. Works at the document level so it stays robust across the various
 * shapes a section can take (absent, empty `{}`, or an existing block).
 */
private class DeclareTopLevelFix(
    private val section: String,
    private val name: String,
) : LocalQuickFix {

    override fun getFamilyName(): String = "Declare $section entry '$name'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement.containingFile as? YAMLFile ?: return
        val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val root = ComposePsi.topMapping(file) ?: return
        val sectionKv = root.getKeyValueByKey(section)
        val value = sectionKv?.value

        when {
            sectionKv == null -> {
                val text = doc.text
                val sep = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
                doc.insertString(doc.textLength, "$sep$section:\n  $name:\n")
            }
            // `volumes: {}` (flow-style empty) -> replace with a block mapping holding the new entry.
            value != null && value.text.trim() == "{}" ->
                doc.replaceString(value.textRange.startOffset, value.textRange.endOffset, "\n  $name:")
            // `volumes:` with no value yet.
            value == null ->
                doc.insertString(sectionKv.textRange.endOffset, "\n  $name:")
            // Existing block mapping -> append a sibling entry after the last child.
            else ->
                doc.insertString(value.textRange.endOffset, "\n  $name:")
        }
        PsiDocumentManager.getInstance(project).commitDocument(doc)
    }
}
