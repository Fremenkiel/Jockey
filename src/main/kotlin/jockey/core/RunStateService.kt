package jockey.core

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Keeps state, exit codes and console output for each run configuration, keyed by its unique ID. */
@Service(Service.Level.PROJECT)
class RunStateService {
    private val records = ConcurrentHashMap<String, RunRecord>()

    fun record(configurationId: String): RunRecord = records.computeIfAbsent(configurationId) { RunRecord() }

    fun find(configurationId: String): RunRecord? = records[configurationId]
}

class RunRecord {
    @Volatile var state: RunState = RunState.STOPPED
    @Volatile var executorId: String? = null
    @Volatile var handler: ProcessHandler? = null
    @Volatile var startedAt: Instant? = null
    @Volatile var lastExitCode: Int? = null
    @Volatile var lastError: String? = null
    @Volatile var applicationStarted: Boolean = false
    @Volatile private var terminationRequested = false
    val listeningUrls = CopyOnWriteArrayList<String>()

    private val lines = ArrayDeque<String>()
    private val partialLine = StringBuilder()
    private var droppedLines = false

    /** Counts launch requests that Rider accepted. A start that does not raise it was refused. */
    @Volatile var scheduledCount: Long = 0
        private set

    fun onScheduled(executorId: String) {
        scheduledCount++
        if (state == RunState.RUNNING) return
        state = RunState.STARTING
        this.executorId = executorId
        lastError = null
    }

    fun onNotStarted() {
        state = RunState.FAILED
        handler = null
        lastError = "The process did not start. The build failed or the launch was cancelled. Read the logs or the Build tool window."
    }

    fun onStarted(executorId: String, handler: ProcessHandler) {
        this.executorId = executorId
        this.handler = handler
        startedAt = Instant.now()
        lastExitCode = null
        terminationRequested = false
        lastError = null
        clearOutput()
        state = RunState.RUNNING
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = append(event.text)
        })
    }

    fun onTerminating(handler: ProcessHandler) {
        if (handler !== this.handler) return
        terminationRequested = true
        state = RunState.STOPPING
    }

    fun onTerminated(handler: ProcessHandler, exitCode: Int) {
        if (handler !== this.handler) return
        this.handler = null
        lastExitCode = exitCode
        if (exitCode == 0 || terminationRequested) {
            state = RunState.STOPPED
        } else {
            state = RunState.FAILED
            lastError = "The process exited with code $exitCode."
        }
    }

    fun append(text: String) = synchronized(lines) {
        partialLine.append(text)
        var newline = partialLine.indexOf("\n")
        while (newline >= 0) {
            addLine(partialLine.substring(0, newline).trimEnd('\r'))
            partialLine.delete(0, newline + 1)
            newline = partialLine.indexOf("\n")
        }
    }

    private fun addLine(line: String) {
        LISTENING.find(line)?.let { match -> listeningUrls.addIfAbsent(match.groupValues[1]) }
        if (line.contains("Application started.")) applicationStarted = true
        lines.addLast(line)
        if (lines.size > MAX_LINES) {
            lines.removeFirst()
            droppedLines = true
        }
    }

    /** Returns the last [count] lines and whether older lines exist. */
    fun tail(count: Int): Pair<List<String>, Boolean> = synchronized(lines) {
        val all = if (partialLine.isEmpty()) lines.toList() else lines.toList() + partialLine.toString()
        val result = all.takeLast(count.coerceAtLeast(0))
        result to (droppedLines || result.size < all.size)
    }

    private fun clearOutput() = synchronized(lines) {
        lines.clear()
        partialLine.setLength(0)
        droppedLines = false
        listeningUrls.clear()
        applicationStarted = false
    }

    private companion object {
        const val MAX_LINES = 5000
        val LISTENING = Regex("""Now listening on:\s*(\S+)""")
    }
}

/** Receives run events for every run configuration in the project, including runs started by hand in Rider. */
class RunStateListener(private val project: Project) : ExecutionListener {
    private fun record(env: ExecutionEnvironment): RunRecord? =
        env.runnerAndConfigurationSettings?.let { project.service<RunStateService>().record(it.uniqueID) }

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        record(env)?.onScheduled(executorId)
    }

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
        record(env)?.onNotStarted()
    }

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        record(env)?.onStarted(executorId, handler)
    }

    override fun processTerminating(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        record(env)?.onTerminating(handler)
    }

    override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
        record(env)?.onTerminated(handler, exitCode)
    }
}
