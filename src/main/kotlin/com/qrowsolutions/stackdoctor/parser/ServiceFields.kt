package com.qrowsolutions.stackdoctor.parser

import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

/** How a [ServiceField] is edited and written back: a single scalar, a list, or KEY=value pairs. */
enum class FieldKind { SCALAR, LIST, ENV }

/**
 * One editable parameter of a service: its YAML [key], a display [label], the current [value]
 * (a scalar string, or newline-joined entries for [FieldKind.LIST]/[FieldKind.ENV]) and a short
 * [explanation]. A few advanced forms (e.g. `depends_on` with conditions) are surfaced read-only
 * with a [note] explaining why, rather than being silently rewritten into a simpler shape.
 */
data class ServiceField(
    val key: String,
    val label: String,
    val kind: FieldKind,
    val value: String,
    val explanation: String,
    val editable: Boolean = true,
    val note: String? = null,
)

/** A change to apply for one field: its [key], its [kind] and the new raw [value] as typed. */
data class ServiceFieldEdit(val key: String, val kind: FieldKind, val value: String)

/**
 * Reads a service's currently-present parameters straight from YAML PSI into editable [ServiceField]s.
 *
 * Values come from the file rather than the (lossy) [com.qrowsolutions.stackdoctor.model.ComposeService]
 * model, so the form shows exactly what is there — including environment values, which the model only
 * keeps the keys of. Only keys that are actually present are returned, and they keep their natural
 * compose ordering (image, then networking, then volumes/env, then restart) for familiarity.
 */
object ServiceFields {

    fun read(serviceBody: YAMLMapping): List<ServiceField> {
        val out = mutableListOf<ServiceField>()

        serviceBody.getKeyValueByKey("image")?.let {
            out += scalar(it, "image", "Image", "Prebuilt image to run, pulled from a registry if it isn't present locally.")
        }
        serviceBody.getKeyValueByKey("ports")?.let {
            out += list(it, "ports", "Published ports", "Host-to-container port mappings (HOST:CONTAINER), one per line.")
        }
        serviceBody.getKeyValueByKey("expose")?.let {
            out += list(it, "expose", "Exposed ports", "Container ports reachable only by other services on the same network, one per line.")
        }
        dependsOn(serviceBody)?.let { out += it }
        serviceBody.getKeyValueByKey("links")?.let {
            out += list(it, "links", "Links", "Legacy network links to other services, one per line.")
        }
        networks(serviceBody)?.let { out += it }
        serviceBody.getKeyValueByKey("volumes")?.let {
            out += list(it, "volumes", "Volumes", "Volume / bind mounts (SOURCE:TARGET), one per line.")
        }
        serviceBody.getKeyValueByKey("env_file")?.let {
            out += list(it, "env_file", "Env files", "Files to load environment variables from, one path per line.")
        }
        environment(serviceBody)?.let { out += it }
        serviceBody.getKeyValueByKey("restart")?.let {
            out += scalar(it, "restart", "Restart policy", "When Docker restarts the container: no, always, on-failure or unless-stopped.")
        }
        return out
    }

    private fun scalar(kv: YAMLKeyValue, key: String, label: String, help: String): ServiceField =
        ServiceField(key, label, FieldKind.SCALAR, (kv.value as? YAMLScalar)?.textValue ?: "", help)

    private fun list(kv: YAMLKeyValue, key: String, label: String, help: String): ServiceField =
        ServiceField(key, label, FieldKind.LIST, scalarEntries(kv).joinToString("\n"), help)

    private fun scalarEntries(kv: YAMLKeyValue): List<String> = when (val v = kv.value) {
        is YAMLScalar -> listOf(v.textValue)
        is YAMLSequence -> v.items.mapNotNull { (it.value as? YAMLScalar)?.textValue }
        else -> emptyList()
    }

    /** `depends_on` is a list of names, or a map of name -> { condition } (kept read-only). */
    private fun dependsOn(body: YAMLMapping): ServiceField? {
        val kv = body.getKeyValueByKey("depends_on") ?: return null
        val help = "Services that must start before this one, one per line."
        return when (val v = kv.value) {
            is YAMLMapping -> ServiceField(
                "depends_on", "Depends on", FieldKind.LIST, v.keyValues.joinToString("\n") { it.keyText },
                help, editable = false, note = "Uses the conditional map form — edit it in the file to keep its conditions.",
            )
            else -> ServiceField("depends_on", "Depends on", FieldKind.LIST, scalarEntries(kv).joinToString("\n"), help)
        }
    }

    /** `networks` is a list of names, or a map of name -> { aliases/options } (kept read-only). */
    private fun networks(body: YAMLMapping): ServiceField? {
        val kv = body.getKeyValueByKey("networks") ?: return null
        val help = "Networks this service joins, one per line."
        return when (val v = kv.value) {
            is YAMLMapping -> ServiceField(
                "networks", "Networks", FieldKind.LIST, v.keyValues.joinToString("\n") { it.keyText },
                help, editable = false, note = "Uses the map form (aliases/options) — edit it in the file to keep them.",
            )
            else -> ServiceField("networks", "Networks", FieldKind.LIST, scalarEntries(kv).joinToString("\n"), help)
        }
    }

    /** `environment` is a `KEY=value` list or a `KEY: value` map; both are shown as `KEY=value` lines. */
    private fun environment(body: YAMLMapping): ServiceField? {
        val kv = body.getKeyValueByKey("environment") ?: return null
        val lines = when (val v = kv.value) {
            is YAMLSequence -> v.items.mapNotNull { (it.value as? YAMLScalar)?.textValue }
            is YAMLMapping -> v.keyValues.map { e ->
                val ev = (e.value as? YAMLScalar)?.textValue
                if (ev.isNullOrEmpty()) e.keyText else "${e.keyText}=$ev"
            }
            else -> emptyList()
        }
        return ServiceField(
            "environment", "Environment", FieldKind.ENV, lines.joinToString("\n"),
            "Environment variables as KEY=value, one per line.",
        )
    }
}
