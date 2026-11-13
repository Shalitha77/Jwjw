package com.example.engine

enum class ProjectSourceType(val label: String) {
    ZIP_ARCHIVE("ZIP Archive"),
    DIRECTORY_TREE("Project Folder"),
    GENERATED_STARTER("Starter Studio Project")
}

enum class FileKind(val label: String) {
    GRADLE("Gradle"),
    MANIFEST("Manifest"),
    KOTLIN("Kotlin"),
    JAVA("Java"),
    XML("XML"),
    RESOURCE("Resource"),
    DEX_OR_BIN("Binary / DEX"),
    OTHER("Other")
}

enum class BuildVariant(val label: String) {
    DEBUG("Debug"),
    RELEASE("Release")
}

data class ProjectFileEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val kind: FileKind
)

data class GradleBuildConfig(
    val rootProjectName: String,
    val namespace: String,
    val applicationId: String,
    val minSdk: Int,
    val targetSdk: Int,
    val compileSdk: Int,
    val versionCode: Int,
    val versionName: String,
    val gradleWrapperVersion: String,
    val plugins: List<String>,
    val dependencies: List<String>,
    val usesCompose: Boolean,
    val usesKotlin: Boolean
)

data class ManifestSummary(
    val appLabel: String,
    val launcherActivity: String,
    val permissions: List<String>,
    val activities: List<String>,
    val rawManifestXml: String
)

data class AndroidStudioProject(
    val id: String,
    val projectName: String,
    val sourceType: ProjectSourceType,
    val sourceDisplayName: String,
    val workspaceDirPath: String,
    val gradleConfig: GradleBuildConfig,
    val manifestSummary: ManifestSummary,
    val files: List<ProjectFileEntry>,
    val hasPrecompiledDex: Boolean,
    val importedAtMillis: Long
) {
    val kotlinFileCount: Int get() = files.count { it.kind == FileKind.KOTLIN }
    val javaFileCount: Int get() = files.count { it.kind == FileKind.JAVA }
    val xmlFileCount: Int get() = files.count { it.kind == FileKind.XML || it.kind == FileKind.MANIFEST }
    val resourceFileCount: Int get() = files.count { it.kind == FileKind.RESOURCE }
    val totalSizeBytes: Long get() = files.sumOf { it.sizeBytes }
}

data class EditableBuildOverride(
    val appLabel: String,
    val applicationId: String,
    val versionName: String,
    val versionCode: String,
    val minSdk: String,
    val targetSdk: String,
    val variant: BuildVariant
)

enum class BuildStage(val stepIndex: Int, val title: String) {
    PARSING(1, "Scanning Gradle & Manifest"),
    RESOURCES(2, "Compiling AXML & resources.arsc"),
    DEX(3, "Packaging Dalvik classes.dex"),
    PACKAGING(4, "Assembling & Zip-Aligning APK"),
    SIGNING(5, "Signing with RSA-2048 Keystore"),
    DONE(6, "Build Complete")
}

data class BuildProgressState(
    val isRunning: Boolean = false,
    val currentStage: BuildStage = BuildStage.PARSING,
    val progressFraction: Float = 0f,
    val logLines: List<String> = emptyList(),
    val lastError: String? = null
)

data class ApkEntryInfo(
    val name: String,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val isStoredUncompressed: Boolean,
    val crcHex: String
)

data class ApkInspectionResult(
    val fileName: String,
    val totalSizeBytes: Long,
    val sha256: String,
    val entryCount: Int,
    val hasBinaryManifest: Boolean,
    val hasResourcesArsc: Boolean,
    val dexFiles: List<String>,
    val hasV1Signature: Boolean,
    val signatureFiles: List<String>,
    val manifestByteSize: Long,
    val entries: List<ApkEntryInfo>
)

data class SigningCertSummary(
    val subjectDn: String,
    val sha256Fingerprint: String,
    val sha1Fingerprint: String,
    val algorithm: String,
    val validFrom: String,
    val validUntil: String,
    val serialHex: String
)
