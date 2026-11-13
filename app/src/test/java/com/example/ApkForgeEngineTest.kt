package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SigningProfileConfig
import com.example.engine.AndroidStudioProjectImporter
import com.example.engine.ApkInspector
import com.example.engine.ApkSignerAndPackager
import com.example.engine.BinaryXmlEncoder
import com.example.engine.BuildVariant
import com.example.engine.DexContainerBuilder
import com.example.engine.EditableBuildOverride
import com.example.engine.ResourcesArscEncoder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ApkForgeEngineTest {

    @Test
    fun `binary xml encoder produces valid RES_XML_TYPE header`() {
        val bytes = BinaryXmlEncoder.encodeManifest(
            packageName = "com.example.demo",
            versionCode = 3,
            versionName = "1.2.0",
            minSdk = 24,
            targetSdk = 35,
            launcherActivity = "com.example.demo.MainActivity",
            permissions = listOf("android.permission.INTERNET")
        )
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x0003.toShort(), bb.getShort(0))
        assertEquals(8.toShort(), bb.getShort(2))
        assertEquals(bytes.size, bb.getInt(4))
    }

    @Test
    fun `resources arsc encoder produces valid RES_TABLE_TYPE header`() {
        val bytes = ResourcesArscEncoder.encode("com.example.demo", "DemoApp")
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x0002.toShort(), bb.getShort(0))
        assertEquals(12.toShort(), bb.getShort(2))
        assertEquals(bytes.size, bb.getInt(4))
        assertEquals(1, bb.getInt(8))
    }

    @Test
    fun `dex builder generates valid v035 dex with matching sha1 and adler32`() {
        val dex = DexContainerBuilder.buildMainActivityDex("com.example.demo.MainActivity")
        val magic = String(dex.copyOfRange(0, 8), Charsets.US_ASCII)
        assertEquals("dex\n035\u0000", magic)

        val bb = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        val storedAdler = bb.getInt(8)
        val storedSha1 = dex.copyOfRange(12, 32)
        val fileSize = bb.getInt(32)
        assertEquals(dex.size, fileSize)

        val expectedSha1 = MessageDigest.getInstance("SHA-1").apply {
            update(dex, 32, dex.size - 32)
        }.digest()
        assertTrue(storedSha1.contentEquals(expectedSha1))

        val adler = Adler32().apply { update(dex, 12, dex.size - 12) }.value.toInt()
        assertEquals(adler, storedAdler)
    }

    @Test
    fun `end to end starter project to signed apk and inspector verification`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val project = AndroidStudioProjectImporter.createStarterStudioProject(
            context = context,
            appName = "ForgeSample",
            packageName = "com.forge.sample"
        ).getOrThrow()

        assertEquals("com.forge.sample", project.gradleConfig.applicationId)
        assertTrue(project.files.any { it.relativePath.endsWith("MainActivity.kt") })

        val override = EditableBuildOverride(
            appLabel = "ForgeSample",
            applicationId = "com.forge.sample",
            versionName = "2.0.0",
            versionCode = "2",
            minSdk = "24",
            targetSdk = "35",
            variant = BuildVariant.RELEASE
        )

        val buildOutput = ApkSignerAndPackager.buildAndSignApk(
            context = context,
            project = project,
            override = override,
            signingProfile = SigningProfileConfig(),
            onStage = { _, _, _ -> }
        ).getOrThrow()

        assertTrue(buildOutput.apkFile.exists())
        val inspection = ApkInspector.inspectFile(buildOutput.apkFile).getOrThrow()
        assertTrue(inspection.hasBinaryManifest)
        assertTrue(inspection.hasResourcesArsc)
        assertTrue(inspection.dexFiles.contains("classes.dex"))
        assertTrue(inspection.hasV1Signature)
    }

    @Test
    fun `gradle cache repairer detects and restores stale caches and properties`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempWorkspace = java.io.File(context.cacheDir, "broken_gradle_ws").apply {
            deleteRecursively()
            mkdirs()
        }
        java.io.File(tempWorkspace, ".gradle/8.9/executionHistory").mkdirs()
        java.io.File(tempWorkspace, ".gradle/8.9/executionHistory/executionHistory.lock").writeText("lock")
        java.io.File(tempWorkspace, "local.properties").writeText("sdk.dir=/Users/old/Android/Sdk\n")
        java.io.File(tempWorkspace, "gradle.properties").writeText("org.gradle.configuration-cache=true\n")

        val before = com.example.engine.GradleCacheRepairer.inspectWorkspace(tempWorkspace)
        assertTrue(before.needsRepair)
        assertTrue(before.hasLocalPropertiesConflict)
        assertTrue(before.configCacheEnabled)

        val report = com.example.engine.GradleCacheRepairer.restoreAndRepair(tempWorkspace)
        assertTrue(report.removedCacheEntries > 0)

        val after = com.example.engine.GradleCacheRepairer.inspectWorkspace(tempWorkspace)
        assertEquals(0, after.issues.size)
        assertTrue(after.cachingEnabled)
        assertTrue(after.hasValidWrapperUrl)
        tempWorkspace.deleteRecursively()
    }
}
