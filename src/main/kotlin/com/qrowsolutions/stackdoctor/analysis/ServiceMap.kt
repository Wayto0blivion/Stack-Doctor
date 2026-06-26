package com.qrowsolutions.stackdoctor.analysis

import com.qrowsolutions.stackdoctor.diagnostics.Severity
import com.qrowsolutions.stackdoctor.model.ComposeService

/** A node in the combined project map: either a compose file or a service inside one. */
sealed class MapNode {
    abstract val id: String
}

/** A compose file. Acts as the root that its services branch off of. */
class FileMapNode(
    override val id: String,
    val analysis: ComposeAnalysis,
    /** Project-relative path shown on the node. */
    val displayName: String,
) : MapNode()

/** A single service, owned by the file it was parsed from. */
class ServiceMapNode(
    override val id: String,
    val analysis: ComposeAnalysis,
    val service: ComposeService,
) : MapNode()

/**
 * Combines every analysed compose file into one map. Each file becomes a root node; its services
 * branch off to the right, keeping the file's own `depends_on` edges. Service names are namespaced
 * by file so the same name in two files stays distinct.
 */
class ServiceMap(val analyses: List<ComposeAnalysis>, basePath: String?) {

    val nodes: List<MapNode>

    /** node id -> the node ids it depends on (drawn as arrows). */
    val dependencyEdges: Map<String, List<String>>

    /** file node id -> its entry service node ids (drawn as light "contains" links, no arrow). */
    val containmentEdges: Map<String, List<String>>

    /** Worst severity attached to each node id, for tinting. */
    val worstSeverity: Map<String, Severity>

    private val byId: Map<String, MapNode>

    init {
        val nodeList = mutableListOf<MapNode>()
        val depEdges = LinkedHashMap<String, List<String>>()
        val containEdges = LinkedHashMap<String, List<String>>()
        val severity = HashMap<String, Severity>()

        analyses.forEachIndexed { idx, a ->
            val fileId = fileId(idx)
            nodeList += FileMapNode(fileId, a, displayName(a, basePath))

            a.project.services.forEach { svc ->
                nodeList += ServiceMapNode(serviceId(idx, svc.name), a, svc)
            }

            // Dependency edges (within the file), namespaced to this file's nodes.
            a.graph.edges.forEach { (from, tos) ->
                depEdges[serviceId(idx, from)] = tos.map { serviceId(idx, it) }
            }

            // Containment: link the file to its entry services — those nothing else in the file
            // depends on (includes orphan services, so no node is left floating).
            val roots = a.project.services
                .map { it.name }
                .filter { a.graph.dependents[it].isNullOrEmpty() }
                .map { serviceId(idx, it) }
            containEdges[fileId] = roots

            // Severity per node: a finding tints the service it concerns (or the file, when project-wide)
            // AND rolls up to the file node, so a service's warning is visible on its compose file too.
            a.diagnostics.forEach { d ->
                val nid = if (d.service != null) serviceId(idx, d.service) else fileId
                bump(severity, nid, d.severity)
                bump(severity, fileId, d.severity)
            }
        }

        nodes = nodeList
        dependencyEdges = depEdges
        containmentEdges = containEdges
        worstSeverity = severity
        byId = nodeList.associateBy { it.id }
    }

    fun node(id: String?): MapNode? = id?.let { byId[it] }

    private fun bump(into: MutableMap<String, Severity>, id: String, sev: Severity) {
        val current = into[id]
        if (current == null || sev.ordinal < current.ordinal) into[id] = sev
    }

    private fun fileId(idx: Int) = "file/$idx"
    private fun serviceId(idx: Int, name: String) = "$idx/$name"

    private fun displayName(a: ComposeAnalysis, basePath: String?): String {
        val path = a.project.filePath
        return if (basePath != null && path.startsWith(basePath)) {
            path.removePrefix(basePath).trimStart('/', '\\').ifEmpty { a.project.fileName }
        } else {
            a.project.fileName
        }
    }
}
