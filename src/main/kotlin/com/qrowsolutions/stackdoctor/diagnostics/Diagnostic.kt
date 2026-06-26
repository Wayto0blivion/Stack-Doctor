package com.qrowsolutions.stackdoctor.diagnostics

enum class Severity { ERROR, WARNING, INFO }

/**
 * A PSI-free description of *where* a [Diagnostic] lives inside the compose file, so the inline
 * inspection can resolve it back to a precise YAML element. Kept free of PSI types on purpose,
 * so the doctor checks stay pure and unit-testable.
 *
 * Resolution (see `parser.ComposePsi.resolveAnchor`) narrows from coarse to fine:
 *  - [service] `null` -> a top-level key (e.g. `volumes:`); otherwise that service's mapping.
 *  - [key] the sub-key inside the service (e.g. `ports`, `depends_on`); `null` -> the service name.
 *  - [value] a scalar to match inside that key's subtree (e.g. a single port or dependency name).
 */
data class DiagnosticAnchor(
    val service: String?,
    val key: String? = null,
    val value: String? = null,
)

/**
 * A single finding from the doctor. [service] is null for project-wide findings.
 * [detail] is a longer human explanation; [hint] is a one-line suggested fix.
 * [anchor] locates the finding in the file for the inline inspection (null = not anchorable).
 */
data class Diagnostic(
    val id: String,
    val severity: Severity,
    val service: String?,
    val title: String,
    val detail: String,
    val hint: String? = null,
    val anchor: DiagnosticAnchor? = null,
)
