package com.qrowsolutions.stackdoctor.diagnostics

import com.qrowsolutions.stackdoctor.model.ComposeProject
import com.qrowsolutions.stackdoctor.model.ComposeService
import java.io.File

/**
 * Runs the suite of "doctor" checks over a parsed [ComposeProject].
 *
 * [baseDir] is the directory containing the compose file; it is used to resolve relative
 * bind-mount and `env_file` paths against the filesystem. Pass null to skip on-disk checks.
 */
object StackDoctor {

    private val REVERSE_PROXY_HINTS = listOf("nginx", "traefik", "caddy", "haproxy", "envoy")

    fun run(project: ComposeProject, baseDir: File?): List<Diagnostic> {
        val graph = ServiceGraph(project)
        val out = mutableListOf<Diagnostic>()

        checkUnknownDependsOn(project, out)
        checkCycles(graph, out)
        checkMissingHealthchecks(project, graph, out)
        checkLoopbackPorts(project, out)
        checkPortConflicts(project, out)
        checkUndeclaredNamedVolumes(project, out)
        checkUndeclaredNetworks(project, out)
        checkBindPaths(project, baseDir, out)
        checkEnvFiles(project, baseDir, out)
        checkReverseProxyNoPorts(project, out)

        return out.sortedBy { it.severity.ordinal }
    }

    private fun checkUnknownDependsOn(project: ComposeProject, out: MutableList<Diagnostic>) {
        val known = project.serviceNames
        for (svc in project.services) {
            for (dep in svc.dependsOn) {
                if (dep !in known) {
                    out += Diagnostic(
                        id = "unknown-depends-on",
                        severity = Severity.ERROR,
                        service = svc.name,
                        title = "depends_on an unknown service '$dep'",
                        detail = "Service '${svc.name}' depends on '$dep', which is not defined in this " +
                            "compose file. Compose will fail to start with \"service '$dep' not found\".",
                        hint = "Fix the name or define the '$dep' service.",
                    )
                }
            }
        }
    }

    private fun checkCycles(graph: ServiceGraph, out: MutableList<Diagnostic>) {
        for (cycle in graph.findCycles()) {
            val path = (cycle + cycle.first()).joinToString(" → ")
            out += Diagnostic(
                id = "dependency-cycle",
                severity = Severity.ERROR,
                service = cycle.first(),
                title = "Circular depends_on: $path",
                detail = "These services depend on each other in a loop, so Compose cannot determine a " +
                    "valid startup order.",
                hint = "Break the cycle by removing one of the depends_on edges.",
            )
        }
    }

    private fun checkMissingHealthchecks(
        project: ComposeProject,
        graph: ServiceGraph,
        out: MutableList<Diagnostic>,
    ) {
        for (svc in project.services) {
            if (!graph.isDependedOn(svc.name)) continue
            if (svc.hasHealthcheck && !svc.healthcheckDisabled) continue
            val dependents = graph.dependents[svc.name].orEmpty()
            out += Diagnostic(
                id = "missing-healthcheck",
                severity = Severity.WARNING,
                service = svc.name,
                title = "No healthcheck, but ${dependents.size} service(s) depend on it",
                detail = "${dependents.joinToString(", ")} depend on '${svc.name}'. Without a healthcheck, " +
                    "'depends_on: { condition: service_healthy }' can't be used and dependents may start " +
                    "before '${svc.name}' is actually ready.",
                hint = "Add a healthcheck so dependents can wait for it to be ready.",
            )
        }
    }

    private fun checkLoopbackPorts(project: ComposeProject, out: MutableList<Diagnostic>) {
        for (svc in project.services) {
            for (port in svc.ports) {
                if (port.isLoopbackBound) {
                    out += Diagnostic(
                        id = "loopback-bound-port",
                        severity = Severity.WARNING,
                        service = svc.name,
                        title = "Port '${port.raw}' is bound to localhost only",
                        detail = "This binding pins the host port to ${port.hostIp}, so the service is " +
                            "reachable from this machine but NOT from other devices on the network. This is the " +
                            "usual cause of \"works locally but I can't reach it from my phone/other PC\".",
                        hint = "Drop the ${port.hostIp} prefix (use '${port.hostPort}:${port.containerPort}') " +
                            "to publish on all interfaces.",
                    )
                }
            }
        }
    }

    private fun checkPortConflicts(project: ComposeProject, out: MutableList<Diagnostic>) {
        // Map "ip:port/proto" -> services that publish it.
        val byBinding = HashMap<String, MutableList<String>>()
        for (svc in project.services) {
            for (port in svc.ports) {
                if (!port.isPublished) continue
                val key = "${port.hostIp ?: "0.0.0.0"}:${port.hostPort}/${port.protocol}"
                byBinding.getOrPut(key) { mutableListOf() }.add(svc.name)
            }
        }
        for ((binding, services) in byBinding) {
            if (services.size > 1) {
                out += Diagnostic(
                    id = "port-conflict",
                    severity = Severity.ERROR,
                    service = services.first(),
                    title = "Host port conflict on $binding",
                    detail = "Services ${services.joinToString(", ")} all publish host binding $binding. " +
                        "Only one container can own a host port; the others will fail to start with " +
                        "\"port is already allocated\".",
                    hint = "Give each service a distinct host port.",
                )
            }
        }
    }

    private fun checkUndeclaredNamedVolumes(project: ComposeProject, out: MutableList<Diagnostic>) {
        for (svc in project.services) {
            for (vol in svc.volumes) {
                val src = vol.source ?: continue
                if (vol.isNamedVolume && src !in project.declaredVolumes) {
                    out += Diagnostic(
                        id = "undeclared-volume",
                        severity = Severity.WARNING,
                        service = svc.name,
                        title = "Named volume '$src' is not declared",
                        detail = "Service '${svc.name}' mounts named volume '$src', but it isn't listed under the " +
                            "top-level 'volumes:' key. Compose requires named volumes to be declared.",
                        hint = "Add '$src:' under the top-level 'volumes:' section.",
                    )
                }
            }
        }
    }

    private fun checkUndeclaredNetworks(project: ComposeProject, out: MutableList<Diagnostic>) {
        for (svc in project.services) {
            for (net in svc.networks) {
                if (net != "default" && net !in project.declaredNetworks) {
                    out += Diagnostic(
                        id = "undeclared-network",
                        severity = Severity.WARNING,
                        service = svc.name,
                        title = "Network '$net' is not declared",
                        detail = "Service '${svc.name}' joins network '$net', which isn't defined under the " +
                            "top-level 'networks:' key.",
                        hint = "Declare '$net:' under the top-level 'networks:' section.",
                    )
                }
            }
        }
    }

    private fun checkBindPaths(project: ComposeProject, baseDir: File?, out: MutableList<Diagnostic>) {
        baseDir ?: return
        for (svc in project.services) {
            for (vol in svc.volumes) {
                val src = vol.source ?: continue
                if (!vol.isBindMount) continue
                if (src.startsWith("~") || src.startsWith("\$")) continue // home/env-expanded, can't verify
                val resolved = if (File(src).isAbsolute) File(src) else File(baseDir, src)
                if (!resolved.exists()) {
                    out += Diagnostic(
                        id = "missing-bind-path",
                        severity = Severity.WARNING,
                        service = svc.name,
                        title = "Bind mount source '$src' does not exist",
                        detail = "Service '${svc.name}' bind-mounts '$src' (resolved to '${resolved.path}'), " +
                            "which is not present on disk. Docker will create it as an empty root-owned " +
                            "directory, which often surfaces as missing config or permission errors.",
                        hint = "Create the path or fix the relative path in the compose file.",
                    )
                }
            }
        }
    }

    private fun checkEnvFiles(project: ComposeProject, baseDir: File?, out: MutableList<Diagnostic>) {
        baseDir ?: return
        for (svc in project.services) {
            for (envFile in svc.envFiles) {
                if (envFile.startsWith("~") || envFile.startsWith("\$")) continue
                val resolved = if (File(envFile).isAbsolute) File(envFile) else File(baseDir, envFile)
                if (!resolved.exists()) {
                    out += Diagnostic(
                        id = "missing-env-file",
                        severity = Severity.WARNING,
                        service = svc.name,
                        title = "env_file '$envFile' not found",
                        detail = "Service '${svc.name}' references env_file '$envFile' (resolved to " +
                            "'${resolved.path}'), which doesn't exist. The referenced variables will be empty.",
                        hint = "Create the env file or correct the path.",
                    )
                }
            }
        }
    }

    private fun checkReverseProxyNoPorts(project: ComposeProject, out: MutableList<Diagnostic>) {
        for (svc in project.services) {
            if (!looksLikeReverseProxy(svc)) continue
            if (svc.ports.any { it.isPublished }) continue
            out += Diagnostic(
                id = "proxy-no-published-ports",
                severity = Severity.WARNING,
                service = svc.name,
                title = "Reverse proxy '${svc.name}' publishes no ports",
                detail = "'${svc.name}' looks like a reverse proxy but doesn't publish any host ports, so " +
                    "nothing outside the Compose network can reach it. A proxy is normally the one service " +
                    "that should publish 80/443.",
                hint = "Add a 'ports:' entry (e.g. '80:80' and '443:443').",
            )
        }
    }

    private fun looksLikeReverseProxy(svc: ComposeService): Boolean {
        val haystack = "${svc.name} ${svc.image ?: ""}".lowercase()
        return REVERSE_PROXY_HINTS.any { it in haystack }
    }
}
