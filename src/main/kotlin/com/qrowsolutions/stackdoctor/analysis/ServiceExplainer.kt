package com.qrowsolutions.stackdoctor.analysis

import com.qrowsolutions.stackdoctor.model.ComposeService
import com.qrowsolutions.stackdoctor.model.PortMapping
import com.qrowsolutions.stackdoctor.model.VolumeMount

/** One row of a service's plain-English breakdown: a [label], the raw [value], and what it does. */
data class DetailLine(val label: String, val value: String, val explanation: String)

/**
 * Turns a [ComposeService] into a human-readable, line-by-line breakdown — one [DetailLine] per
 * meaningful piece of configuration. Pure (model-only) so it's easy to unit-test and reuse outside
 * the UI.
 */
object ServiceExplainer {

    fun explain(svc: ComposeService): List<DetailLine> {
        val out = mutableListOf<DetailLine>()

        when {
            svc.image != null -> out += DetailLine(
                "image", svc.image,
                "Runs this prebuilt image, pulled from a registry if it isn't present locally.",
            )
            svc.hasBuild -> out += DetailLine(
                "build", "(local)",
                "Builds the image from a local Dockerfile/build context instead of pulling one.",
            )
        }

        for (port in svc.ports) out += explainPort(port)

        if (svc.expose.isNotEmpty()) out += DetailLine(
            "expose", svc.expose.joinToString(", "),
            "Reachable only by other services on the same network — not published to the host.",
        )

        if (svc.dependsOn.isNotEmpty()) out += DetailLine(
            "depends_on", svc.dependsOn.joinToString(", "),
            "Compose starts these first; without healthchecks it only waits for them to launch, " +
                "not to be ready.",
        )

        if (svc.links.isNotEmpty()) out += DetailLine(
            "links", svc.links.joinToString(", "),
            "Legacy networking link; modern Compose reaches services by name on a shared network.",
        )

        if (svc.networks.isNotEmpty()) out += DetailLine(
            "networks", svc.networks.joinToString(", "),
            "Joins these networks; services can only reach each other on a shared network.",
        )

        for (vol in svc.volumes) out += explainVolume(vol)

        when {
            svc.healthcheckDisabled -> out += DetailLine(
                "healthcheck", "disabled",
                "Healthcheck is explicitly turned off, so dependents can't wait for readiness.",
            )
            svc.hasHealthcheck -> out += DetailLine(
                "healthcheck", "enabled",
                "Docker probes the container so dependents can wait until it's actually ready.",
            )
        }

        for (envFile in svc.envFiles) out += DetailLine(
            "env_file", envFile,
            "Loads environment variables from this file; missing files leave the values empty.",
        )

        if (svc.environmentKeys.isNotEmpty()) out += DetailLine(
            "environment", "${svc.environmentKeys.size} variable(s)",
            "Sets ${svc.environmentKeys.joinToString(", ")} inside the container.",
        )

        svc.restart?.let {
            out += DetailLine("restart", it, "Restart policy controlling when Docker brings the container back up.")
        }

        if (out.isEmpty()) out += DetailLine(
            "(no config)", "",
            "This service declares nothing beyond its name.",
        )
        return out
    }

    private fun explainPort(port: PortMapping): DetailLine = when {
        port.isLoopbackBound -> DetailLine(
            "port", port.raw,
            "Published on ${port.hostIp} only — reachable from this machine but not from other " +
                "devices on the network.",
        )
        port.isPublished -> DetailLine(
            "port", port.raw,
            "Maps host port ${port.hostPort} → container port ${port.containerPort}/${port.protocol}; " +
                "reachable from your network.",
        )
        else -> DetailLine(
            "port", port.raw,
            "Container port ${port.containerPort}/${port.protocol}, internal to the Compose network.",
        )
    }

    private fun explainVolume(vol: VolumeMount): DetailLine = when {
        vol.isBindMount -> DetailLine(
            "volume", vol.raw,
            "Bind-mounts a host path into the container; changes on either side are shared live.",
        )
        vol.isNamedVolume -> DetailLine(
            "volume", vol.raw,
            "Named volume managed by Docker; persists across container restarts and rebuilds.",
        )
        else -> DetailLine(
            "volume", vol.raw,
            "Anonymous volume; data persists until the container is removed.",
        )
    }
}
