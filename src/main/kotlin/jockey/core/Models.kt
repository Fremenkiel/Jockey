package jockey.core

import kotlinx.serialization.Serializable

@Serializable
enum class RunState { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

@Serializable
data class SolutionInfo(
    val name: String,
    val directory: String?,
)

@Serializable
data class SolutionsResult(
    val solutions: List<SolutionInfo>,
)

@Serializable
data class LaunchProfileInfo(
    val name: String,
    val commandName: String?,
    val applicationUrls: List<String>,
    val launchUrl: String?,
)

@Serializable
data class ProjectInfo(
    val name: String,
    val projectFile: String,
    val launchProfiles: List<LaunchProfileInfo>,
    val runConfigurations: List<String>,
)

@Serializable
data class RunConfigurationInfo(
    val name: String,
    val type: String,
    val project: String?,
    val projectFile: String?,
    val launchProfile: String?,
    val state: RunState,
    val executor: String?,
    val pid: Long?,
    val startedAt: String?,
    val lastExitCode: Int?,
    val lastError: String?,
    val applicationUrls: List<String>,
    val listeningUrls: List<String>,
    val launchUrl: String?,
)

@Serializable
data class ProjectsResult(
    val solution: SolutionInfo,
    val repoPath: String,
    val runConfigurations: List<RunConfigurationInfo>,
    val projects: List<ProjectInfo>,
)

@Serializable
data class ActionResult(
    val action: String,
    /** started, already_running, stopped, already_stopped, restarted, timeout, failed */
    val outcome: String,
    val ready: Boolean,
    val message: String?,
    val runConfiguration: RunConfigurationInfo,
    val logTail: List<String>,
)

@Serializable
data class LogsResult(
    val name: String,
    val state: RunState,
    val lines: List<String>,
    val truncated: Boolean,
)

class JockeyException(message: String, val httpStatus: Int = 400) : Exception(message)
