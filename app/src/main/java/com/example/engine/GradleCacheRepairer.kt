package com.example.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class GradleCacheDiagnostic(
    val hasGradleProperties: Boolean,
    val cachingEnabled: Boolean,
    val configCacheEnabled: Boolean,
    val hasValidWrapperUrl: Boolean,
    val staleCacheItemsCount: Int,
    val hasLocalPropertiesConflict: Boolean,
    val issues: List<String>
) {
    val needsRepair: Boolean get() = issues.isNotEmpty()
}

data class GradleCacheRestoreReport(
    val removedCacheEntries: Int,
    val restoredFiles: List<String>,
    val summary: String
)

object GradleCacheRepairer {

    fun inspectWorkspace(workspaceDir: File): GradleCacheDiagnostic {
        val issues = mutableListOf<String>()
        val gradlePropsFile = File(workspaceDir, "gradle.properties")
        val hasGradleProps = gradlePropsFile.exists()
        val propsText = if (hasGradleProps) runCatching { gradlePropsFile.readText() }.getOrDefault("") else ""

        val cachingEnabled = propsText.contains(Regex("""org\.gradle\.caching\s*=\s*true"""))
        val configCacheEnabled = propsText.contains(Regex("""org\.gradle\.configuration-cache\s*=\s*true"""))

        if (!hasGradleProps) {
            issues.add("Missing gradle.properties (JVM memory & build cache defaults unset)")
        } else {
            if (!cachingEnabled) {
                issues.add("Gradle build cache is disabled (org.gradle.caching != true)")
            }
            if (configCacheEnabled) {
                issues.add("Configuration cache enabled — can trigger serialization errors with older AGP plugins")
            }
        }

        val wrapperFile = File(workspaceDir, "gradle/wrapper/gradle-wrapper.properties")
        val wrapperText = if (wrapperFile.exists()) runCatching { wrapperFile.readText() }.getOrDefault("") else ""
        val hasValidWrapperUrl = wrapperText.contains("distributionUrl=") && wrapperText.contains("gradle-")
        if (!hasValidWrapperUrl) {
            issues.add("Missing or invalid gradle-wrapper.properties distributionUrl")
        }

        val localProps = File(workspaceDir, "local.properties")
        val hasLocalConflict = localProps.exists() && runCatching {
            localProps.readText().contains("sdk.dir=")
        }.getOrDefault(false)
        if (hasLocalConflict) {
            issues.add("Hardcoded sdk.dir in local.properties may fail across machines")
        }

        val staleItems = findStaleCacheEntries(workspaceDir)
        if (staleItems.isNotEmpty()) {
            issues.add("Found ${staleItems.size} stale Gradle cache/lock or build intermediate entries")
        }

        return GradleCacheDiagnostic(
            hasGradleProperties = hasGradleProps,
            cachingEnabled = cachingEnabled,
            configCacheEnabled = configCacheEnabled,
            hasValidWrapperUrl = hasValidWrapperUrl,
            staleCacheItemsCount = staleItems.size,
            hasLocalPropertiesConflict = hasLocalConflict,
            issues = issues
        )
    }

    suspend fun restoreAndRepair(workspaceDir: File): GradleCacheRestoreReport = withContext(Dispatchers.IO) {
        var removedCount = 0
        val restoredFiles = mutableListOf<String>()

        // 1. Remove stale .gradle, .idea/caches, *.lock, *.lck, and build/intermediates entries
        findStaleCacheEntries(workspaceDir).forEach { target ->
            val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
            if (deleted) removedCount++
        }

        // 2. Remove hardcoded local.properties sdk.dir if present
        val localProps = File(workspaceDir, "local.properties")
        if (localProps.exists()) {
            val cleaned = localProps.readLines().filterNot { it.trim().startsWith("sdk.dir=") }
            if (cleaned.isEmpty()) {
                if (localProps.delete()) restoredFiles.add("Removed machine-specific local.properties")
            } else {
                localProps.writeText(cleaned.joinToString("\n") + "\n")
                restoredFiles.add("Cleaned sdk.dir from local.properties")
            }
        }

        // 3. Restore safe, clean gradle.properties
        val gradleProps = File(workspaceDir, "gradle.properties")
        val existingLines = if (gradleProps.exists()) gradleProps.readLines() else emptyList()
        val managedKeys = setOf(
            "org.gradle.jvmargs",
            "org.gradle.parallel",
            "org.gradle.caching",
            "org.gradle.configuration-cache",
            "android.useAndroidX",
            "kotlin.code.style"
        )
        val preserved = existingLines.filter { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("#") &&
                managedKeys.none { key -> trimmed.startsWith("$key=") }
        }
        val restoredPropsContent = buildString {
            appendLine("# Restored by APK Forge Gradle Cache Repair")
            appendLine("org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8")
            appendLine("org.gradle.parallel=true")
            appendLine("org.gradle.caching=true")
            appendLine("org.gradle.configuration-cache=false")
            appendLine("android.useAndroidX=true")
            appendLine("kotlin.code.style=official")
            preserved.forEach { appendLine(it) }
        }
        gradleProps.writeText(restoredPropsContent)
        restoredFiles.add("gradle.properties")

        // 4. Restore gradle-wrapper.properties if missing or invalid
        val wrapperFile = File(workspaceDir, "gradle/wrapper/gradle-wrapper.properties")
        val wrapperText = if (wrapperFile.exists()) runCatching { wrapperFile.readText() }.getOrDefault("") else ""
        if (!wrapperText.contains("distributionUrl=") || !wrapperText.contains("gradle-")) {
            wrapperFile.parentFile?.mkdirs()
            wrapperFile.writeText(
                """
                distributionBase=GRADLE_USER_HOME
                distributionPath=wrapper/dists
                distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip
                zipStoreBase=GRADLE_USER_HOME
                zipStorePath=wrapper/dists
                """.trimIndent() + "\n"
            )
            restoredFiles.add("gradle/wrapper/gradle-wrapper.properties")
        }

        GradleCacheRestoreReport(
            removedCacheEntries = removedCount,
            restoredFiles = restoredFiles,
            summary = "Restored Gradle cache & config (${restoredFiles.size} file(s) updated, $removedCount stale cache item(s) cleared)"
        )
    }

    private fun findStaleCacheEntries(workspaceDir: File): List<File> {
        val targets = mutableListOf<File>()
        workspaceDir.walkTopDown()
            .onEnter { dir ->
                val name = dir.name
                if (name == ".gradle" || name == "intermediates" || name == "tmp") {
                    targets.add(dir)
                    false
                } else {
                    true
                }
            }
            .forEach { file ->
                if (file.isFile && (file.name.endsWith(".lock") || file.name.endsWith(".lck") || file.name == "gc.properties")) {
                    targets.add(file)
                }
            }
        return targets
    }
}
