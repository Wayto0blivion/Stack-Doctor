package com.qrowsolutions.stackdoctor.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/** Project-level holder so actions (e.g. Refresh) can reach the live tool-window panel. */
@Service(Service.Level.PROJECT)
class StackDoctorService(@Suppress("unused") private val project: Project) {
    var panel: StackDoctorPanel? = null

    companion object {
        fun getInstance(project: Project): StackDoctorService =
            project.getService(StackDoctorService::class.java)
    }
}
