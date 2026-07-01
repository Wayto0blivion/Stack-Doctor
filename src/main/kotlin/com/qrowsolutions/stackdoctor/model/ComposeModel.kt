package com.qrowsolutions.stackdoctor.model

/**
 * Lightweight, IDE-agnostic representation of a parsed `docker-compose` file.
 *
 * The parser ([com.qrowsolutions.stackdoctor.parser.ComposeParser]) fills these in from YAML PSI.
 * Keeping the model free of PSI types makes the doctor checks pure and unit-testable.
 */
data class ComposeProject(
    /** Absolute path of the compose file this was parsed from. */
    val filePath: String,
    /** Display name (the file name). */
    val fileName: String,
    val services: List<ComposeService>,
    /** Names of networks declared under the top-level `networks:` key. */
    val declaredNetworks: Set<String>,
    /** Names of volumes declared under the top-level `volumes:` key. */
    val declaredVolumes: Set<String>,
) {
    fun service(name: String): ComposeService? = services.firstOrNull { it.name == name }
    val serviceNames: Set<String> get() = services.mapTo(LinkedHashSet()) { it.name }
}

data class ComposeService(
    val name: String,
    val image: String?,
    val hasBuild: Boolean,
    /** Other services this one declares a dependency on (`depends_on`). */
    val dependsOn: List<String>,
    /** Legacy `links:` targets (service name part, before any alias). */
    val links: List<String>,
    val ports: List<PortMapping>,
    /** Container ports declared under `expose:` (internal only, never published). */
    val expose: List<String>,
    val volumes: List<VolumeMount>,
    val networks: Set<String>,
    val hasHealthcheck: Boolean,
    /** Whether `healthcheck:` is present but disabled (`disable: true`). */
    val healthcheckDisabled: Boolean,
    /** `env_file` entries as written (paths, possibly relative). */
    val envFiles: List<String>,
    /** Keys from the `environment:` block. */
    val environmentKeys: Set<String>,
    val restart: String?,
)

/**
 * A parsed `ports:` entry. Supports the short syntax (`"127.0.0.1:8080:80/tcp"`) and the
 * long object syntax. [hostIp] is null when no host IP was specified.
 */
data class PortMapping(
    val raw: String,
    val hostIp: String?,
    val hostPort: String?,
    val containerPort: String?,
    val protocol: String,
) {
    /** True when this binding is pinned to the loopback interface, so only the host can reach it. */
    val isLoopbackBound: Boolean
        get() = hostIp == "127.0.0.1" || hostIp == "localhost" || hostIp == "::1"

    /** True when a host port is published at all (vs. expose-only). */
    val isPublished: Boolean get() = hostPort != null
}

/**
 * A parsed `volumes:` entry on a service. [source] is null for anonymous volumes.
 * [isNamedVolume] distinguishes a named volume (`mydata:/var/lib`) from a bind mount (`./x:/y`).
 */
data class VolumeMount(
    val raw: String,
    val source: String?,
    val target: String?,
    val isNamedVolume: Boolean,
    val isBindMount: Boolean,
) {
    /** How this mount stores data, driving the map's persistence borders/badges. */
    val kind: VolumeKind
        get() = when {
            isBindMount -> VolumeKind.BIND
            isNamedVolume -> VolumeKind.NAMED
            else -> VolumeKind.ANONYMOUS
        }

    /** True when the data outlives the container (named volume or host bind), vs. an anonymous volume. */
    val isPersistent: Boolean get() = kind != VolumeKind.ANONYMOUS
}

/**
 * The three ways a compose volume stores data, ordered from safest to most surprising:
 *  * [NAMED]     — a Docker-managed named volume; survives `down`, removed only with `down -v`.
 *  * [BIND]      — a host path mounted in; data lives on the host filesystem.
 *  * [ANONYMOUS] — an unnamed volume (only a container path given); Docker invents a random name for
 *                  it, so it's easily orphaned and is the classic cause of "my data disappeared".
 */
enum class VolumeKind { NAMED, BIND, ANONYMOUS }

/**
 * A per-service roll-up of its [VolumeMount]s by [VolumeKind], so the map can decide a node's
 * border/badges once from pure data (and unit-test it without any UI). "Persistent" groups named
 * volumes and host binds — anything whose data survives a container recreate.
 */
data class VolumeSummary(
    val named: List<VolumeMount>,
    val binds: List<VolumeMount>,
    val anonymous: List<VolumeMount>,
) {
    /** Named volumes + host binds: mounts whose data outlives the container. */
    val persistentCount: Int get() = named.size + binds.size
    val hasPersistent: Boolean get() = persistentCount > 0
    val hasAnonymous: Boolean get() = anonymous.isNotEmpty()
    val total: Int get() = named.size + binds.size + anonymous.size
    val isEmpty: Boolean get() = total == 0

    companion object {
        fun of(service: ComposeService): VolumeSummary {
            val byKind = service.volumes.groupBy { it.kind }
            return VolumeSummary(
                named = byKind[VolumeKind.NAMED].orEmpty(),
                binds = byKind[VolumeKind.BIND].orEmpty(),
                anonymous = byKind[VolumeKind.ANONYMOUS].orEmpty(),
            )
        }
    }
}
