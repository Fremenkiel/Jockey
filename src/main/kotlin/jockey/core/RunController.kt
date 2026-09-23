package jockey.core

import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ExecutionManagerImpl
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.file.Path

/** Lists, starts, stops and restarts run configurations. All waits are bounded by the caller's timeout. */
object RunController {

    fun listSolutions() = SolutionsResult(SolutionResolver.openSolutions().map(SolutionResolver::describe))

    suspend fun list(project: Project, repoPath: String?): ProjectsResult = withContext(Dispatchers.IO) {
        val snapshot = Snapshot.take(project, repoPath)
        ProjectsResult(
            solution = SolutionResolver.describe(project),
            repoPath = snapshot.root.toString(),
            runConfigurations = snapshot.settings.map { snapshot.info(it) },
            projects = snapshot.projects.map { scanned ->
                ProjectInfo(
                    name = scanned.name,
                    projectFile = scanned.projectFile.toString(),
                    launchProfiles = scanned.launchProfiles,
                    runConfigurations = snapshot.settings.filter { snapshot.projectOf(it) == scanned }.map { it.name },
                )
            },
        )
    }

    suspend fun start(project: Project, repoPath: String?, name: String, debug: Boolean, waitSeconds: Int): ActionResult {
        val snapshot = withContext(Dispatchers.IO) { Snapshot.take(project, repoPath) }
        val settings = snapshot.find(name)
        if (runningDescriptors(project, settings).isNotEmpty()) {
            return snapshot.result(settings, "start", "already_running", ready = true, "The run configuration is already running. Use restart to rerun it.")
        }
        return launch(snapshot, settings, debug, waitSeconds, action = "start", successOutcome = "started")
    }

    suspend fun stop(project: Project, repoPath: String?, name: String, waitSeconds: Int): ActionResult {
        val snapshot = withContext(Dispatchers.IO) { Snapshot.take(project, repoPath) }
        val settings = snapshot.find(name)
        return if (terminate(project, settings, waitSeconds)) {
            snapshot.result(settings, "stop", "stopped", ready = false, null)
        } else {
            snapshot.result(settings, "stop", "timeout", ready = false, "The process did not stop within $waitSeconds seconds.")
        }
    }

    suspend fun restart(project: Project, repoPath: String?, name: String, debug: Boolean, waitSeconds: Int): ActionResult {
        val snapshot = withContext(Dispatchers.IO) { Snapshot.take(project, repoPath) }
        val settings = snapshot.find(name)
        if (!terminate(project, settings, STOP_TIMEOUT_SECONDS)) {
            return snapshot.result(settings, "restart", "timeout", ready = false, "The old process did not stop within $STOP_TIMEOUT_SECONDS seconds.")
        }
        return launch(snapshot, settings, debug, waitSeconds, action = "restart", successOutcome = "restarted")
    }

    fun logs(project: Project, repoPath: String?, name: String, tail: Int): LogsResult {
        val snapshot = Snapshot.take(project, repoPath, scanProjects = false)
        val settings = snapshot.find(name)
        val (lines, truncated) = project.service<RunStateService>().find(settings.uniqueID)?.tail(tail) ?: (emptyList<String>() to false)
        return LogsResult(settings.name, snapshot.info(settings).state, lines, truncated)
    }

    private suspend fun launch(
        snapshot: Snapshot,
        settings: RunnerAndConfigurationSettings,
        debug: Boolean,
        waitSeconds: Int,
        action: String,
        successOutcome: String,
    ): ActionResult {
        val project = snapshot.project
        val record = project.service<RunStateService>().record(settings.uniqueID)
        val executor = if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance()

        val scheduledBefore = record.scheduledCount
        withContext(Dispatchers.EDT) {
            // Use the target that fits the configuration, as Rider's own MCP run tool does. With the default
            // target, ProgramRunnerUtil can refuse the launch without any run event or log line.
            val target = ExecutionTargetManager.getInstance(project).findTarget(settings.configuration)
            val environment = ExecutionEnvironmentBuilder.createOrNull(executor, settings)?.target(target)?.build()
                ?: throw JockeyException("Rider has no ${executor.id} runner for '${settings.name}'.")
            // showSettings = false: never open a modal dialog, because no one is at the keyboard.
            ProgramRunnerUtil.executeConfiguration(environment, false, true)
        }

        // Rider reports processStartScheduled before the build starts. If that event does not come, Rider refused the launch.
        val acceptDeadline = System.nanoTime() + ACCEPT_TIMEOUT_SECONDS * 1_000_000_000L
        while (record.scheduledCount == scheduledBefore) {
            if (System.nanoTime() > acceptDeadline) {
                return snapshot.result(
                    settings, action, "failed", ready = false,
                    "Rider did not accept the launch within $ACCEPT_TIMEOUT_SECONDS seconds. Look for an error notification or an open dialog in Rider.",
                )
            }
            delay(100)
        }

        if (waitSeconds <= 0) return snapshot.result(settings, action, successOutcome, ready = false, "Launch requested. Poll the project state to follow it.")

        val urls = snapshot.urlsOf(settings)
        val deadline = System.nanoTime() + waitSeconds * 1_000_000_000L
        var seenRunning = false
        while (System.nanoTime() < deadline) {
            val running = runningDescriptors(project, settings).isNotEmpty()
            if (running) seenRunning = true
            when {
                record.state == RunState.FAILED ->
                    return snapshot.result(settings, action, "failed", ready = false, record.lastError)
                seenRunning && !running ->
                    return snapshot.result(settings, action, "failed", ready = false, "The process exited during startup (exit code ${record.lastExitCode}).")
                running && isReady(record, urls) ->
                    return snapshot.result(settings, action, successOutcome, ready = true, null)
            }
            delay(POLL_MILLIS)
        }
        return snapshot.result(
            settings, action, "timeout", ready = false,
            if (seenRunning) "The process runs, but it did not accept connections within $waitSeconds seconds."
            else "The process did not start within $waitSeconds seconds. The build can still be in progress.",
        )
    }

    /** Stops every running instance of [settings]. Returns false if a process is still alive at the timeout. */
    private suspend fun terminate(project: Project, settings: RunnerAndConfigurationSettings, waitSeconds: Int): Boolean {
        val descriptors = runningDescriptors(project, settings)
        if (descriptors.isEmpty()) return true
        withContext(Dispatchers.EDT) { descriptors.forEach(ExecutionManagerImpl::stopProcess) }

        val start = System.nanoTime()
        val timeout = waitSeconds.coerceAtLeast(1) * 1_000_000_000L
        var killed = false
        while (System.nanoTime() - start < timeout) {
            if (runningDescriptors(project, settings).isEmpty()) return true
            // A process that ignores the soft stop for half the timeout gets killed.
            if (!killed && System.nanoTime() - start > timeout / 2) {
                descriptors.forEach { (it.processHandler as? KillableProcessHandler)?.killProcess() }
                killed = true
            }
            delay(POLL_MILLIS)
        }
        return runningDescriptors(project, settings).isEmpty()
    }

    private suspend fun isReady(record: RunRecord, urls: List<String>): Boolean {
        if (record.listeningUrls.isNotEmpty() || record.applicationStarted) return true
        if (urls.isEmpty()) return true
        return withContext(Dispatchers.IO) { urls.any(::acceptsConnections) }
    }

    private fun acceptsConnections(url: String): Boolean = try {
        val uri = URI(url.replace("://*", "://localhost").replace("://+", "://localhost").replace("://0.0.0.0", "://localhost"))
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
        Socket().use { it.connect(InetSocketAddress(uri.host ?: "localhost", port), 300); true }
    } catch (_: Exception) {
        false
    }

    private fun runningDescriptors(project: Project, settings: RunnerAndConfigurationSettings): List<RunContentDescriptor> =
        ExecutionManagerImpl.getInstance(project)
            .getRunningDescriptors { it.uniqueID == settings.uniqueID }
            .filter(ExecutionManagerImpl::isProcessRunning)

    private const val POLL_MILLIS = 500L
    private const val STOP_TIMEOUT_SECONDS = 30
    private const val ACCEPT_TIMEOUT_SECONDS = 10

    /** One consistent view of the run configurations and the .NET projects on disk. */
    private class Snapshot(
        val project: Project,
        val root: Path,
        val settings: List<RunnerAndConfigurationSettings>,
        val projects: List<ScannedProject>,
    ) {
        private val byFile = projects.associateBy { it.projectFile.toString() }
        private val byName = projects.groupBy { it.name }

        fun find(name: String): RunnerAndConfigurationSettings {
            settings.firstOrNull { it.name == name }?.let { return it }
            val byProject = settings.filter { projectNameOf(it) == name }
            return when (byProject.size) {
                1 -> byProject.single()
                0 -> throw JockeyException("No run configuration named '$name'. Available: ${settings.joinToString { it.name }}", httpStatus = 404)
                else -> throw JockeyException("'$name' matches several run configurations. Pass one of: ${byProject.joinToString { it.name }}")
            }
        }

        fun projectOf(settings: RunnerAndConfigurationSettings): ScannedProject? {
            projectFileOf(settings.configuration)?.let { file ->
                byFile[SolutionResolver.normalize(Path.of(file)).toString()]?.let { return it }
            }
            return byName[settings.name.substringBefore(": ")]?.singleOrNull()
        }

        private fun projectNameOf(settings: RunnerAndConfigurationSettings): String =
            projectOf(settings)?.name
                ?: projectFileOf(settings.configuration)?.let { Path.of(it).fileName.toString().substringBeforeLast('.') }
                ?: settings.name.substringBefore(": ")

        private fun profileOf(settings: RunnerAndConfigurationSettings): LaunchProfileInfo? {
            val profiles = projectOf(settings)?.launchProfiles ?: return null
            val profileName = profileNameOf(settings.configuration)
                ?: settings.name.substringAfter(": ", missingDelimiterValue = "").ifEmpty { null }
            return profiles.firstOrNull { it.name == profileName }
                ?: profiles.firstOrNull { it.commandName == "Project" }
        }

        fun urlsOf(settings: RunnerAndConfigurationSettings): List<String> = profileOf(settings)?.applicationUrls ?: emptyList()

        fun info(settings: RunnerAndConfigurationSettings): RunConfigurationInfo {
            val running = runningDescriptors(project, settings)
            val record = project.service<RunStateService>().find(settings.uniqueID)
            val state = when {
                running.isNotEmpty() -> if (record?.state == RunState.STOPPING) RunState.STOPPING else RunState.RUNNING
                record?.state == RunState.STARTING -> RunState.STARTING
                record?.state == RunState.FAILED -> RunState.FAILED
                else -> RunState.STOPPED
            }
            val executorId = record?.executorId?.takeIf { state != RunState.STOPPED }
                ?: running.firstOrNull()?.let { ExecutionManagerImpl.getInstance(project).getExecutors(it).firstOrNull()?.id }
            val profile = profileOf(settings)
            val scanned = projectOf(settings)
            return RunConfigurationInfo(
                name = settings.name,
                type = settings.type.displayName,
                project = scanned?.name ?: projectNameOf(settings),
                projectFile = scanned?.projectFile?.toString() ?: projectFileOf(settings.configuration),
                launchProfile = profile?.name,
                state = state,
                executor = executorId?.lowercase(),
                pid = running.firstOrNull()?.let { pidOf(it) },
                startedAt = record?.startedAt?.takeIf { running.isNotEmpty() }?.toString(),
                lastExitCode = record?.lastExitCode,
                lastError = record?.lastError,
                applicationUrls = profile?.applicationUrls ?: emptyList(),
                listeningUrls = if (running.isNotEmpty()) record?.listeningUrls?.toList() ?: emptyList() else emptyList(),
                launchUrl = profile?.launchUrl,
            )
        }

        fun result(settings: RunnerAndConfigurationSettings, action: String, outcome: String, ready: Boolean, message: String?): ActionResult {
            val record = project.service<RunStateService>().find(settings.uniqueID)
            val logTail = if (outcome == "failed" || outcome == "timeout") record?.tail(LOG_TAIL_ON_ERROR)?.first ?: emptyList() else emptyList()
            return ActionResult(action, outcome, ready, message, info(settings), logTail)
        }

        companion object {
            const val LOG_TAIL_ON_ERROR = 60

            fun take(project: Project, repoPath: String?, scanProjects: Boolean = true): Snapshot {
                val root = SolutionResolver.normalize(Path.of(repoPath?.takeIf { it.isNotBlank() } ?: project.basePath ?: "."))
                val settings = RunManager.getInstance(project).allSettings.filter { !it.isTemplate }
                val projects = if (scanProjects) DotnetProjectScanner.scan(root) else emptyList()
                return Snapshot(project, root, settings, projects)
            }

            private fun pidOf(descriptor: RunContentDescriptor): Long? =
                runCatching { (descriptor.processHandler as? BaseProcessHandler<*>)?.process?.pid() }.getOrNull()

            // Rider's .NET run configurations keep the project path in their parameters object.
            // The classes are internal to Rider, so read them by reflection and accept failure.
            private fun projectFileOf(configuration: RunConfiguration): String? =
                parameter(configuration, "getProjectFilePath")

            private fun profileNameOf(configuration: RunConfiguration): String? =
                parameter(configuration, "getProfileName") ?: parameter(configuration, "getLaunchProfileName")

            private fun parameter(configuration: RunConfiguration, getter: String): String? = runCatching {
                val parameters = configuration.javaClass.methods
                    .firstOrNull { it.name == "getParameters" && it.parameterCount == 0 }
                    ?.invoke(configuration) ?: return null
                parameters.javaClass.methods
                    .firstOrNull { it.name == getter && it.parameterCount == 0 }
                    ?.invoke(parameters) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }
}
