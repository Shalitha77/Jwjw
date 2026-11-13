package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.engine.AndroidStudioProject
import com.example.engine.FileKind
import com.example.engine.ProjectFileEntry
import com.example.ui.components.ForgeBadge
import com.example.ui.components.formatByteSize
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun ProjectFilesScreen(
    project: AndroidStudioProject?,
    readWorkspaceFile: suspend (String) -> String,
    onSaveWorkspaceFile: (String, String) -> Unit,
    onGoToForge: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFile by remember(project?.id) { mutableStateOf<ProjectFileEntry?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedKindFilter by remember { mutableStateOf<FileKind?>(null) }

    if (project == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.widthIn(max = 360.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOff,
                    contentDescription = "No project loaded",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp)
                )
                Text("No Project Loaded", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "Import an Android Studio ZIP, project folder, or create a starter project in the Forge tab to inspect and edit its files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onGoToForge) {
                    Text("Go to Forge")
                }
            }
        }
        return
    }

    val currentFile = selectedFile
    if (currentFile != null) {
        FileViewerEditorPane(
            fileEntry = currentFile,
            readWorkspaceFile = readWorkspaceFile,
            onSave = { newContent -> onSaveWorkspaceFile(currentFile.relativePath, newContent) },
            onBack = { selectedFile = null },
            modifier = modifier
        )
        return
    }

    val filteredFiles = remember(project.files, searchQuery, selectedKindFilter) {
        project.files.filter { entry ->
            val matchesSearch = searchQuery.isBlank() ||
                entry.relativePath.contains(searchQuery, ignoreCase = true)
            val matchesKind = selectedKindFilter == null || entry.kind == selectedKindFilter
            matchesSearch && matchesKind
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search ${project.files.size} project files...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("files_search_input")
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedKindFilter == null,
                        onClick = { selectedKindFilter = null },
                        label = { Text("All (${project.files.size})") }
                    )
                }
                val kinds = listOf(
                    FileKind.GRADLE,
                    FileKind.MANIFEST,
                    FileKind.KOTLIN,
                    FileKind.JAVA,
                    FileKind.XML,
                    FileKind.RESOURCE
                )
                items(kinds) { kind ->
                    val count = project.files.count { it.kind == kind }
                    if (count > 0) {
                        FilterChip(
                            selected = selectedKindFilter == kind,
                            onClick = {
                                selectedKindFilter = if (selectedKindFilter == kind) null else kind
                            },
                            label = { Text("${kind.label} ($count)") }
                        )
                    }
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filteredFiles, key = { it.relativePath }) { entry ->
                    FileTreeRow(
                        entry = entry,
                        onClick = { selectedFile = entry }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileTreeRow(
    entry: ProjectFileEntry,
    onClick: () -> Unit
) {
    val (icon, tint) = when (entry.kind) {
        FileKind.KOTLIN, FileKind.JAVA -> Icons.Default.Code to MaterialTheme.colorScheme.primary
        FileKind.GRADLE, FileKind.MANIFEST -> Icons.Default.Settings to MaterialTheme.colorScheme.secondary
        FileKind.RESOURCE -> Icons.Default.Image to MaterialTheme.colorScheme.tertiary
        else -> Icons.Default.Description to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .testTag("file_row_${entry.relativePath.substringAfterLast('/')}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = entry.kind.label,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.relativePath.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.relativePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ForgeBadge(
                text = formatByteSize(entry.sizeBytes),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileViewerEditorPane(
    fileEntry: ProjectFileEntry,
    readWorkspaceFile: suspend (String) -> String,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var content by remember(fileEntry.relativePath) { mutableStateOf("") }
    var isEditing by remember(fileEntry.relativePath) { mutableStateOf(false) }
    var isLoaded by remember(fileEntry.relativePath) { mutableStateOf(false) }

    LaunchedEffect(fileEntry.relativePath) {
        content = readWorkspaceFile(fileEntry.relativePath)
        isLoaded = true
    }

    BackHandler(onBack = onBack)

    val canEdit = fileEntry.kind != FileKind.RESOURCE && fileEntry.kind != FileKind.DEX_OR_BIN

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to file tree")
                }
                Column {
                    Text(
                        text = fileEntry.relativePath.substringAfterLast('/'),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = fileEntry.relativePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (canEdit && isLoaded) {
                if (isEditing) {
                    Button(
                        onClick = {
                            onSave(content)
                            isEditing = false
                        },
                        modifier = Modifier.testTag("save_file_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save")
                    }
                } else {
                    OutlinedButton(
                        onClick = { isEditing = true },
                        modifier = Modifier.testTag("edit_file_button")
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Edit")
                    }
                }
            }
        }

        Surface(
            color = Color(0xFF070A0F),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
        ) {
            if (!isLoaded) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading...", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (isEditing) {
                BasicTextField(
                    value = content,
                    onValueChange = { content = it },
                    textStyle = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = Color(0xFFE2E8F0)
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState())
                )
            } else {
                val lines = remember(content) { content.lines() }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    lines.forEachIndexed { idx, line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = (idx + 1).toString().padStart(3, ' '),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = Color(0xFF475569)
                            )
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = Color(0xFFE2E8F0)
                            )
                        }
                    }
                }
            }
        }
    }
}
