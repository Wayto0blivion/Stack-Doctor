package com.qrowsolutions.stackdoctor.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.qrowsolutions.stackdoctor.ui.StackDoctorService

/** Re-scans the project for compose files and re-runs the doctor. */
class RefreshAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        StackDoctorService.getInstance(project).panel?.refresh()
    }
}
