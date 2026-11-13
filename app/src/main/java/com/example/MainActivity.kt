package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.BuildRecordEntity
import com.example.ui.ForgeViewModel
import com.example.ui.components.ApkInspectorSheet
import com.example.ui.components.ForgeBadge
import com.example.ui.screens.BuildsHistoryScreen
import com.example.ui.screens.ForgeWorkspaceScreen
import com.example.ui.screens.ProjectFilesScreen
import com.example.ui.screens.SigningToolsScreen
import com.example.ui.theme.ApkForgeTheme
import java.io.File

const val ROUTE_FORGE = "forge"
const val ROUTE_FILES = "files"
const val ROUTE_BUILDS = "builds"
const val ROUTE_SIGNING = "signing"

private data class NavDestinationItem(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ApkForgeTheme {
                ApkForgeApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkForgeApp(viewModel: ForgeViewModel = viewModel()) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val buildHistory by viewModel.buildHistory.collectAsStateWithLifecycle()
    val signingProfile by viewModel.signingProfile.collectAsStateWithLifecycle()

    var pendingExportRecord by remember { mutableStateOf<BuildRecordEntity?>(null) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importProjectZip(uri)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.importProjectFolder(uri)
        }
    }

    val externalApkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.inspectExternalApk(uri)
        }
    }

    val saveApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        val rec = pendingExportRecord
        if (uri != null && rec != null) {
            viewModel.exportApkToUri(rec, uri)
        }
        pendingExportRecord = null
    }

    val saveProjectZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.exportProjectZipToUri(uri)
        }
    }

    val shareApk: (BuildRecordEntity) -> Unit = { record ->
        val file = File(record.apkFilePath)
        if (file.exists()) {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(sendIntent, context.getString(R.string.share_apk_title))
            )
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbar()
        }
    }

    val destinations = remember {
        listOf(
            NavDestinationItem(ROUTE_FORGE, R.string.nav_forge, Icons.Filled.Build, Icons.Outlined.Build),
            NavDestinationItem(ROUTE_FILES, R.string.nav_files, Icons.Filled.Folder, Icons.Outlined.Folder),
            NavDestinationItem(ROUTE_BUILDS, R.string.nav_builds, Icons.Filled.Inventory2, Icons.Outlined.Inventory2),
            NavDestinationItem(ROUTE_SIGNING, R.string.nav_signing, Icons.Filled.VerifiedUser, Icons.Outlined.VerifiedUser)
        )
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: ROUTE_FORGE

    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    uiState.inspectedBuildRecord?.let { record ->
        ApkInspectorSheet(
            record = record,
            inspection = uiState.inspectedApkResult,
            onDismiss = { viewModel.dismissBuildInspector() },
            onSaveAs = {
                pendingExportRecord = record
                saveApkLauncher.launch(record.apkFileName)
            },
            onShare = { shareApk(record) }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Row {
                            Text(
                                text = stringResource(id = R.string.app_name),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(Modifier.width(10.dp))
                            ForgeBadge(
                                text = uiState.activeProject?.projectName ?: "STUDIO CONVERTER",
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        destinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            val label = stringResource(id = dest.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(dest.route) },
                                icon = {
                                    val iconVector = if (selected) dest.selectedIcon else dest.unselectedIcon
                                    if (dest.route == ROUTE_BUILDS && buildHistory.isNotEmpty()) {
                                        BadgedBox(badge = { Badge { Text("${buildHistory.size}") } }) {
                                            Icon(iconVector, contentDescription = label)
                                        }
                                    } else {
                                        Icon(iconVector, contentDescription = label)
                                    }
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("nav_${dest.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        destinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            val label = stringResource(id = dest.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(dest.route) },
                                icon = {
                                    val iconVector = if (selected) dest.selectedIcon else dest.unselectedIcon
                                    if (dest.route == ROUTE_BUILDS && buildHistory.isNotEmpty()) {
                                        BadgedBox(badge = { Badge { Text("${buildHistory.size}") } }) {
                                            Icon(iconVector, contentDescription = label)
                                        }
                                    } else {
                                        Icon(iconVector, contentDescription = label)
                                    }
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("rail_${dest.route}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_FORGE
                    ) {
                        composable(ROUTE_FORGE) {
                            ForgeWorkspaceScreen(
                                uiState = uiState,
                                onImportZip = {
                                    zipPickerLauncher.launch(
                                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                                    )
                                },
                                onImportFolder = { folderPickerLauncher.launch(null) },
                                onCreateStarter = { name, pkg ->
                                    viewModel.createStarterProject(name, pkg)
                                },
                                onExportProjectZip = {
                                    val defaultName = "${uiState.activeProject?.projectName ?: "studio-project"}.zip"
                                    saveProjectZipLauncher.launch(defaultName)
                                },
                                onCloseProject = { viewModel.clearActiveProject() },
                                onUpdateOverride = { transform -> viewModel.updateOverride(transform) },
                                onConvertToApk = { viewModel.convertProjectToApk() },
                                onRestoreGradleCache = { viewModel.restoreProjectGradleCache() },
                                onInspectFiles = { navigateToTab(ROUTE_FILES) }
                            )
                        }
                        composable(ROUTE_FILES) {
                            ProjectFilesScreen(
                                project = uiState.activeProject,
                                readWorkspaceFile = { relPath -> viewModel.readWorkspaceFile(relPath) },
                                onSaveWorkspaceFile = { relPath, text ->
                                    viewModel.saveWorkspaceFile(relPath, text)
                                },
                                onGoToForge = { navigateToTab(ROUTE_FORGE) }
                            )
                        }
                        composable(ROUTE_BUILDS) {
                            BuildsHistoryScreen(
                                builds = buildHistory,
                                onInspectBuild = { viewModel.openBuildInspector(it) },
                                onSaveBuild = { rec ->
                                    pendingExportRecord = rec
                                    saveApkLauncher.launch(rec.apkFileName)
                                },
                                onShareBuild = shareApk,
                                onDeleteBuild = { viewModel.deleteBuildRecord(it) },
                                onGoToForge = { navigateToTab(ROUTE_FORGE) }
                            )
                        }
                        composable(ROUTE_SIGNING) {
                            SigningToolsScreen(
                                signingProfile = signingProfile,
                                certSummary = uiState.certSummary,
                                externalApkResult = uiState.externalApkResult,
                                isInspectingExternal = uiState.isInspectingExternal,
                                onSaveProfile = { viewModel.updateSigningProfile(it) },
                                onRegenerateKey = { viewModel.regenerateKeypair() },
                                onPickExternalApk = {
                                    externalApkPickerLauncher.launch(
                                        arrayOf("application/vnd.android.package-archive", "application/octet-stream")
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
