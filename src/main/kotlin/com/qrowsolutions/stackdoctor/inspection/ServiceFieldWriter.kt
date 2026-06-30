package com.qrowsolutions.stackdoctor.inspection

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.qrowsolutions.stackdoctor.parser.ComposePsi
import com.qrowsolutions.stackdoctor.parser.FieldKind
import com.qrowsolutions.stackdoctor.parser.ServiceFieldEdit
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Writes edits made in the service form back into the compose file. Mirrors the document-level
 * approach of [HealthcheckWriter]: each field is applied one at a time, re-committing the document in
 * between so PSI offsets stay valid (a service only has a handful of fields, so this is cheap).
 * Must run inside a write action (e.g. `WriteCommandAction`).
 *
 * Only *changed* fields are passed in, so untouched configuration is never rewritten. A scalar is
 * set / replaced / removed in place; a list or environment block is rewritten as a block sequence
 * (`key:\n  - item`). Clearing a field removes its key entirely.
 */
object ServiceFieldWriter {

    fun apply(project: Project, file: YAMLFile, serviceName: String, edits: List<ServiceFieldEdit>) {
        if (edits.isEmpty()) return
        val psiDocMgr = PsiDocumentManager.getInstance(project)
        val doc = psiDocMgr.getDocument(file) ?: return

        for (edit in edits) {
            psiDocMgr.commitDocument(doc)
            val body = ComposePsi.serviceKeyValue(file, serviceName)?.value as? YAMLMapping ?: continue
            when (edit.kind) {
                FieldKind.SCALAR -> applyScalar(doc, body, edit)
                FieldKind.LIST, FieldKind.ENV -> applyList(doc, body, edit)
                FieldKind.MAP -> applyMap(doc, body, edit)
            }
        }
        psiDocMgr.commitDocument(doc)
    }

    private fun applyScalar(doc: Document, body: YAMLMapping, edit: ServiceFieldEdit) {
        val kv = body.getKeyValueByKey(edit.key)
        val value = edit.value.trim()
        if (value.isEmpty()) {
            if (kv != null) deleteKeyValue(doc, kv)
            return
        }
        val scalar = quoteIfNeeded(value)
        val existing = kv?.value as? YAMLScalar
        when {
            existing != null -> doc.replaceString(existing.textRange.startOffset, existing.textRange.endOffset, scalar)
            kv != null -> doc.replaceString(kv.textRange.startOffset, kv.textRange.endOffset, "${edit.key}: $scalar")
            else -> insertKey(doc, body, "${edit.key}: $scalar")
        }
    }

    private fun applyList(doc: Document, body: YAMLMapping, edit: ServiceFieldEdit) {
        val kv = body.getKeyValueByKey(edit.key)
        val items = edit.value.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) {
            if (kv != null) deleteKeyValue(doc, kv)
            return
        }
        val itemPad = " ".repeat(bodyIndent(doc, body) + 2)
        val block = buildString {
            append(edit.key).append(':')
            for (item in items) append('\n').append(itemPad).append("- ").append(quoteIfNeeded(item))
        }
        if (kv != null) doc.replaceString(kv.textRange.startOffset, kv.textRange.endOffset, block)
        else insertKey(doc, body, block)
    }

    /** Rewrites a nested mapping (e.g. `healthcheck`) from `key: value` lines into a block mapping. */
    private fun applyMap(doc: Document, body: YAMLMapping, edit: ServiceFieldEdit) {
        val kv = body.getKeyValueByKey(edit.key)
        val entries = edit.value.lines().map { it.trim() }.filter { it.isNotEmpty() && it.contains(':') }
        if (entries.isEmpty()) {
            if (kv != null) deleteKeyValue(doc, kv)
            return
        }
        val pad = " ".repeat(bodyIndent(doc, body) + 2)
        val block = buildString {
            append(edit.key).append(':')
            for (entry in entries) {
                val k = entry.substringBefore(':').trim()
                val v = entry.substringAfter(':').trim()
                append('\n').append(pad).append(k).append(": ").append(v)
            }
        }
        if (kv != null) doc.replaceString(kv.textRange.startOffset, kv.textRange.endOffset, block)
        else insertKey(doc, body, block)
    }

    /** Inserts a new key block after the service's last existing key, at the body's indentation. */
    private fun insertKey(doc: Document, body: YAMLMapping, block: String) {
        val pad = " ".repeat(bodyIndent(doc, body))
        val offset = body.keyValues.lastOrNull()?.textRange?.endOffset ?: body.textRange.endOffset
        doc.insertString(offset, "\n$pad$block")
    }

    /** Deletes a key-value and the whole line(s) it occupies, including indent and trailing newline. */
    private fun deleteKeyValue(doc: Document, kv: YAMLKeyValue) {
        val start = doc.getLineStartOffset(doc.getLineNumber(kv.textRange.startOffset))
        var end = kv.textRange.endOffset
        if (end < doc.textLength && doc.charsSequence[end] == '\n') end++
        doc.deleteString(start, end)
    }

    /** Indentation (spaces) of the service's keys, taken from its first existing key. */
    private fun bodyIndent(doc: Document, body: YAMLMapping): Int {
        val off = body.keyValues.firstOrNull()?.textRange?.startOffset ?: return 0
        return off - doc.getLineStartOffset(doc.getLineNumber(off))
    }

    /** Minimal YAML quoting: only wrap in double quotes when the value would otherwise be misread. */
    private fun quoteIfNeeded(value: String): String {
        if (value.length >= 2 && value.first() == value.last() && (value.first() == '"' || value.first() == '\'')) {
            return value // already quoted
        }
        val risky = value != value.trim() ||
            value.contains(": ") || value.endsWith(":") || value.contains(" #") ||
            value.first() in "#&*!@`%[]{},>|\"'"
        return if (risky) "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else value
    }
}
