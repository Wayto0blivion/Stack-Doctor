package com.qrowsolutions.stackdoctor.parser

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.qrowsolutions.stackdoctor.diagnostics.DiagnosticAnchor
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * PSI-side companion to [ComposeParser]: turns the PSI-free [DiagnosticAnchor] descriptors (and
 * plain service names) back into concrete YAML elements, so the inline inspection can highlight a
 * finding and the tool window can navigate to a service in the editor.
 */
object ComposePsi {

    fun topMapping(file: YAMLFile): YAMLMapping? =
        file.documents.firstNotNullOfOrNull { it.topLevelValue as? YAMLMapping }

    fun servicesMapping(file: YAMLFile): YAMLMapping? =
        topMapping(file)?.getKeyValueByKey("services")?.value as? YAMLMapping

    /** The `name:` key-value for a service under `services:`, or null if absent. */
    fun serviceKeyValue(file: YAMLFile, name: String): YAMLKeyValue? =
        servicesMapping(file)?.getKeyValueByKey(name)

    /**
     * Best element to highlight for [anchor], narrowing from the service mapping down to a single
     * scalar when possible. Always falls back to a coarser element rather than returning null, so a
     * finding is never silently dropped (callers still get null only when the service is gone).
     */
    fun resolveAnchor(file: YAMLFile, anchor: DiagnosticAnchor): PsiElement? {
        if (anchor.service == null) {
            val root = topMapping(file) ?: return null
            val kv = anchor.key?.let { root.getKeyValueByKey(it) }
            return kv?.key ?: kv ?: root
        }

        val svcKv = serviceKeyValue(file, anchor.service) ?: return null
        val nameElement = svcKv.key ?: svcKv
        val body = svcKv.value as? YAMLMapping ?: return nameElement
        val keyKv = anchor.key?.let { body.getKeyValueByKey(it) } ?: return nameElement
        if (anchor.value == null) return keyKv.key ?: keyKv

        val match = PsiTreeUtil.findChildrenOfType(keyKv, YAMLScalar::class.java).firstOrNull {
            val text = it.textValue
            text == anchor.value || text.substringBefore(':') == anchor.value
        }
        return match ?: keyKv.key ?: keyKv
    }
}
