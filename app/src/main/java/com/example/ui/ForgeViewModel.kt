package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BuildRecordEntity
import com.example.data.ForgeDatabase
import com.example.data.ForgeRepository
import com.example.data.SigningPreferencesRepository
import com.example.data.SigningProfileConfig
import com.example.engine.AndroidStudioProject
import com.example.engine.AndroidStudioProjectImporter
import com.example.engine.ApkInspectionResult
import com.example.engine.ApkInspector
import com.example.engine.ApkSignerAndPackager
import com.example.engine.BuildProgressState
import com.example.engine.BuildStage
import com.example.engine.BuildVariant
import com.example.engine.EditableBuildOverride
import com.example.engine.GradleAndManifestParser
import com.example.engine.GradleCacheDiagnostic
import com.example.engine.GradleCacheRepairer
import com.example.engine.SigningCertSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

data class ForgeUiState(
    val activeProject: AndroidStudioProject? = null,
    val gradleDiagnostic: GradleCacheDiagnostic? = null,
    val override: EditableBuildOverride = EditableBuildOverride(
        appLabel = "",
        applicationId = "",
        versionName = "1.0.0",
        versionCode = "1",
        minSdk = "24",
        targetSdk = "35",
        variant = BuildVariant.DEBUG
    ),
    val isImporting: Boolean = false,
    val buildProgress: BuildProgressState = BuildProgressState(),
    val certSummary: SigningCertSummary? = null,
    val inspectedBuildRecord: BuildRecordEntity? = null,
    val inspectedApkResult: ApkInspectionResult? = null,
    val externalApkResult: ApkInspectionResult? = null,
    val isInspectingExternal: Boolean = false,
    val snackbarMessage: String? = null
)

class ForgeViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val repository = ForgeRepository(ForgeDatabase.getInstance(context).buildRecordDao())
    private val prefsRepo = SigningPreferencesRepository(context)

    val buildHistory: StateFlow<List<BuildRecordEntity>> = repository.allBuilds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val signingProfile: StateFlow<SigningProfileConfig> = prefsRepo.signingConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SigningProfileConfig())

    private val _uiState = MutableStateFlow(ForgeUiState())
    val uiState: StateFlow<ForgeUiState> = _uiState.asStateFlow()

    init {
        refreshSigningSummary(forceRegenerate = false)
    }

    fun importProjectZip(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, snackbarMessage = null) }
            val result = AndroidStudioProjectImporter.importFromZipUri(context, uri)
            result.onSuccess { project ->
                setLoadedProject(project, "Imported ${project.files.size} files from ${project.sourceDisplayName}")
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        snackbarMessage = "ZIP import failed: ${err.localizedMessage ?: "Invalid archive"}"
                    )
                }
            }
        }
    }

    fun importProjectFolder(treeUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, snackbarMessage = null) }
            val result = AndroidStudioProjectImporter.importFromTreeUri(context, treeUri)
            result.onSuccess { project ->
                setLoadedProject(project, "Imported ${project.files.size} files from folder")
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        snackbarMessage = "Folder import failed: ${err.localizedMessage ?: "Unreadable folder"}"
                    )
                }
            }
        }
    }

    fun createStarterProject(appName: String, packageName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, snackbarMessage = null) }
            val cleanName = appName.trim().ifBlank { "PulseNotes" }
            val cleanPkg = packageName.trim().ifBlank { "com.studio.pulsenotes" }
            val result = AndroidStudioProjectImporter.createStarterStudioProject(context, cleanName, cleanPkg)
            result.onSuccess { project ->
                setLoadedProject(project, "Created Android Studio project \"$cleanName\"")
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        snackbarMessage = "Starter project error: ${err.localizedMessage}"
                    )
                }
            }
        }
    }

    private fun setLoadedProject(project: AndroidStudioProject, message: String) {
        val diagnostic = GradleCacheRepairer.inspectWorkspace(File(project.workspaceDirPath))
        _uiState.update {
            it.copy(
                activeProject = project,
                gradleDiagnostic = diagnostic,
                isImporting = false,
                override = EditableBuildOverride(
                    appLabel = project.manifestSummary.appLabel,
                    applicationId = project.gradleConfig.applicationId,
                    versionName = project.gradleConfig.versionName,
                    versionCode = project.gradleConfig.versionCode.toString(),
                    minSdk = project.gradleConfig.minSdk.toString(),
                    targetSdk = project.gradleConfig.targetSdk.toString(),
                    variant = BuildVariant.DEBUG
                ),
                buildProgress = BuildProgressState(),
                snackbarMessage = message
            )
        }
    }

    fun restoreProjectGradleCache() {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            val workspaceDir = File(project.workspaceDirPath)
            val report = GradleCacheRepairer.restoreAndRepair(workspaceDir)
            val refreshed = withContext(Dispatchers.IO) {
                GradleAndManifestParser.scanAndParse(
                    id = project.id,
                    workspaceDir = workspaceDir,
                    sourceType = project.sourceType,
                    sourceDisplayName = project.sourceDisplayName
                )
            }
            val diagnostic = withContext(Dispatchers.IO) {
                GradleCacheRepairer.inspectWorkspace(workspaceDir)
            }
            _uiState.update {
                it.copy(
                    activeProject = refreshed,
                    gradleDiagnostic = diagnostic,
                    snackbarMessage = report.summary
                )
            }
        }
    }

    fun clearActiveProject() {
        _uiState.update {
            it.copy(
                activeProject = null,
                gradleDiagnostic = null,
                buildProgress = BuildProgressState(),
                snackbarMessage = "Workspace closed"
            )
        }
    }

    fun updateOverride(transform: (EditableBuildOverride) -> EditableBuildOverride) {
        _uiState.update { state ->
            state.copy(override = transform(state.override))
        }
    }

    fun convertProjectToApk() {
        val project = _uiState.value.activeProject ?: return
        val override = _uiState.value.override
        val profile = signingProfile.value

        if (_uiState.value.buildProgress.isRunning) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    buildProgress = BuildProgressState(
                        isRunning = true,
                        currentStage = BuildStage.PARSING,
                        progressFraction = 0.05f,
                        logLines = listOf("Initializing APK Forge pipeline for ${project.projectName}...")
                    )
                )
            }

            val result = ApkSignerAndPackager.buildAndSignApk(
                context = context,
                project = project,
                override = override,
                signingProfile = profile,
                onStage = { stage, fraction, logLine ->
                    _uiState.update { state ->
                        state.copy(
                            buildProgress = state.buildProgress.copy(
                                isRunning = true,
                                currentStage = stage,
                                progressFraction = fraction,
                                logLines = state.buildProgress.logLines + logLine
                            )
                        )
                    }
                }
            )

            result.onSuccess { output ->
                val inspection = ApkInspector.inspectFile(output.apkFile).getOrNull()
                val versionCodeInt = override.versionCode.toIntOrNull() ?: project.gradleConfig.versionCode
                val minSdkInt = override.minSdk.toIntOrNull() ?: project.gradleConfig.minSdk
                val targetSdkInt = override.targetSdk.toIntOrNull() ?: project.gradleConfig.targetSdk

                val record = BuildRecordEntity(
                    projectName = project.projectName,
                    packageName = override.applicationId.trim().ifBlank { project.gradleConfig.applicationId },
                    appLabel = override.appLabel.trim().ifBlank { project.projectName },
                    versionName = override.versionName.trim().ifBlank { project.gradleConfig.versionName },
                    versionCode = versionCodeInt,
                    buildVariant = override.variant.label,
                    minSdk = minSdkInt,
                    targetSdk = targetSdkInt,
                    apkFilePath = output.apkFile.absolutePath,
                    apkFileName = output.apkFile.name.substringAfter('_'),
                    apkSizeBytes = output.apkFile.length(),
                    sha256Fingerprint = output.sha256Fingerprint,
                    certSha256 = output.certSha256,
                    entryCount = output.entryCount,
                    hasBinaryManifest = inspection?.hasBinaryManifest ?: true,
                    hasDex = (inspection?.dexFiles?.isNotEmpty()) ?: true,
                    hasResourcesArsc = inspection?.hasResourcesArsc ?: true,
                    durationMs = output.durationMs,
                    status = "SUCCESS",
                    buildLog = output.logLines.joinToString("\n")
                )

                val insertedId = repository.insertBuild(record)
                val savedRecord = record.copy(id = insertedId)

                // Re-scan workspace since overrides were persisted to Gradle/strings.xml
                val refreshedProject = withContext(Dispatchers.IO) {
                    GradleAndManifestParser.scanAndParse(
                        id = project.id,
                        workspaceDir = File(project.workspaceDirPath),
                        sourceType = project.sourceType,
                        sourceDisplayName = project.sourceDisplayName
                    )
                }

                _uiState.update { state ->
                    state.copy(
                        activeProject = refreshedProject,
                        buildProgress = BuildProgressState(
                            isRunning = false,
                            currentStage = BuildStage.DONE,
                            progressFraction = 1f,
                            logLines = output.logLines
                        ),
                        inspectedBuildRecord = savedRecord,
                        inspectedApkResult = inspection,
                        snackbarMessage = "Built & signed ${savedRecord.apkFileName}"
                    )
                }
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        buildProgress = state.buildProgress.copy(
                            isRunning = false,
                            lastError = err.localizedMessage ?: "APK build failed",
                            logLines = state.buildProgress.logLines + "ERROR: ${err.localizedMessage}"
                        ),
                        snackbarMessage = "Build failed: ${err.localizedMessage}"
                    )
                }
            }
        }
    }

    suspend fun readWorkspaceFile(relativePath: String): String = withContext(Dispatchers.IO) {
        val project = _uiState.value.activeProject ?: return@withContext ""
        val file = File(project.workspaceDirPath, relativePath)
        if (!file.exists() || !file.isFile) return@withContext ""
        if (file.length() > 512 * 1024) {
            return@withContext "// Binary or large file (${file.length()} bytes) — preview truncated"
        }
        runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("// Unable to decode file as UTF-8")
    }

    fun saveWorkspaceFile(relativePath: String, content: String) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(project.workspaceDirPath, relativePath)
            file.writeText(content)
            val refreshed = GradleAndManifestParser.scanAndParse(
                id = project.id,
                workspaceDir = File(project.workspaceDirPath),
                sourceType = project.sourceType,
                sourceDisplayName = project.sourceDisplayName
            )
            _uiState.update { state ->
                state.copy(
                    activeProject = refreshed,
                    override = state.override.copy(
                        appLabel = refreshed.manifestSummary.appLabel,
                        applicationId = refreshed.gradleConfig.applicationId,
                        versionName = refreshed.gradleConfig.versionName,
                        versionCode = refreshed.gradleConfig.versionCode.toString(),
                        minSdk = refreshed.gradleConfig.minSdk.toString(),
                        targetSdk = refreshed.gradleConfig.targetSdk.toString()
                    ),
                    snackbarMessage = "Saved $relativePath"
                )
            }
        }
    }

    fun openBuildInspector(record: BuildRecordEntity) {
        viewModelScope.launch {
            val file = File(record.apkFilePath)
            val inspected = if (file.exists()) {
                ApkInspector.inspectFile(file).getOrNull()
            } else {
                null
            }
            _uiState.update {
                it.copy(
                    inspectedBuildRecord = record,
                    inspectedApkResult = inspected
                )
            }
        }
    }

    fun dismissBuildInspector() {
        _uiState.update { it.copy(inspectedBuildRecord = null, inspectedApkResult = null) }
    }

    fun exportApkToUri(record: BuildRecordEntity, destUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val src = File(record.apkFilePath)
                if (!src.exists()) throw IllegalStateException("APK file no longer exists on disk")
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    FileInputStream(src).use { it.copyTo(out) }
                } ?: throw IllegalStateException("Cannot write to selected destination")
            }
            _uiState.update {
                it.copy(
                    snackbarMessage = result.fold(
                        onSuccess = { "Exported ${record.apkFileName}" },
                        onFailure = { "Export failed: ${it.localizedMessage}" }
                    )
                )
            }
        }
    }

    fun exportProjectZipToUri(destUri: Uri) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val tempZip = File(context.cacheDir, "${project.projectName}.zip")
                AndroidStudioProjectImporter.exportWorkspaceAsZip(File(project.workspaceDirPath), tempZip)
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    FileInputStream(tempZip).use { it.copyTo(out) }
                } ?: throw IllegalStateException("Cannot open destination URI")
                tempZip.delete()
            }
            _uiState.update {
                it.copy(
                    snackbarMessage = result.fold(
                        onSuccess = { "Exported Android Studio ZIP" },
                        onFailure = { "ZIP export failed: ${it.localizedMessage}" }
                    )
                )
            }
        }
    }

    fun deleteBuildRecord(record: BuildRecordEntity) {
        viewModelScope.launch {
            repository.deleteBuild(record)
            _uiState.update {
                it.copy(
                    inspectedBuildRecord = if (it.inspectedBuildRecord?.id == record.id) null else it.inspectedBuildRecord,
                    snackbarMessage = "Deleted ${record.apkFileName}"
                )
            }
        }
    }

    fun inspectExternalApk(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isInspectingExternal = true) }
            val result = ApkInspector.inspectUri(context, uri)
            result.onSuccess { inspected ->
                _uiState.update {
                    it.copy(
                        isInspectingExternal = false,
                        externalApkResult = inspected,
                        snackbarMessage = "Analyzed ${inspected.fileName}"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isInspectingExternal = false,
                        snackbarMessage = "APK analysis failed: ${err.localizedMessage}"
                    )
                }
            }
        }
    }

    fun updateSigningProfile(config: SigningProfileConfig) {
        viewModelScope.launch {
            prefsRepo.updateProfile(config)
            refreshSigningSummary(forceRegenerate = true, overrideConfig = config)
            _uiState.update { it.copy(snackbarMessage = "Signing certificate updated & regenerated") }
        }
    }

    fun regenerateKeypair() {
        refreshSigningSummary(forceRegenerate = true)
        _uiState.update { it.copy(snackbarMessage = "Generated new RSA-2048 keystore certificate") }
    }

    private fun refreshSigningSummary(
        forceRegenerate: Boolean,
        overrideConfig: SigningProfileConfig? = null
    ) {
        viewModelScope.launch {
            val profile = overrideConfig ?: signingProfile.value
            val summary = runCatching {
                ApkSignerAndPackager.getOrCreateSigningSummary(context, profile, forceRegenerate)
            }.getOrNull()
            _uiState.update { it.copy(certSummary = summary) }
        }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
