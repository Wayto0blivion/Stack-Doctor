package com.qrowsolutions.stackdoctor.inspection

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.qrowsolutions.stackdoctor.analysis.HealthcheckGenerator.Healthcheck
import com.qrowsolutions.stackdoctor.parser.ComposePsi
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Writes generated [Healthcheck]s back into a compose file. Insertions are applied bottom-up so
 * earlier text offsets stay valid. Must be called inside a write action (a quick-fix or
 * `WriteCommandAction`); services that already declare a `healthcheck:` are skipped.
 */
object HealthcheckWriter {

    private data class Insert(val offset: Int, val text: String)

    fun apply(project: Project, file: YAMLFile, additions: List<Pair<String, Healthcheck>>) {
        val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val inserts = ArrayList<Insert>(additions.size)

        for ((name, hc) in additions) {
            val svcKv = ComposePsi.serviceKeyValue(file, name) ?: continue
            val body = svcKv.value as? YAMLMapping
            if (body?.getKeyValueByKey("healthcheck") != null) continue // already has one

            val pad = " ".repeat(bodyIndent(doc, body, svcKv))
            val text = buildString {
                append('\n').append(pad).append("healthcheck:")
                append('\n').append(pad).append("  test: ").append(hc.test)
                append('\n').append(pad).append("  interval: ").append(hc.interval)
                append('\n').append(pad).append("  timeout: ").append(hc.timeout)
                append('\n').append(pad).append("  retries: ").append(hc.retries)
                append('\n').append(pad).append("  start_period: ").append(hc.startPeriod)
            }
            inserts += Insert(insertionOffset(body, svcKv), text)
        }

        inserts.sortedByDescending { it.offset }.forEach { doc.insertString(it.offset, it.text) }
        PsiDocumentManager.getInstance(project).commitDocument(doc)
    }

    /** Indentation (in spaces) to use for the new `healthcheck:` key: align with the service's other keys. */
    private fun bodyIndent(doc: Document, body: YAMLMapping?, svcKv: YAMLKeyValue): Int {
        body?.keyValues?.firstOrNull()?.let { return columnOf(doc, it.textRange.startOffset) }
        // Empty/scalar body: nest one level under the service key itself.
        return columnOf(doc, svcKv.textRange.startOffset) + 2
    }

    /** Insert after the service's last existing key, or right after the service key if it has no body. */
    private fun insertionOffset(body: YAMLMapping?, svcKv: YAMLKeyValue): Int =
        body?.keyValues?.lastOrNull()?.textRange?.endOffset ?: svcKv.textRange.endOffset

    private fun columnOf(doc: Document, offset: Int): Int =
        offset - doc.getLineStartOffset(doc.getLineNumber(offset))
}
