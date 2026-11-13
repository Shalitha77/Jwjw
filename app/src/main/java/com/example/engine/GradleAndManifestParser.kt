package com.example.engine

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader

object GradleAndManifestParser {

    fun scanAndParse(
        id: String,
        workspaceDir: File,
        sourceType: ProjectSourceType,
        sourceDisplayName: String
    ): AndroidStudioProject {
        val entries = mutableListOf<ProjectFileEntry>()
        workspaceDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(workspaceDir).invariantSeparatorsPath
                entries.add(
                    ProjectFileEntry(
                        relativePath = rel,
                        sizeBytes = file.length(),
                        kind = classifyFile(rel)
                    )
                )
            }
        entries.sortWith(compareBy<ProjectFileEntry> { it.kind.ordinal }.thenBy { it.relativePath })

        val settingsFile = findFirstExisting(workspaceDir, listOf("settings.gradle.kts", "settings.gradle"))
        val appGradleFile = findFirstExisting(
            workspaceDir,
            listOf("app/build.gradle.kts", "app/build.gradle", "build.gradle.kts", "build.gradle")
        )
        val wrapperPropsFile = findFirstExisting(
            workspaceDir,
            listOf("gradle/wrapper/gradle-wrapper.properties")
        )
        val manifestFile = findManifestFile(workspaceDir)
        val stringsFile = findStringsXmlFile(workspaceDir)

        val settingsText = settingsFile?.readTextSafe().orEmpty()
        val appGradleText = appGradleFile?.readTextSafe().orEmpty()
        val wrapperText = wrapperPropsFile?.readTextSafe().orEmpty()
        val manifestText = manifestFile?.readTextSafe().orEmpty()
        val stringsMap = stringsFile?.let { parseStringsXml(it.readTextSafe()) }.orEmpty()

        val rootName = extractQuotedProperty(settingsText, "rootProject.name")
            ?: workspaceDir.name.ifBlank { "AndroidApp" }

        val namespace = extractGradleString(appGradleText, "namespace")
            ?: extractManifestAttribute(manifestText, "package")
            ?: "com.example.app"

        val applicationId = extractGradleString(appGradleText, "applicationId")
            ?: namespace

        val minSdk = extractGradleInt(appGradleText, "minSdk")
            ?: extractGradleInt(appGradleText, "minSdkVersion")
            ?: extractManifestUsesSdk(manifestText, "minSdkVersion")
            ?: 24

        val targetSdk = extractGradleInt(appGradleText, "targetSdk")
            ?: extractGradleInt(appGradleText, "targetSdkVersion")
            ?: extractManifestUsesSdk(manifestText, "targetSdkVersion")
            ?: 35

        val compileSdk = extractGradleInt(appGradleText, "compileSdk")
            ?: extractGradleInt(appGradleText, "compileSdkVersion")
            ?: targetSdk

        val versionCode = extractGradleInt(appGradleText, "versionCode")
            ?: extractManifestIntAttribute(manifestText, "versionCode")
            ?: 1

        val versionName = extractGradleString(appGradleText, "versionName")
            ?: extractManifestAttribute(manifestText, "versionName")
            ?: "1.0.0"

        val gradleWrapperVersion = Regex("""gradle-([0-9]+\.[0-9]+(?:\.[0-9]+)?)-""")
            .find(wrapperText)?.groupValues?.getOrNull(1) ?: "8.9"

        val plugins = extractPlugins(appGradleText)
        val dependencies = extractDependencies(appGradleText)

        val manifestSummary = parseManifest(manifestText, stringsMap, namespace, rootName)

        val hasPrecompiledDex = entries.any {
            it.relativePath.endsWith(".dex", ignoreCase = true)
        }

        val usesCompose = appGradleText.contains("compose", ignoreCase = true) ||
            plugins.any { it.contains("compose", ignoreCase = true) }
        val usesKotlin = entries.any { it.kind == FileKind.KOTLIN } ||
            appGradleText.contains("kotlin", ignoreCase = true)

        val gradleConfig = GradleBuildConfig(
            rootProjectName = rootName,
            namespace = namespace,
            applicationId = applicationId,
            minSdk = minSdk.coerceIn(16, 36),
            targetSdk = targetSdk.coerceIn(21, 36),
            compileSdk = compileSdk.coerceIn(21, 36),
            versionCode = versionCode.coerceAtLeast(1),
            versionName = versionName.ifBlank { "1.0" },
            gradleWrapperVersion = gradleWrapperVersion,
            plugins = plugins,
            dependencies = dependencies,
            usesCompose = usesCompose,
            usesKotlin = usesKotlin
        )

        return AndroidStudioProject(
            id = id,
            projectName = manifestSummary.appLabel.ifBlank { rootName },
            sourceType = sourceType,
            sourceDisplayName = sourceDisplayName,
            workspaceDirPath = workspaceDir.absolutePath,
            gradleConfig = gradleConfig,
            manifestSummary = manifestSummary,
            files = entries,
            hasPrecompiledDex = hasPrecompiledDex,
            importedAtMillis = System.currentTimeMillis()
        )
    }

    fun applyOverridesToWorkspace(
        workspaceDir: File,
        override: EditableBuildOverride
    ) {
        val stringsFile = findStringsXmlFile(workspaceDir)
        if (stringsFile != null) {
            val content = stringsFile.readTextSafe()
            val updated = if (content.contains("""name="app_name"""")) {
                content.replace(
                    Regex("""(<string\s+name="app_name"[^>]*>)(.*?)(</string>)"""),
                    "$1${escapeXml(override.appLabel)}$3"
                )
            } else {
                content.replace(
                    "</resources>",
                    "    <string name=\"app_name\">${escapeXml(override.appLabel)}</string>\n</resources>"
                )
            }
            stringsFile.writeText(updated)
        }

        val gradleFile = findFirstExisting(
            workspaceDir,
            listOf("app/build.gradle.kts", "app/build.gradle", "build.gradle.kts", "build.gradle")
        )
        if (gradleFile != null) {
            var text = gradleFile.readTextSafe()
            text = replaceGradlePropString(text, "applicationId", override.applicationId)
            text = replaceGradlePropString(text, "versionName", override.versionName)
            override.versionCode.toIntOrNull()?.let { vc ->
                text = replaceGradlePropInt(text, "versionCode", vc)
            }
            override.minSdk.toIntOrNull()?.let { min ->
                text = replaceGradlePropInt(text, "minSdk", min)
                text = replaceGradlePropInt(text, "minSdkVersion", min)
            }
            override.targetSdk.toIntOrNull()?.let { tgt ->
                text = replaceGradlePropInt(text, "targetSdk", tgt)
                text = replaceGradlePropInt(text, "targetSdkVersion", tgt)
            }
            gradleFile.writeText(text)
        }
    }

    private fun replaceGradlePropString(text: String, key: String, value: String): String {
        val regex = Regex("""(\b$key\s*=?\s*["'])([^"']*)(["'])""")
        val match = regex.find(text) ?: return text
        val replacement = "${match.groupValues[1]}$value${match.groupValues[3]}"
        return text.replaceRange(match.range, replacement)
    }

    private fun replaceGradlePropInt(text: String, key: String, value: Int): String {
        val regex = Regex("""(\b$key\s*=?\s*)(\d+)""")
        val match = regex.find(text) ?: return text
        val replacement = "${match.groupValues[1]}$value"
        return text.replaceRange(match.range, replacement)
    }

    private fun classifyFile(relativePath: String): FileKind {
        val lower = relativePath.lowercase()
        return when {
            lower.endsWith("androidmanifest.xml") -> FileKind.MANIFEST
            lower.endsWith(".gradle") || lower.endsWith(".gradle.kts") ||
                lower.endsWith("libs.versions.toml") || lower.endsWith("gradle-wrapper.properties") -> FileKind.GRADLE
            lower.endsWith(".kt") || lower.endsWith(".kts") -> FileKind.KOTLIN
            lower.endsWith(".java") -> FileKind.JAVA
            lower.endsWith(".xml") -> FileKind.XML
            lower.endsWith(".dex") || lower.endsWith(".so") || lower.endsWith(".arsc") || lower.endsWith(".jar") -> FileKind.DEX_OR_BIN
            lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".jpg") ||
                lower.endsWith(".ttf") || lower.endsWith(".otf") -> FileKind.RESOURCE
            else -> FileKind.OTHER
        }
    }

    private fun findFirstExisting(root: File, candidates: List<String>): File? {
        for (rel in candidates) {
            val f = File(root, rel)
            if (f.exists() && f.isFile) return f
        }
        return null
    }

    private fun findManifestFile(root: File): File? {
        return findFirstExisting(
            root,
            listOf("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml", "AndroidManifest.xml")
        ) ?: root.walkTopDown().firstOrNull { it.isFile && it.name == "AndroidManifest.xml" }
    }

    private fun findStringsXmlFile(root: File): File? {
        return findFirstExisting(
            root,
            listOf(
                "app/src/main/res/values/strings.xml",
                "src/main/res/values/strings.xml",
                "res/values/strings.xml"
            )
        ) ?: root.walkTopDown().firstOrNull { it.isFile && it.name == "strings.xml" }
    }

    private fun parseStringsXml(xml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (xml.isBlank()) return map
        runCatching {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            var currentKey: String? = null
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "string") {
                            currentKey = parser.getAttributeValue(null, "name")
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val key = currentKey
                        if (!key.isNullOrBlank()) {
                            map[key] = parser.text.orEmpty().trim()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "string") currentKey = null
                    }
                }
                event = parser.next()
            }
        }
        return map
    }

    private fun parseManifest(
        xml: String,
        stringsMap: Map<String, String>,
        fallbackPackage: String,
        fallbackName: String
    ): ManifestSummary {
        var rawLabel = ""
        val permissions = mutableListOf<String>()
        val activities = mutableListOf<String>()
        var launcherActivity = ".MainActivity"
        var currentActivity: String? = null
        var isCurrentLauncher = false

        if (xml.isNotBlank()) {
            runCatching {
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(StringReader(xml))
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "application" -> {
                                rawLabel = getAndroidAttr(parser, "label").orEmpty()
                            }
                            "uses-permission" -> {
                                getAndroidAttr(parser, "name")?.let { permissions.add(it) }
                            }
                            "activity" -> {
                                val actName = getAndroidAttr(parser, "name")
                                if (!actName.isNullOrBlank()) {
                                    activities.add(actName)
                                    currentActivity = actName
                                    isCurrentLauncher = false
                                }
                            }
                            "action" -> {
                                val action = getAndroidAttr(parser, "name")
                                if (action == "android.intent.action.MAIN") {
                                    isCurrentLauncher = true
                                }
                            }
                            "category" -> {
                                val cat = getAndroidAttr(parser, "name")
                                if (cat == "android.intent.category.LAUNCHER" && isCurrentLauncher && currentActivity != null) {
                                    launcherActivity = currentActivity!!
                                }
                            }
                        }
                    } else if (event == XmlPullParser.END_TAG && parser.name == "activity") {
                        currentActivity = null
                    }
                    event = parser.next()
                }
            }
        }

        val resolvedLabel = when {
            rawLabel.startsWith("@string/") -> {
                val key = rawLabel.removePrefix("@string/")
                stringsMap[key]?.takeIf { it.isNotBlank() } ?: fallbackName
            }
            rawLabel.isNotBlank() -> rawLabel
            stringsMap["app_name"]?.isNotBlank() == true -> stringsMap["app_name"]!!
            else -> fallbackName
        }

        val normalizedLauncher = when {
            launcherActivity.startsWith(".") -> "$fallbackPackage$launcherActivity"
            !launcherActivity.contains(".") -> "$fallbackPackage.$launcherActivity"
            else -> launcherActivity
        }

        return ManifestSummary(
            appLabel = resolvedLabel,
            launcherActivity = normalizedLauncher,
            permissions = permissions.distinct(),
            activities = activities.ifEmpty { listOf(".MainActivity") },
            rawManifestXml = xml
        )
    }

    private fun getAndroidAttr(parser: XmlPullParser, attrName: String): String? {
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)
            if (name == attrName || name == "android:$attrName") {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun extractQuotedProperty(text: String, prop: String): String? {
        val regex = Regex("""${Regex.escape(prop)}\s*=\s*["']([^"']+)["']""")
        return regex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractGradleString(text: String, key: String): String? {
        val regex = Regex("""\b$key\s*=?\s*["']([^"']+)["']""")
        return regex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractGradleInt(text: String, key: String): Int? {
        val regex = Regex("""\b$key\s*=?\s*(\d+)""")
        return regex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractManifestAttribute(xml: String, attr: String): String? {
        val regex = Regex("""(?:android:)?$attr\s*=\s*["']([^"']+)["']""")
        return regex.find(xml)?.groupValues?.getOrNull(1)
    }

    private fun extractManifestIntAttribute(xml: String, attr: String): Int? {
        return extractManifestAttribute(xml, attr)?.toIntOrNull()
    }

    private fun extractManifestUsesSdk(xml: String, attr: String): Int? {
        return extractManifestAttribute(xml, attr)?.toIntOrNull()
    }

    private fun extractPlugins(gradleText: String): List<String> {
        val list = mutableListOf<String>()
        Regex("""alias\(([^)]+)\)""").findAll(gradleText).forEach {
            list.add(it.groupValues[1].trim())
        }
        Regex("""id\(\s*["']([^"']+)["']\s*\)""").findAll(gradleText).forEach {
            list.add(it.groupValues[1].trim())
        }
        Regex("""apply\s+plugin:\s*["']([^"']+)["']""").findAll(gradleText).forEach {
            list.add(it.groupValues[1].trim())
        }
        return list.distinct().take(12)
    }

    private fun extractDependencies(gradleText: String): List<String> {
        val list = mutableListOf<String>()
        Regex("""(?:implementation|api|ksp|kapt|testImplementation)\s*\(?\s*["']([^"']+)["']\s*\)?""")
            .findAll(gradleText)
            .forEach { list.add(it.groupValues[1].trim()) }
        Regex("""(?:implementation|api|ksp)\s*\(\s*(libs\.[a-zA-Z0-9_.]+)\s*\)""")
            .findAll(gradleText)
            .forEach { list.add(it.groupValues[1].trim()) }
        return list.distinct().take(24)
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun File.readTextSafe(): String {
        return runCatching { readText(Charsets.UTF_8) }.getOrDefault("")
    }
}
