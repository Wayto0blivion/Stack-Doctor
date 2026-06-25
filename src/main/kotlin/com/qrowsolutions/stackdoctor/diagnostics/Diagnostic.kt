package com.qrowsolutions.stackdoctor.diagnostics

enum class Severity { ERROR, WARNING, INFO }

/**
 * A single finding from the doctor. [service] is null for project-wide findings.
 * [detail] is a longer human explanation; [hint] is a one-line suggested fix.
 */
data class Diagnostic(
    val id: String,
    val severity: Severity,
    val service: String?,
    val title: String,
    val detail: String,
    val hint: String? = null,
)
