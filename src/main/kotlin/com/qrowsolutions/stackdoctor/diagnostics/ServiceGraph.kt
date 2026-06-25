package com.qrowsolutions.stackdoctor.diagnostics

import com.qrowsolutions.stackdoctor.model.ComposeProject

/**
 * Directed dependency graph between services. An edge `a -> b` means `a` depends on `b`
 * (via `depends_on` or a legacy `link`), so `b` should start / be healthy first.
 */
class ServiceGraph(val project: ComposeProject) {

    /** service -> the services it depends on (only edges that point at a real service). */
    val edges: Map<String, List<String>> = buildMap {
        val known = project.serviceNames
        for (svc in project.services) {
            val deps = (svc.dependsOn + svc.links).distinct().filter { it in known && it != svc.name }
            put(svc.name, deps)
        }
    }

    val nodes: List<String> get() = project.serviceNames.toList()

    /** Reverse edges: service -> services that depend on it. */
    val dependents: Map<String, List<String>> by lazy {
        buildMap<String, MutableList<String>> {
            nodes.forEach { put(it, mutableListOf()) }
            edges.forEach { (from, tos) -> tos.forEach { to -> getValue(to).add(from) } }
        }
    }

    fun isDependedOn(service: String): Boolean = dependents[service]?.isNotEmpty() == true

    /**
     * Assigns each node a layer (0 = no dependencies) via longest-path layering.
     * Nodes involved in a cycle still get a finite layer so the graph always renders.
     */
    fun layers(): Map<String, Int> {
        val layer = HashMap<String, Int>()
        val visiting = HashSet<String>()

        fun depth(node: String): Int {
            layer[node]?.let { return it }
            if (!visiting.add(node)) return 0 // cycle guard
            val deps = edges[node].orEmpty()
            val d = if (deps.isEmpty()) 0 else (deps.maxOf { depth(it) } + 1)
            visiting.remove(node)
            layer[node] = d
            return d
        }

        nodes.forEach { depth(it) }
        return layer
    }

    /** Returns dependency cycles as lists of service names (each cycle returned once). */
    fun findCycles(): List<List<String>> {
        val cycles = mutableListOf<List<String>>()
        val seen = mutableSetOf<Set<String>>()
        val stack = mutableListOf<String>()
        val onStack = mutableSetOf<String>()
        val done = mutableSetOf<String>()

        fun dfs(node: String) {
            stack.add(node)
            onStack.add(node)
            for (next in edges[node].orEmpty()) {
                if (next in onStack) {
                    val cycle = stack.subList(stack.indexOf(next), stack.size).toList()
                    if (seen.add(cycle.toSet())) cycles.add(cycle)
                } else if (next !in done) {
                    dfs(next)
                }
            }
            stack.removeAt(stack.size - 1)
            onStack.remove(node)
            done.add(node)
        }

        nodes.forEach { if (it !in done) dfs(it) }
        return cycles
    }
}
