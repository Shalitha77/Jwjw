package com.example.engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object AndroidStudioProjectImporter {

    private val skippedDirs = setOf(".git", ".gradle", ".idea", "intermediates", "tmp", "kotlin")

    suspend fun importFromZipUri(
        context: Context,
        zipUri: Uri
    ): Result<AndroidStudioProject> = withContext(Dispatchers.IO) {
        runCatching {
            val id = UUID.randomUUID().toString().take(8)
            val projectsRoot = File(context.filesDir, "projects").apply { mkdirs() }
            val workspaceDir = File(projectsRoot, id).apply { mkdirs() }
            val displayName = queryDisplayName(context, zipUri) ?: "project.zip"

            val rawExtractDir = File(workspaceDir, "_raw").apply { mkdirs() }
            var extractedCount = 0

            context.contentResolver.openInputStream(zipUri)?.use { rawStream ->
                ZipInputStream(BufferedInputStream(rawStream)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory && !shouldSkipPath(name)) {
                            val destFile = File(rawExtractDir, name)
                            val canonicalRoot = rawExtractDir.canonicalPath + File.separator
                            val canonicalDest = destFile.canonicalPath
                            if (!canonicalDest.startsWith(canonicalRoot)) {
                                throw SecurityException("Blocked unsafe zip entry path: $name")
                            }
                            destFile.parentFile?.mkdirs()
                            FileOutputStream(destFile).use { fos ->
                                zis.copyTo(fos)
                            }
                            extractedCount++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: throw IllegalStateException("Unable to open ZIP stream from selected file")

            if (extractedCount == 0) {
                throw IllegalArgumentException("ZIP archive contains no readable project files")
            }

            // If ZIP wraps everything in a single top-level directory (e.g., MyApp/...), hoist it
            val topChildren = rawExtractDir.listFiles().orEmpty()
            val effectiveRoot = if (topChildren.size == 1 && topChildren[0].isDirectory) {
                topChildren[0]
            } else {
                rawExtractDir
            }

            effectiveRoot.listFiles().orEmpty().forEach { child ->
                child.copyRecursively(File(workspaceDir, child.name), overwrite = true)
            }
            rawExtractDir.deleteRecursively()

            GradleAndManifestParser.scanAndParse(
                id = id,
                workspaceDir = workspaceDir,
                sourceType = ProjectSourceType.ZIP_ARCHIVE,
                sourceDisplayName = displayName
            )
        }
    }

    suspend fun importFromTreeUri(
        context: Context,
        treeUri: Uri
    ): Result<AndroidStudioProject> = withContext(Dispatchers.IO) {
        runCatching {
            val id = UUID.randomUUID().toString().take(8)
            val projectsRoot = File(context.filesDir, "projects").apply { mkdirs() }
            val workspaceDir = File(projectsRoot, id).apply { mkdirs() }

            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            val displayName = queryTreeDocName(context, rootDocUri) ?: "AndroidStudioProject"

            var copiedCount = 0
            fun copyNode(docId: String, targetDir: File, depth: Int) {
                if (depth > 14) return
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(idCol)
                        val childName = cursor.getString(nameCol) ?: continue
                        val mime = cursor.getString(mimeCol) ?: ""
                        if (childName in skippedDirs) continue

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val subDir = File(targetDir, childName).apply { mkdirs() }
                            copyNode(childId, subDir, depth + 1)
                        } else {
                            val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            val destFile = File(targetDir, childName)
                            context.contentResolver.openInputStream(childUri)?.use { input ->
                                FileOutputStream(destFile).use { output ->
                                    input.copyTo(output)
                                }
                                copiedCount++
                            }
                        }
                    }
                }
            }

            copyNode(rootDocId, workspaceDir, 0)
            if (copiedCount == 0) {
                throw IllegalArgumentException("Selected folder did not contain readable files")
            }

            GradleAndManifestParser.scanAndParse(
                id = id,
                workspaceDir = workspaceDir,
                sourceType = ProjectSourceType.DIRECTORY_TREE,
                sourceDisplayName = displayName
            )
        }
    }

    suspend fun createStarterStudioProject(
        context: Context,
        appName: String = "PulseNotes",
        packageName: String = "com.studio.pulsenotes"
    ): Result<AndroidStudioProject> = withContext(Dispatchers.IO) {
        runCatching {
            val id = UUID.randomUUID().toString().take(8)
            val projectsRoot = File(context.filesDir, "projects").apply { mkdirs() }
            val root = File(projectsRoot, id).apply { mkdirs() }
            val pkgPath = packageName.replace('.', '/')

            writeProjectFile(
                root,
                "settings.gradle.kts",
                """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
                rootProject.name = "$appName"
                include(":app")
                """.trimIndent()
            )

            writeProjectFile(
                root,
                "build.gradle.kts",
                """
                plugins {
                    id("com.android.application") version "8.9.1" apply false
                    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
                    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
                }
                """.trimIndent()
            )

            writeProjectFile(
                root,
                "gradle.properties",
                """
                org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
                org.gradle.parallel=true
                org.gradle.caching=true
                org.gradle.configuration-cache=false
                android.useAndroidX=true
                kotlin.code.style=official
                """.trimIndent()
            )

            writeProjectFile(
                root,
                "gradle/wrapper/gradle-wrapper.properties",
                """
                distributionBase=GRADLE_USER_HOME
                distributionPath=wrapper/dists
                distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip
                zipStoreBase=GRADLE_USER_HOME
                zipStorePath=wrapper/dists
                """.trimIndent()
            )

            writeProjectFile(
                root,
                "app/build.gradle.kts",
                """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                    id("org.jetbrains.kotlin.plugin.compose")
                }

                android {
                    namespace = "$packageName"
                    compileSdk = 35

                    defaultConfig {
                        applicationId = "$packageName"
                        minSdk = 24
                        targetSdk = 35
                        versionCode = 1
                        versionName = "1.0.0"
                    }

                    buildTypes {
                        release {
                            isMinifyEnabled = true
                        }
                    }

                    buildFeatures {
                        compose = true
                    }
                }

                dependencies {
                    implementation("androidx.core:core-ktx:1.15.0")
                    implementation("androidx.activity:activity-compose:1.10.0")
                    implementation("androidx.compose.material3:material3:1.3.1")
                }
                """.trimIndent()
            )

            writeProjectFile(
                root,
                "app/src/main/AndroidManifest.xml",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">

                    <uses-permission android:name="android.permission.INTERNET" />

                    <application
                        android:allowBackup="true"
                        android:label="@string/app_name"
                        android:supportsRtl="true"
                        android:theme="@android:style/Theme.Material.Light.NoActionBar">
                        <activity
                            android:name=".MainActivity"
                            android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
                """.trimIndent()
            )

            writeProjectFile(
                root,
                "app/src/main/res/values/strings.xml",
                """
                <resources>
                    <string name="app_name">$appName</string>
                    <string name="welcome_subtitle">Built from Android Studio template</string>
                </resources>
                """.trimIndent()
            )

            writeProjectFile(
                root,
                "app/src/main/res/values/colors.xml",
                """
                <resources>
                    <color name="primary_emerald">#10B981</color>
                    <color name="surface_dark">#0F172A</color>
                </resources>
                """.trimIndent()
            )

            writeProjectFile(
                root,
                "app/src/main/res/layout/activity_main.xml",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:gravity="center"
                    android:orientation="vertical"
                    android:padding="24dp">

                    <TextView
                        android:id="@+id/titleText"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/app_name"
                        android:textSize="22sp" />
                </LinearLayout>
                """.trimIndent()
            )

            writeProjectFile(
                root,
                "app/src/main/java/$pkgPath/MainActivity.kt",
                """
                package $packageName

                import android.os.Bundle
                import androidx.activity.ComponentActivity
                import androidx.activity.compose.setContent
                import androidx.compose.material3.MaterialTheme
                import androidx.compose.material3.Surface
                import androidx.compose.material3.Text

                class MainActivity : ComponentActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContent {
                            MaterialTheme {
                                Surface {
                                    Text(text = "Hello from $appName")
                                }
                            }
                        }
                    }
                }
                """.trimIndent()
            )

            GradleAndManifestParser.scanAndParse(
                id = id,
                workspaceDir = root,
                sourceType = ProjectSourceType.GENERATED_STARTER,
                sourceDisplayName = "$appName (Android Studio Template)"
            )
        }
    }

    suspend fun exportWorkspaceAsZip(
        workspaceDir: File,
        destFile: File
    ) = withContext(Dispatchers.IO) {
        ZipOutputStream(FileOutputStream(destFile)).use { zos ->
            workspaceDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val rel = file.relativeTo(workspaceDir).invariantSeparatorsPath
                    zos.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
    }

    private fun writeProjectFile(root: File, relativePath: String, content: String) {
        val f = File(root, relativePath)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    private fun shouldSkipPath(path: String): Boolean {
        val segments = path.split('/', '\\')
        return segments.any { it in skippedDirs || it.startsWith("__MACOSX") }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }

    private fun queryTreeDocName(context: Context, docUri: Uri): String? {
        return context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }
}
