package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.AndroidStudioProject
import com.example.engine.BuildVariant
import com.example.engine.EditableBuildOverride
import com.example.engine.GradleCacheDiagnostic
import com.example.ui.ForgeUiState
import com.example.ui.components.BuildPipelineCard
import com.example.ui.components.ForgeBadge
import com.example.ui.components.StatTile
import com.example.ui.components.StarterProjectDialog
import com.example.ui.components.formatByteSize
import com.example.ui.theme.JetBrainsMonoFontFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ForgeWorkspaceScreen(
    uiState: ForgeUiState,
    onImportZip: () -> Unit,
    onImportFolder: () -> Unit,
    onCreateStarter: (String, String) -> Unit,
    onExportProjectZip: () -> Unit,
    onCloseProject: () -> Unit,
    onUpdateOverride: ((EditableBuildOverride) -> EditableBuildOverride) -> Unit,
    onConvertToApk: () -> Unit,
    onRestoreGradleCache: () -> Unit,
    onInspectFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showStarterDialog by remember { mutableStateOf(false) }

    if (showStarterDialog) {
        StarterProjectDialog(
            onDismiss = { showStarterDialog = false },
            onCreate = { name, pkg ->
                showStarterDialog = false
                onCreateStarter(name, pkg)
            }
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val project = uiState.activeProject

            if (project == null) {
                EmptyWorkspaceHero(
                    isImporting = uiState.isImporting,
                    onImportZip = onImportZip,
                    onImportFolder = onImportFolder,
                    onOpenStarterDialog = { showStarterDialog = true }
                )
            } else {
                ActiveProjectHeaderCard(
                    project = project,
                    onExportZip = onExportProjectZip,
                    onClose = onCloseProject,
                    onInspectFiles = onInspectFiles
                )

                BuildConfigurationCard(
                    override = uiState.override,
                    onUpdateOverride = onUpdateOverride
                )

                ManifestAndGradleSummaryCard(project = project)

                uiState.gradleDiagnostic?.let { diagnostic ->
                    GradleCacheHealthCard(
                        diagnostic = diagnostic,
                        onRestoreGradleCache = onRestoreGradleCache
                    )
                }

                AnimatedVisibility(
                    visible = uiState.buildProgress.isRunning || uiState.buildProgress.logLines.isNotEmpty()
                ) {
                    BuildPipelineCard(progress = uiState.buildProgress)
                }

                Button(
                    onClick = onConvertToApk,
                    enabled = !uiState.buildProgress.isRunning,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("convert_to_apk_button")
                ) {
                    if (uiState.buildProgress.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Building & Signing APK...", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Icon(Icons.Default.RocketLaunch, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Convert Project to Signed APK", style = MaterialTheme.typography.titleMedium)
                    }
                }

                // Allow switching project anytime
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onImportZip,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("switch_zip_button")
                    ) {
                        Icon(Icons.Outlined.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Load ZIP")
                    }
                    OutlinedButton(
                        onClick = onImportFolder,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("switch_folder_button")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Load Folder")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EmptyWorkspaceHero(
    isImporting: Boolean,
    onImportZip: () -> Unit,
    onImportFolder: () -> Unit,
    onOpenStarterDialog: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_forge_hero),
                        contentDescription = stringResource(id = R.string.hero_banner_desc),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                        MaterialTheme.colorScheme.surface
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ForgeBadge(text = "ANDROID STUDIO -> APK")
                        Text(
                            text = "Convert Studio Projects to APK",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }

                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Import an Android Studio ZIP export or project directory. APK Forge parses Gradle & Manifest metadata, compiles binary AndroidManifest.xml (AXML), resources.arsc, and Dalvik DEX containers, and signs with RSA-2048.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isImporting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Scanning Android Studio project tree...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        ImportSourceCard(
            icon = Icons.Outlined.FolderZip,
            title = "Import Android Studio ZIP",
            subtitle = "Select an exported .zip archive containing app/build.gradle.kts, Manifest, and sources",
            accent = MaterialTheme.colorScheme.primary,
            testTag = "import_zip_card",
            onClick = onImportZip
        )

        ImportSourceCard(
            icon = Icons.Default.FolderOpen,
            title = "Import Project Folder",
            subtitle = "Browse an unzipped Android Studio workspace directory via Storage Access Framework",
            accent = MaterialTheme.colorScheme.secondary,
            testTag = "import_folder_card",
            onClick = onImportFolder
        )

        ImportSourceCard(
            icon = Icons.Default.AddCircle,
            title = "Create Starter Studio Project",
            subtitle = "Generate a complete Gradle KTS + Kotlin Compose project to test & convert right away",
            accent = MaterialTheme.colorScheme.tertiary,
            testTag = "create_starter_card",
            onClick = onOpenStarterDialog
        )
    }
}

@Composable
private fun ImportSourceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                color = accent.copy(alpha = 0.14f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accent,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveProjectHeaderCard(
    project: AndroidStudioProject,
    onExportZip: () -> Unit,
    onClose: () -> Unit,
    onInspectFiles: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), MaterialTheme.shapes.medium)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Android,
                                contentDescription = "Loaded Android project",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = project.projectName,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = project.gradleConfig.applicationId,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row {
                    IconButton(
                        onClick = onExportZip,
                        modifier = Modifier.testTag("export_project_zip_button")
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = "Export Studio ZIP")
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("close_project_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close project")
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ForgeBadge(text = project.sourceType.label)
                ForgeBadge(
                    text = "Gradle ${project.gradleConfig.gradleWrapperVersion}",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
                if (project.gradleConfig.usesCompose) {
                    ForgeBadge(
                        text = "Jetpack Compose",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                if (project.hasPrecompiledDex) {
                    ForgeBadge(
                        text = "Precompiled DEX",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatTile(
                    label = "Kotlin/Java",
                    value = "${project.kotlinFileCount + project.javaFileCount}",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "XML/Manifest",
                    value = "${project.xmlFileCount}",
                    accent = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Total Files",
                    value = "${project.files.size}",
                    accent = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Workspace size: ${formatByteSize(project.totalSizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onInspectFiles,
                    modifier = Modifier.testTag("open_files_button")
                ) {
                    Text("Browse ${project.files.size} Files")
                }
            }
        }
    }
}

@Composable
private fun BuildConfigurationCard(
    override: EditableBuildOverride,
    onUpdateOverride: ((EditableBuildOverride) -> EditableBuildOverride) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Text("APK Packaging Configuration", style = MaterialTheme.typography.titleMedium)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Variant:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BuildVariant.entries.forEach { variant ->
                    val selected = override.variant == variant
                    FilterChip(
                        selected = selected,
                        onClick = { onUpdateOverride { it.copy(variant = variant) } },
                        label = { Text(variant.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.testTag("variant_${variant.name.lowercase()}")
                    )
                }
            }

            OutlinedTextField(
                value = override.appLabel,
                onValueChange = { v -> onUpdateOverride { it.copy(appLabel = v) } },
                label = { Text("App Label (strings.xml)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("config_app_label")
            )

            OutlinedTextField(
                value = override.applicationId,
                onValueChange = { v -> onUpdateOverride { it.copy(applicationId = v) } },
                label = { Text("Application ID (build.gradle.kts)") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("config_application_id")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = override.versionName,
                    onValueChange = { v -> onUpdateOverride { it.copy(versionName = v) } },
                    label = { Text("Version Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = override.versionCode,
                    onValueChange = { v -> onUpdateOverride { it.copy(versionCode = v.filter { ch -> ch.isDigit() }) } },
                    label = { Text("Version Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = override.minSdk,
                    onValueChange = { v -> onUpdateOverride { it.copy(minSdk = v.filter { ch -> ch.isDigit() }) } },
                    label = { Text("Min SDK") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = override.targetSdk,
                    onValueChange = { v -> onUpdateOverride { it.copy(targetSdk = v.filter { ch -> ch.isDigit() }) } },
                    label = { Text("Target SDK") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManifestAndGradleSummaryCard(project: AndroidStudioProject) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Text("Detected Manifest & Dependencies", style = MaterialTheme.typography.titleMedium)
            }

            Text(
                text = "Launcher Activity: ${project.manifestSummary.launcherActivity}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (project.manifestSummary.permissions.isNotEmpty()) {
                Text(
                    text = "Permissions (${project.manifestSummary.permissions.size})",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    project.manifestSummary.permissions.forEach { perm ->
                        ForgeBadge(
                            text = perm.substringAfterLast('.'),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            if (project.gradleConfig.dependencies.isNotEmpty()) {
                Text(
                    text = "Gradle Dependencies (${project.gradleConfig.dependencies.size})",
                    style = MaterialTheme.typography.labelLarge
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    project.gradleConfig.dependencies.take(5).forEach { dep ->
                        Text(
                            text = "• $dep",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (project.gradleConfig.dependencies.size > 5) {
                        Text(
                            text = "+ ${project.gradleConfig.dependencies.size - 5} more in Files tab",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GradleCacheHealthCard(
    diagnostic: GradleCacheDiagnostic,
    onRestoreGradleCache: () -> Unit
) {
    val borderColor = if (diagnostic.needsRepair) {
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, MaterialTheme.shapes.medium)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (diagnostic.needsRepair) Icons.Default.WarningAmber else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (diagnostic.needsRepair) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    )
                    Text("Gradle Cache & Build Health", style = MaterialTheme.typography.titleMedium)
                }
                ForgeBadge(
                    text = if (diagnostic.needsRepair) "${diagnostic.issues.size} Issue(s)" else "Healthy",
                    containerColor = if (diagnostic.needsRepair) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = if (diagnostic.needsRepair) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
            }

            if (diagnostic.issues.isEmpty()) {
                Text(
                    text = "Build cache enabled, wrapper URL valid, and no stale lock or intermediate cache files detected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    diagnostic.issues.forEach { issue ->
                        Text(
                            text = "• $issue",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onRestoreGradleCache,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("restore_gradle_cache_button")
            ) {
                Icon(Icons.Default.Healing, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (diagnostic.needsRepair) "Restore & Repair Gradle Cache" else "Refresh & Reset Gradle Cache")
            }
        }
    }
}
