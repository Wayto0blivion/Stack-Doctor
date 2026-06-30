package com.qrowsolutions.stackdoctor.analysis

import com.qrowsolutions.stackdoctor.model.ComposeProject
import com.qrowsolutions.stackdoctor.model.ComposeService
import com.qrowsolutions.stackdoctor.model.VolumeMount

/**
 * Result of merging compose files: the effective [merged] project plus, per service name, the set
 * of field keys an overlay changed or added relative to the base. [overriddenKeys] drives the
 * provenance markers in the merge preview (e.g. "ports", "image"); the special key
 * [NEW_SERVICE] marks a service that the overlay introduced entirely.
 */
data class MergeResult(
    val merged: ComposeProject,
    val overriddenKeys: Map<String, Set<String>>,
) {
    companion object {
        const val NEW_SERVICE = "(new)"
    }
}

/**
 * Pure, model-level merge of docker-compose files, mirroring how `docker compose` overlays a
 * later file (e.g. `docker-compose.override.yml`) onto an earlier one. The merge happens on the
 * IDE-agnostic [ComposeProject] model so it stays unit-testable and consistent with the rest of
 * the plugin's analysis (which all runs on the model).
 *
 * Merge rules, per Compose semantics:
 *  - single-value options (`image`, `restart`) — the overlay replaces the base when it sets them;
 *  - sequence options (`ports`, `volumes`, `expose`, `links`, `env_file`) — concatenated, with
 *    duplicates dropped (`volumes` by mount target, `ports` by their raw text);
 *  - `depends_on`, `networks`, `environment` — unioned;
 *  - `healthcheck` — the overlay's wins when it declares one, else the base's is kept.
 *
 * The merged project keeps the **base** file's path/name so on-disk checks (bind mounts, env
 * files) resolve relative paths against the base file's directory.
 */
object ComposeMerge {

    private const val OVERRIDE_TOKEN = ".override"

    /**
     * If [name] is an override-style file name (it contains `.override.`), returns the
     * corresponding base file name with the `.override` part removed
     * (`docker-compose.override.yml` -> `docker-compose.yml`); otherwise null. Used only to
     * pre-select a sensible default overlay in the picker — never to gate the feature.
     */
    fun baseNameForOverride(name: String): String? {
        val marker = "$OVERRIDE_TOKEN."
        val idx = name.lowercase().lastIndexOf(marker)
        if (idx < 0) return null
        return name.substring(0, idx) + name.substring(idx + OVERRIDE_TOKEN.length)
    }

    /** Folds each overlay in [overrides] onto [base], in order. */
    fun merge(base: ComposeProject, overrides: List<ComposeProject>): MergeResult {
        var acc = base
        val overridden = LinkedHashMap<String, MutableSet<String>>()
        for (overlay in overrides) acc = mergeOne(acc, overlay, overridden)
        return MergeResult(acc, overridden)
    }

    private fun mergeOne(
        base: ComposeProject,
        overlay: ComposeProject,
        overridden: MutableMap<String, MutableSet<String>>,
    ): ComposeProject {
        val overlayByName = overlay.services.associateBy { it.name }
        val baseNames = base.serviceNames

        // Base services in order, merged with their overlay counterpart when present.
        val merged = base.services.map { b ->
            val o = overlayByName[b.name] ?: return@map b
            val changed = overridden.getOrPut(b.name) { linkedSetOf() }
            mergeService(b, o, changed)
        }.toMutableList()

        // Services only the overlay declares are appended, flagged as new.
        for (o in overlay.services) {
            if (o.name in baseNames) continue
            overridden.getOrPut(o.name) { linkedSetOf() }.add(MergeResult.NEW_SERVICE)
            merged += o
        }

        return ComposeProject(
            filePath = base.filePath,
            fileName = base.fileName,
            services = merged,
            declaredNetworks = base.declaredNetworks + overlay.declaredNetworks,
            declaredVolumes = base.declaredVolumes + overlay.declaredVolumes,
        )
    }

    private fun mergeService(base: ComposeService, overlay: ComposeService, changed: MutableSet<String>): ComposeService {
        fun mark(condition: Boolean, key: String) { if (condition) changed.add(key) }

        mark(overlay.image != null && overlay.image != base.image, "image")
        mark(overlay.restart != null && overlay.restart != base.restart, "restart")
        mark(overlay.hasBuild && !base.hasBuild, "build")

        val basePortKeys = base.ports.mapTo(HashSet()) { it.raw }
        mark(overlay.ports.any { it.raw !in basePortKeys }, "ports")

        val baseVolKeys = base.volumes.mapTo(HashSet()) { volumeKey(it) }
        mark(overlay.volumes.any { volumeKey(it) !in baseVolKeys }, "volumes")

        mark(overlay.expose.any { it !in base.expose }, "expose")
        mark(overlay.links.any { it !in base.links }, "links")
        mark(overlay.envFiles.any { it !in base.envFiles }, "env_file")
        mark(overlay.dependsOn.any { it !in base.dependsOn }, "depends_on")
        mark(overlay.networks.any { it !in base.networks }, "networks")
        mark(overlay.environmentKeys.any { it !in base.environmentKeys }, "environment")
        mark(overlay.hasHealthcheck, "healthcheck")

        return ComposeService(
            name = base.name,
            image = overlay.image ?: base.image,
            hasBuild = base.hasBuild || overlay.hasBuild,
            dependsOn = (base.dependsOn + overlay.dependsOn).distinct(),
            links = (base.links + overlay.links).distinct(),
            ports = dedupBy(base.ports + overlay.ports) { it.raw },
            expose = (base.expose + overlay.expose).distinct(),
            volumes = dedupBy(base.volumes + overlay.volumes) { volumeKey(it) },
            networks = base.networks + overlay.networks,
            hasHealthcheck = if (overlay.hasHealthcheck) true else base.hasHealthcheck,
            healthcheckDisabled = if (overlay.hasHealthcheck) overlay.healthcheckDisabled else base.healthcheckDisabled,
            envFiles = (base.envFiles + overlay.envFiles).distinct(),
            environmentKeys = base.environmentKeys + overlay.environmentKeys,
            restart = overlay.restart ?: base.restart,
        )
    }

    /** A volume's identity for de-duplication: its mount target if known, else its raw text. */
    private fun volumeKey(v: VolumeMount): String = v.target ?: v.raw

    private fun <T> dedupBy(items: List<T>, key: (T) -> String): List<T> {
        val seen = HashSet<String>()
        return items.filter { seen.add(key(it)) }
    }
}
