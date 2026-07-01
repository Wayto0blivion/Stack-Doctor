package com.qrowsolutions.stackdoctor.parser

import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

/**
 * How a [ServiceField] is edited and written back: a single scalar, a list (`- item`), `KEY=value`
 * environment pairs, or a nested `key: value` mapping (e.g. `healthcheck`).
 */
enum class FieldKind { SCALAR, LIST, ENV, MAP }

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

    private const val HEALTHCHECK_HELP =
        "Container health probe — one key: value per line " +
            "(test, interval, timeout, retries, start_period, disable). " +
            "test is a YAML list, e.g. [\"CMD-SHELL\", \"curl -fsS http://localhost || exit 1\"]."

    /** Display metadata for one editable service field, shared by [read] and [addableTemplates]. */
    private data class Spec(val key: String, val label: String, val kind: FieldKind, val explanation: String)

    /** Every editable field, in natural compose order (image, networking, volumes/env, restart). */
    private val SPECS: List<Spec> = listOf(
        Spec("image", "Image", FieldKind.SCALAR, "Prebuilt image to run, pulled from a registry if it isn't present locally."),
        Spec("ports", "Published ports", FieldKind.LIST, "Host-to-container port mappings (HOST:CONTAINER), one per line."),
        Spec("expose", "Exposed ports", FieldKind.LIST, "Container ports reachable only by other services on the same network. One per line."),
        Spec("depends_on", "Depends on", FieldKind.LIST, "Services that must start before this one, one per line."),
        Spec("links", "Links", FieldKind.LIST, "Legacy network links to other services, one per line."),
        Spec("networks", "Networks", FieldKind.LIST, "Networks this service joins, one per line."),
        Spec("volumes", "Volumes", FieldKind.LIST, "Volume / bind mounts (SOURCE:TARGET), one per line."),
        Spec("env_file", "Env files", FieldKind.LIST, "Files to load environment variables from, one path per line."),
        Spec("environment", "Environment", FieldKind.ENV, "Environment variables as KEY=value, one per line."),
        Spec("healthcheck", "Healthcheck", FieldKind.MAP, HEALTHCHECK_HELP),
        Spec("restart", "Restart policy", FieldKind.SCALAR, "When Docker restarts the container: no, always, on-failure or unless-stopped."),
    )

    fun read(serviceBody: YAMLMapping): List<ServiceField> =
        SPECS.mapNotNull { spec ->
            val kv = serviceBody.getKeyValueByKey(spec.key) ?: return@mapNotNull null
            readField(spec, kv)
        }

    /**
     * Empty, editable templates for every field the service does not already declare ([present]),
     * for the form's "Add field" menu — so a user can add and then fill in a parameter that isn't
     * there yet (a healthcheck, a restart policy, expose, …). Saving writes the new key into the file.
     */
    fun addableTemplates(present: Set<String>): List<ServiceField> =
        SPECS.filter { it.key !in present }
            .map { ServiceField(it.key, it.label, it.kind, "", it.explanation) }

    private fun readField(spec: Spec, kv: YAMLKeyValue): ServiceField = when (spec.key) {
        "depends_on" -> dependsOn(spec, kv)
        "networks" -> networks(spec, kv)
        "environment" -> environment(spec, kv)
        "healthcheck" -> healthcheck(spec, kv)
        else -> when (spec.kind) {
            FieldKind.SCALAR ->
                ServiceField(spec.key, spec.label, FieldKind.SCALAR, (kv.value as? YAMLScalar)?.textValue ?: "", spec.explanation)
            else ->
                ServiceField(spec.key, spec.label, spec.kind, scalarEntries(kv).joinToString("\n"), spec.explanation)
        }
    }

    private fun scalarEntries(kv: YAMLKeyValue): List<String> = when (val v = kv.value) {
        is YAMLScalar -> listOf(v.textValue)
        is YAMLSequence -> v.items.mapNotNull { (it.value as? YAMLScalar)?.textValue }
        else -> emptyList()
    }

    /** `depends_on` is a list of names, or a map of name -> { condition } (kept read-only). */
    private fun dependsOn(spec: Spec, kv: YAMLKeyValue): ServiceField = when (val v = kv.value) {
        is YAMLMapping -> ServiceField(
            spec.key, spec.label, FieldKind.LIST, v.keyValues.joinToString("\n") { it.keyText },
            spec.explanation, editable = false, note = "Uses the conditional map form — edit it in the file to keep its conditions.",
        )
        else -> ServiceField(spec.key, spec.label, FieldKind.LIST, scalarEntries(kv).joinToString("\n"), spec.explanation)
    }

    /** `networks` is a list of names, or a map of name -> { aliases/options } (kept read-only). */
    private fun networks(spec: Spec, kv: YAMLKeyValue): ServiceField = when (val v = kv.value) {
        is YAMLMapping -> ServiceField(
            spec.key, spec.label, FieldKind.LIST, v.keyValues.joinToString("\n") { it.keyText },
            spec.explanation, editable = false, note = "Uses the map form (aliases/options) — edit it in the file to keep them.",
        )
        else -> ServiceField(spec.key, spec.label, FieldKind.LIST, scalarEntries(kv).joinToString("\n"), spec.explanation)
    }

    /** `environment` is a `KEY=value` list or a `KEY: value` map; both are shown as `KEY=value` lines. */
    private fun environment(spec: Spec, kv: YAMLKeyValue): ServiceField {
        val lines = when (val v = kv.value) {
            is YAMLSequence -> v.items.mapNotNull { (it.value as? YAMLScalar)?.textValue }
            is YAMLMapping -> v.keyValues.map { e ->
                val ev = (e.value as? YAMLScalar)?.textValue
                if (ev.isNullOrEmpty()) e.keyText else "${e.keyText}=$ev"
            }
            else -> emptyList()
        }
        return ServiceField(spec.key, spec.label, FieldKind.ENV, lines.joinToString("\n"), spec.explanation)
    }

    /**
     * `healthcheck` is a nested mapping shown as `key: value` lines (test, interval, timeout, …).
     * The `test` command is rendered in YAML flow form (`["CMD-SHELL", "…"]`) so it stays on one line,
     * matching what the plugin's own generator writes.
     */
    private fun healthcheck(spec: Spec, kv: YAMLKeyValue): ServiceField {
        val map = kv.value as? YAMLMapping ?: return ServiceField(
            spec.key, spec.label, FieldKind.MAP, "", spec.explanation,
            editable = false, note = "Unrecognised form — edit it directly in the file.",
        )
        val lines = map.keyValues.map { sub -> "${sub.keyText}: ${flowText(sub.value)}" }
        return ServiceField(spec.key, spec.label, FieldKind.MAP, lines.joinToString("\n"), spec.explanation)
    }

    /** A single-line rendering of a healthcheck sub-value: scalar as-is, sequence as a flow list. */
    private fun flowText(value: org.jetbrains.yaml.psi.YAMLValue?): String = when (value) {
        is YAMLScalar -> value.textValue
        is YAMLSequence -> value.items
            .mapNotNull { (it.value as? YAMLScalar)?.textValue }
            .joinToString(", ", "[", "]") { "\"$it\"" }
        else -> value?.text?.trim() ?: ""
    }
}
