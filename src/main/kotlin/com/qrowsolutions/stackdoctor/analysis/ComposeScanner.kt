package com.qrowsolutions.stackdoctor.analysis

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.qrowsolutions.stackdoctor.diagnostics.Diagnostic
import com.qrowsolutions.stackdoctor.diagnostics.ServiceGraph
import com.qrowsolutions.stackdoctor.diagnostics.StackDoctor
import com.qrowsolutions.stackdoctor.model.ComposeProject
import com.qrowsolutions.stackdoctor.parser.ComposeParser
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile
import java.io.File

/** Full result of analysing one compose file. */
data class ComposeAnalysis(
    val project: ComposeProject,
    val graph: ServiceGraph,
    val diagnostics: List<Diagnostic>,
)

/**
 * Finds candidate compose files in a project and analyses them. All PSI access is wrapped in a
 * read action so callers may invoke this from any thread.
 */
object ComposeScanner {

    private val CANONICAL_NAMES = setOf(
        "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml",
    )

    /** Returns YAML files in the project that look like compose files, canonical names first. */
    fun findComposeFiles(project: Project): List<VirtualFile> = ReadAction.compute<List<VirtualFile>, RuntimeException> {
        if (project.isDisposed) return@compute emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        val yaml = FileTypeIndex.getFiles(YAMLFileType.YML, scope)
        yaml.filter { isComposeName(it.name) }
            .sortedWith(compareByDescending<VirtualFile> { it.name in CANONICAL_NAMES }.thenBy { it.path })
    }

    fun isComposeName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in CANONICAL_NAMES) return true
        return lower.contains("compose") && (lower.endsWith(".yml") || lower.endsWith(".yaml"))
    }

    /** Parses and runs the doctor over a single compose file, or null if it isn't a usable compose file. */
    fun analyze(project: Project, file: VirtualFile): ComposeAnalysis? =
        ReadAction.compute<ComposeAnalysis?, RuntimeException> {
            if (project.isDisposed || !file.isValid) return@compute null
            val yamlFile = PsiManager.getInstance(project).findFile(file) as? YAMLFile ?: return@compute null
            val composeProject = ComposeParser.parse(yamlFile) ?: return@compute null
            if (composeProject.services.isEmpty()) return@compute null
            val baseDir = file.parent?.path?.let { File(it) }
            val diagnostics = StackDoctor.run(composeProject, baseDir)
            ComposeAnalysis(composeProject, ServiceGraph(composeProject), diagnostics)
        }
}
