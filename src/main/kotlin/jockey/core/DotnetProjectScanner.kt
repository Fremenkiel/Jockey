package jockey.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

data class ScannedProject(
    val name: String,
    val projectFile: Path,
    val launchProfiles: List<LaunchProfileInfo>,
)

/** Finds .csproj/.fsproj files under a folder and reads their Properties/launchSettings.json. */
object DotnetProjectScanner {
    private val LOG = logger<DotnetProjectScanner>()
    private val skippedDirs = setOf("bin", "obj", "node_modules", ".git", ".idea", ".vs", "packages", "TestResults")
    private const val MAX_DEPTH = 10
    private const val MAX_PROJECTS = 500

    fun scan(root: Path): List<ScannedProject> {
        if (!Files.isDirectory(root)) return emptyList()
        val found = mutableListOf<ScannedProject>()
        Files.walkFileTree(root, emptySet(), MAX_DEPTH, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && dir.fileName.toString() in skippedDirs) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val fileName = file.fileName.toString()
                if (fileName.endsWith(".csproj") || fileName.endsWith(".fsproj")) {
                    found += ScannedProject(
                        name = fileName.substringBeforeLast('.'),
                        projectFile = file,
                        launchProfiles = readLaunchProfiles(file.parent.resolve("Properties/launchSettings.json")),
                    )
                }
                return if (found.size >= MAX_PROJECTS) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return found.sortedBy { it.name }
    }

    private fun readLaunchProfiles(launchSettings: Path): List<LaunchProfileInfo> {
        if (!Files.isRegularFile(launchSettings)) return emptyList()
        return try {
            // JsonParser.parseReader is lenient, so comments in launchSettings.json are accepted.
            val root = Files.newBufferedReader(launchSettings).use { JsonParser.parseReader(it) }.asJsonObject
            val profiles = root.getAsJsonObject("profiles") ?: return emptyList()
            profiles.entrySet().mapNotNull { (name, value) ->
                val profile = value as? JsonObject ?: return@mapNotNull null
                LaunchProfileInfo(
                    name = name,
                    commandName = profile.string("commandName"),
                    applicationUrls = profile.string("applicationUrl")
                        ?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }
                        ?: emptyList(),
                    launchUrl = profile.string("launchUrl"),
                )
            }
        } catch (e: Exception) {
            LOG.warn("Cannot read $launchSettings", e)
            emptyList()
        }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
