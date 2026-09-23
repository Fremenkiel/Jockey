@file:Suppress("FunctionName")

package jockey.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.project.Project
import kotlinx.coroutines.currentCoroutineContext
import jockey.core.ActionResult
import jockey.core.LogsResult
import jockey.core.ProjectsResult
import jockey.core.JockeyException
import jockey.core.RunController
import jockey.core.SolutionResolver
import jockey.core.SolutionsResult

/** MCP tools on the IDE's built-in MCP server. They share the logic of the HTTP API. */
class JockeyToolset : McpToolset {

    @McpTool(name = "rider_list_solutions")
    @McpDescription("Lists the solutions that are open in Rider, with their directories.")
    suspend fun rider_list_solutions(): SolutionsResult = RunController.listSolutions()

    @McpTool(name = "dotnet_list_projects")
    @McpDescription(
        """Lists the run configurations of the Rider solution for a folder, with state (STOPPED, STARTING, RUNNING,
        STOPPING, FAILED), executor, pid, URLs from launchSettings.json, URLs the app reports it listens on, and the
        .NET projects found under the folder. Use the 'name' of a run configuration in the other tools."""
    )
    suspend fun dotnet_list_projects(
        @McpDescription("Absolute path of the git repository or solution folder. Optional if only one solution is open.")
        repoPath: String? = null,
    ): ProjectsResult = call { RunController.list(project(repoPath), repoPath) }

    @McpTool(name = "dotnet_start_project")
    @McpDescription(
        """Builds and starts a run configuration in Rider. With waitSeconds > 0 the call returns when the app accepts
        connections, exits, or the time runs out. If it fails, the result contains the last console lines."""
    )
    suspend fun dotnet_start_project(
        @McpDescription("Run configuration name, or a project name if it has exactly one run configuration.")
        name: String,
        @McpDescription("Absolute path of the git repository or solution folder.")
        repoPath: String? = null,
        @McpDescription("Start with the debugger attached.")
        debug: Boolean = false,
        @McpDescription("Seconds to wait until the app is ready. 0 returns at once.")
        waitSeconds: Int = 120,
    ): ActionResult = call { RunController.start(project(repoPath), repoPath, name, debug, waitSeconds) }

    @McpTool(name = "dotnet_stop_project")
    @McpDescription("Stops all running instances of a run configuration.")
    suspend fun dotnet_stop_project(
        @McpDescription("Run configuration name, or a project name if it has exactly one run configuration.")
        name: String,
        @McpDescription("Absolute path of the git repository or solution folder.")
        repoPath: String? = null,
        @McpDescription("Seconds to wait for the process to exit. The process is killed after half of this time.")
        waitSeconds: Int = 30,
    ): ActionResult = call { RunController.stop(project(repoPath), repoPath, name, waitSeconds) }

    @McpTool(name = "dotnet_restart_project")
    @McpDescription(
        """Stops a run configuration, rebuilds it and starts it again. Use this after code changes, for example
        before you download a new swagger.json or run smoke tests."""
    )
    suspend fun dotnet_restart_project(
        @McpDescription("Run configuration name, or a project name if it has exactly one run configuration.")
        name: String,
        @McpDescription("Absolute path of the git repository or solution folder.")
        repoPath: String? = null,
        @McpDescription("Start with the debugger attached.")
        debug: Boolean = false,
        @McpDescription("Seconds to wait until the app is ready. 0 returns at once.")
        waitSeconds: Int = 120,
    ): ActionResult = call { RunController.restart(project(repoPath), repoPath, name, debug, waitSeconds) }

    @McpTool(name = "dotnet_project_logs")
    @McpDescription("Returns the console output of the current or last run of a run configuration.")
    suspend fun dotnet_project_logs(
        @McpDescription("Run configuration name, or a project name if it has exactly one run configuration.")
        name: String,
        @McpDescription("Absolute path of the git repository or solution folder.")
        repoPath: String? = null,
        @McpDescription("Number of lines from the end.")
        tail: Int = 200,
    ): LogsResult = call { RunController.logs(project(repoPath), repoPath, name, tail) }

    private suspend fun project(repoPath: String?): Project =
        SolutionResolver.resolve(repoPath, fallback = currentCoroutineContext().projectOrNull)

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: JockeyException) {
        mcpFail(e.message ?: "Error")
    }
}
