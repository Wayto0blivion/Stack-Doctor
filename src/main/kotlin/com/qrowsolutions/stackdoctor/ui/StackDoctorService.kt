package com.qrowsolutions.stackdoctor.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.awt.Point

/** Project-level holder so actions (e.g. Refresh) can reach the live tool-window panel. */
@Service(Service.Level.PROJECT)
class StackDoctorService(@Suppress("unused") private val project: Project) {
    var panel: StackDoctorPanel? = null

    /**
     * User-dragged node positions, keyed by map-node id (e.g. `0/api`, `file/0`). Held here rather
     * than in the panel so a hand-arranged layout survives a Refresh (which rebuilds the panel).
     * Session-scoped: not persisted across IDE restarts. Cleared by "Reset layout".
     */
    val nodePositions: MutableMap<String, Point> = HashMap()

    companion object {
        fun getInstance(project: Project): StackDoctorService =
            project.getService(StackDoctorService::class.java)
    }
}
