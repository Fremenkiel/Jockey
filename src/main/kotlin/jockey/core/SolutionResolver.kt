package jockey.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.nio.file.Files
import java.nio.file.Path

/** Maps a folder (usually a git repository root) to the Rider solution that is open for it. */
object SolutionResolver {

    fun openSolutions(): List<Project> =
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }

    fun describe(project: Project) = SolutionInfo(project.name, project.basePath)

    /**
     * Picks the open solution whose directory contains [repoPath], or that lies inside it.
     * If [repoPath] is blank, [fallback] or the only open solution is used.
     */
    fun resolve(repoPath: String?, fallback: Project? = null): Project {
        val solutions = openSolutions()
        if (repoPath.isNullOrBlank()) {
            return fallback ?: solutions.singleOrNull()
                ?: throw JockeyException(
                    if (solutions.isEmpty()) "No solution is open in Rider."
                    else "Several solutions are open. Pass repoPath. Open: ${solutions.joinToString { it.basePath ?: it.name }}"
                )
        }

        val target = normalize(Path.of(repoPath))
        val matches = solutions.mapNotNull { project ->
            val base = project.basePath?.let { normalize(Path.of(it)) } ?: return@mapNotNull null
            when {
                // The solution is inside the repository. Prefer the one closest to the repository root.
                base.startsWith(target) -> project to base.nameCount - target.nameCount
                // The repository path is inside the solution directory.
                target.startsWith(base) -> project to target.nameCount - base.nameCount
                else -> null
            }
        }
        return matches.minByOrNull { it.second }?.first
            ?: throw JockeyException(
                "No open Rider solution matches $repoPath. Open: ${solutions.joinToString { it.basePath ?: it.name }}",
                httpStatus = 404,
            )
    }

    fun normalize(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        return if (Files.exists(absolute)) absolute.toRealPath() else absolute
    }
}
