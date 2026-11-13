package com.example.engine

import android.content.Context
import com.example.data.SigningProfileConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ApkBuildOutput(
    val apkFile: File,
    val sha256Fingerprint: String,
    val certSha256: String,
    val entryCount: Int,
    val durationMs: Long,
    val logLines: List<String>
)

object ApkSignerAndPackager {

    private const val KEYSTORE_DIR = "keystore"
    private const val PRIVATE_KEY_FILE = "forge_key.pk8"
    private const val CERT_FILE = "forge_cert.der"

    suspend fun getOrCreateSigningSummary(
        context: Context,
        profile: SigningProfileConfig,
        forceRegenerate: Boolean = false
    ): SigningCertSummary = withContext(Dispatchers.IO) {
        val (_, cert) = loadOrGenerateKeyAndCert(context, profile, forceRegenerate)
        val certBytes = cert.encoded
        val sha256 = sha256ColonHex(certBytes)
        val sha1 = sha1ColonHex(certBytes)
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        SigningCertSummary(
            subjectDn = cert.subjectX500Principal.name,
            sha256Fingerprint = sha256,
            sha1Fingerprint = sha1,
            algorithm = "${cert.sigAlgName} (2048-bit)",
            validFrom = df.format(cert.notBefore),
            validUntil = df.format(cert.notAfter),
            serialHex = cert.serialNumber.toString(16).uppercase(Locale.US)
        )
    }

    suspend fun buildAndSignApk(
        context: Context,
        project: AndroidStudioProject,
        override: EditableBuildOverride,
        signingProfile: SigningProfileConfig,
        onStage: suspend (BuildStage, Float, String) -> Unit
    ): Result<ApkBuildOutput> = withContext(Dispatchers.IO) {
        runCatching {
            val startedAt = System.currentTimeMillis()
            val logs = mutableListOf<String>()
            fun log(msg: String) {
                logs.add(msg)
            }

            // Stage 1: Parsing & Applying Overrides
            val stage1Msg = "Applying config (${override.applicationId}, v${override.versionName} [${override.versionCode}], ${override.variant.label})"
            log("[1/5] $stage1Msg")
            onStage(BuildStage.PARSING, 0.15f, stage1Msg)

            val workspaceDir = File(project.workspaceDirPath)
            GradleAndManifestParser.applyOverridesToWorkspace(workspaceDir, override)

            val versionCode = override.versionCode.toIntOrNull()?.coerceAtLeast(1) ?: project.gradleConfig.versionCode
            val minSdk = override.minSdk.toIntOrNull()?.coerceIn(16, 36) ?: project.gradleConfig.minSdk
            val targetSdk = override.targetSdk.toIntOrNull()?.coerceIn(21, 36) ?: project.gradleConfig.targetSdk
            val pkgName = override.applicationId.trim().ifBlank { project.gradleConfig.applicationId }
            val appLabel = override.appLabel.trim().ifBlank { project.projectName }
            val versionName = override.versionName.trim().ifBlank { project.gradleConfig.versionName }

            log("  > Detected ${project.kotlinFileCount} Kotlin, ${project.javaFileCount} Java, ${project.xmlFileCount} XML, ${project.resourceFileCount} resource files")

            // Stage 2: Compile AXML Manifest & resources.arsc
            val stage2Msg = "Compiling binary AndroidManifest.xml (AXML) and resources.arsc"
            log("[2/5] $stage2Msg")
            onStage(BuildStage.RESOURCES, 0.35f, stage2Msg)

            val binaryManifest = BinaryXmlEncoder.encodeManifest(
                packageName = pkgName,
                versionCode = versionCode,
                versionName = versionName,
                minSdk = minSdk,
                targetSdk = targetSdk,
                launcherActivity = project.manifestSummary.launcherActivity,
                permissions = project.manifestSummary.permissions
            )
            val resourcesArsc = ResourcesArscEncoder.encode(
                packageName = pkgName,
                appLabel = appLabel
            )
            log("  > AndroidManifest.xml encoded (${binaryManifest.size} bytes, RES_XML_TYPE 0x0003)")
            log("  > resources.arsc compiled (${resourcesArsc.size} bytes, string/app_name -> \"$appLabel\")")

            // Stage 3: Package DEX bytecode
            val stage3Msg = "Building Dalvik classes.dex for ${project.manifestSummary.launcherActivity}"
            log("[3/5] $stage3Msg")
            onStage(BuildStage.DEX, 0.55f, stage3Msg)

            val existingDexFiles = workspaceDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("dex", ignoreCase = true) }
                .sortedBy { it.name }
                .toList()

            val dexEntries = linkedMapOf<String, ByteArray>()
            if (existingDexFiles.isNotEmpty()) {
                existingDexFiles.forEachIndexed { idx, file ->
                    val dexName = if (idx == 0) "classes.dex" else "classes${idx + 1}.dex"
                    dexEntries[dexName] = file.readBytes()
                }
                log("  > Packaged ${existingDexFiles.size} precompiled DEX file(s) from project workspace")
            } else {
                val launcher = if (project.manifestSummary.launcherActivity.contains(".")) {
                    val shortName = project.manifestSummary.launcherActivity.substringAfterLast('.')
                    "$pkgName.$shortName"
                } else {
                    "$pkgName.MainActivity"
                }
                val generatedDex = DexContainerBuilder.buildMainActivityDex(launcher)
                dexEntries["classes.dex"] = generatedDex
                log("  > Generated classes.dex v035 (${generatedDex.size} bytes, class L${launcher.replace('.', '/')};)")
            }

            // Stage 4: Collect resources, assets, and assemble APK entries
            val stage4Msg = "Assembling APK entries & aligning uncompressed resources"
            log("[4/5] $stage4Msg")
            onStage(BuildStage.PACKAGING, 0.75f, stage4Msg)

            val payloadEntries = linkedMapOf<String, ByteArray>()
            payloadEntries["AndroidManifest.xml"] = binaryManifest
            dexEntries.forEach { (name, bytes) -> payloadEntries[name] = bytes }
            payloadEntries["resources.arsc"] = resourcesArsc

            // Include non-values resource assets & assets/ folder from the Android Studio project
            val resDir = listOf("app/src/main/res", "src/main/res", "res")
                .map { File(workspaceDir, it) }
                .firstOrNull { it.exists() && it.isDirectory }
            var copiedResCount = 0
            if (resDir != null) {
                resDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val rel = file.relativeTo(resDir).invariantSeparatorsPath
                        // Skip raw values/*.xml since compiled into resources.arsc; keep drawables, mipmaps, raw, font, xml, layout
                        if (!rel.startsWith("values")) {
                            payloadEntries["res/$rel"] = file.readBytes()
                            copiedResCount++
                        }
                    }
            }

            val assetsDir = listOf("app/src/main/assets", "src/main/assets", "assets")
                .map { File(workspaceDir, it) }
                .firstOrNull { it.exists() && it.isDirectory }
            var copiedAssetsCount = 0
            if (assetsDir != null) {
                assetsDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val rel = file.relativeTo(assetsDir).invariantSeparatorsPath
                        payloadEntries["assets/$rel"] = file.readBytes()
                        copiedAssetsCount++
                    }
            }

            if (signingProfile.includeSourceMetadata) {
                val summaryProps = buildString {
                    appendLine("project.name=${project.projectName}")
                    appendLine("applicationId=$pkgName")
                    appendLine("versionName=$versionName")
                    appendLine("versionCode=$versionCode")
                    appendLine("variant=${override.variant.label}")
                    appendLine("gradle=${project.gradleConfig.gradleWrapperVersion}")
                    appendLine("sourceFiles.kotlin=${project.kotlinFileCount}")
                    appendLine("sourceFiles.java=${project.javaFileCount}")
                }.toByteArray(Charsets.UTF_8)
                payloadEntries["META-INF/forge-build.properties"] = summaryProps
            }
            log("  > Collected ${payloadEntries.size} archive entries ($copiedResCount res files, $copiedAssetsCount assets)")

            // Stage 5: JAR v1 Signing (MANIFEST.MF, CERT.SF, CERT.RSA) & ZIP output
            val stage5Msg = "Signing APK with RSA-2048 X.509 certificate (JAR v1)"
            log("[5/5] $stage5Msg")
            onStage(BuildStage.SIGNING, 0.90f, stage5Msg)

            val (privateKey, cert) = loadOrGenerateKeyAndCert(context, signingProfile, forceRegenerate = false)
            val manifestMf = buildManifestMf(payloadEntries)
            val certSf = buildCertSf(manifestMf, payloadEntries)
            val certRsa = buildPkcs7SignedData(certSf, privateKey, cert)

            val buildsDir = File(context.filesDir, "builds").apply { mkdirs() }
            val safeFileName = "${pkgName.replace('.', '_')}-v${versionName}-${override.variant.label.lowercase(Locale.US)}.apk"
            val outputApkFile = File(buildsDir, "${System.currentTimeMillis()}_$safeFileName")

            ZipOutputStream(FileOutputStream(outputApkFile)).use { zos ->
                // Write META-INF signature entries first
                writeCompressedEntry(zos, "META-INF/MANIFEST.MF", manifestMf)
                writeCompressedEntry(zos, "META-INF/CERT.SF", certSf)
                writeCompressedEntry(zos, "META-INF/CERT.RSA", certRsa)

                // Write resources.arsc as STORED (uncompressed) for Android 11+ compatibility
                if (signingProfile.alignUncompressedEntries) {
                    writeStoredEntry(zos, "resources.arsc", resourcesArsc)
                } else {
                    writeCompressedEntry(zos, "resources.arsc", resourcesArsc)
                }

                // Write remaining entries
                payloadEntries.forEach { (entryName, bytes) ->
                    if (entryName != "resources.arsc") {
                        writeCompressedEntry(zos, entryName, bytes)
                    }
                }
            }

            val apkBytes = outputApkFile.readBytes()
            val apkSha256 = sha256ColonHex(apkBytes)
            val certSha256 = sha256ColonHex(cert.encoded)
            val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)

            log("  > Signed APK written: $safeFileName (${apkBytes.size} bytes in ${durationMs}ms)")
            log("  > APK SHA-256: $apkSha256")
            onStage(BuildStage.DONE, 1.0f, "APK ready: $safeFileName")

            ApkBuildOutput(
                apkFile = outputApkFile,
                sha256Fingerprint = apkSha256,
                certSha256 = certSha256,
                entryCount = payloadEntries.size + 3,
                durationMs = durationMs,
                logLines = logs
            )
        }
    }

    private fun writeCompressedEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun writeStoredEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        val crc = CRC32().apply { update(data) }.value
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            this.crc = crc
            // 4-byte alignment padding via extra field if needed by standard zip tools
            extra = ByteArray(4)
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun buildManifestMf(entries: Map<String, ByteArray>): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val sb = StringBuilder()
        sb.append("Manifest-Version: 1.0\r\n")
        sb.append("Created-By: APK Forge On-Device Packager\r\n\r\n")
        entries.forEach { (name, bytes) ->
            val digestB64 = Base64.getEncoder().encodeToString(sha256.digest(bytes))
            sb.append("Name: ").append(name).append("\r\n")
            sb.append("SHA-256-Digest: ").append(digestB64).append("\r\n\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun buildCertSf(manifestMf: ByteArray, entries: Map<String, ByteArray>): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val manifestDigest = Base64.getEncoder().encodeToString(sha256.digest(manifestMf))
        val sb = StringBuilder()
        sb.append("Signature-Version: 1.0\r\n")
        sb.append("Created-By: APK Forge Signer\r\n")
        sb.append("SHA-256-Digest-Manifest: ").append(manifestDigest).append("\r\n\r\n")
        entries.forEach { (name, bytes) ->
            val section = "Name: $name\r\nSHA-256-Digest: ${Base64.getEncoder().encodeToString(sha256.digest(bytes))}\r\n\r\n"
            val sectionDigest = Base64.getEncoder().encodeToString(sha256.digest(section.toByteArray(Charsets.UTF_8)))
            sb.append("Name: ").append(name).append("\r\n")
            sb.append("SHA-256-Digest: ").append(sectionDigest).append("\r\n\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun loadOrGenerateKeyAndCert(
        context: Context,
        profile: SigningProfileConfig,
        forceRegenerate: Boolean
    ): Pair<PrivateKey, X509Certificate> {
        val dir = File(context.filesDir, KEYSTORE_DIR).apply { mkdirs() }
        val keyFile = File(dir, PRIVATE_KEY_FILE)
        val certFile = File(dir, CERT_FILE)

        if (!forceRegenerate && keyFile.exists() && certFile.exists()) {
            val loaded = runCatching {
                val keyBytes = keyFile.readBytes()
                val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                privateKey to cert
            }
            if (loaded.isSuccess) return loaded.getOrThrow()
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()
        val certDer = generateSelfSignedX509Cert(keyPair, profile)
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

        keyFile.writeBytes(keyPair.private.encoded)
        certFile.writeBytes(certDer)
        return keyPair.private to cert
    }

    /**
     * Builds a real DER-encoded X.509 v3 self-signed certificate signed with SHA256withRSA.
     */
    private fun generateSelfSignedX509Cert(
        keyPair: KeyPair,
        profile: SigningProfileConfig
    ): ByteArray {
        val now = Date()
        val expiry = Date(now.time + 25L * 365 * 24 * 60 * 60 * 1000) // 25 years
        val serial = BigInteger.valueOf(System.currentTimeMillis() and 0x7FFFFFFFFFFFFFFFL)

        val sha256WithRsaAlgId = derSeq(
            derOid(byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)), // 1.2.840.113549.1.1.11
            byteArrayOf(0x05, 0x00) // NULL
        )

        val dn = buildDistinguishedName(profile)
        val validity = derSeq(derUtcTime(now), derUtcTime(expiry))

        val tbsCert = derSeq(
            derExplicitTag(0, derInteger(BigInteger.valueOf(2))), // v3 = 2
            derInteger(serial),
            sha256WithRsaAlgId,
            dn, // issuer
            validity,
            dn, // subject
            keyPair.public.encoded // SubjectPublicKeyInfo already DER-encoded
        )

        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(tbsCert)
        }.sign()

        return derSeq(
            tbsCert,
            sha256WithRsaAlgId,
            derBitString(sig)
        )
    }

    /**
     * Builds a DER-encoded PKCS#7 ContentInfo(SignedData) signature block for META-INF/CERT.RSA.
     */
    private fun buildPkcs7SignedData(
        contentToSign: ByteArray,
        privateKey: PrivateKey,
        cert: X509Certificate
    ): ByteArray {
        val signatureBytes = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(contentToSign)
        }.sign()

        // OIDs
        val oidSignedData = derOid(byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x07, 0x02)) // 1.2.840.113549.1.7.2
        val oidData = derOid(byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x07, 0x01)) // 1.2.840.113549.1.7.1
        val oidSha256 = derOid(byteArrayOf(0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01)) // 2.16.840.1.101.3.4.2.1
        val oidRsa = derOid(byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01)) // 1.2.840.113549.1.1.1

        val sha256AlgId = derSeq(oidSha256, byteArrayOf(0x05, 0x00))
        val rsaAlgId = derSeq(oidRsa, byteArrayOf(0x05, 0x00))

        val issuerAndSerial = derSeq(
            cert.issuerX500Principal.encoded,
            derInteger(cert.serialNumber)
        )

        val signerInfo = derSeq(
            derInteger(BigInteger.ONE), // version 1
            issuerAndSerial,
            sha256AlgId,
            rsaAlgId,
            derOctetString(signatureBytes)
        )

        val signedData = derSeq(
            derInteger(BigInteger.ONE), // version 1
            derSet(sha256AlgId), // digestAlgorithms
            derSeq(oidData), // encapsulatedContentInfo (detached)
            derImplicitConstructedTag(0, cert.encoded), // certificates [0] IMPLICIT
            derSet(signerInfo) // signerInfos
        )

        return derSeq(
            oidSignedData,
            derExplicitTag(0, signedData)
        )
    }

    private fun buildDistinguishedName(profile: SigningProfileConfig): ByteArray {
        val oidCn = derOid(byteArrayOf(0x55, 0x04, 0x03))
        val oidOu = derOid(byteArrayOf(0x55, 0x04, 0x0b))
        val oidO = derOid(byteArrayOf(0x55, 0x04, 0x0a))
        val oidC = derOid(byteArrayOf(0x55, 0x04, 0x06))

        return derSeq(
            derSet(derSeq(oidCn, derUtf8String(profile.commonName))),
            derSet(derSeq(oidOu, derUtf8String(profile.organizationalUnit))),
            derSet(derSeq(oidO, derUtf8String(profile.organization))),
            derSet(derSeq(oidC, derPrintableString(profile.country.take(2).uppercase(Locale.US))))
        )
    }

    // Minimal ASN.1 DER helpers
    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        writeDerLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    private fun writeDerLength(out: ByteArrayOutputStream, len: Int) {
        if (len < 128) {
            out.write(len)
        } else {
            val bytes = mutableListOf<Byte>()
            var n = len
            while (n > 0) {
                bytes.add(0, (n and 0xff).toByte())
                n = n ushr 8
            }
            out.write(0x80 or bytes.size)
            bytes.forEach { out.write(it.toInt()) }
        }
    }

    private fun derSeq(vararg items: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        items.forEach { body.write(it) }
        return derTag(0x30, body.toByteArray())
    }

    private fun derSet(vararg items: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        items.forEach { body.write(it) }
        return derTag(0x31, body.toByteArray())
    }

    private fun derInteger(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())
    private fun derOctetString(bytes: ByteArray): ByteArray = derTag(0x04, bytes)
    private fun derOid(bytes: ByteArray): ByteArray = derTag(0x06, bytes)
    private fun derUtf8String(s: String): ByteArray = derTag(0x0C, s.toByteArray(Charsets.UTF_8))
    private fun derPrintableString(s: String): ByteArray = derTag(0x13, s.toByteArray(Charsets.US_ASCII))
    private fun derBitString(bytes: ByteArray): ByteArray {
        val payload = ByteArray(bytes.size + 1)
        payload[0] = 0 // unused bits
        System.arraycopy(bytes, 0, payload, 1, bytes.size)
        return derTag(0x03, payload)
    }

    private fun derUtcTime(date: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return derTag(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun derExplicitTag(tagNum: Int, inner: ByteArray): ByteArray = derTag(0xA0 or tagNum, inner)
    private fun derImplicitConstructedTag(tagNum: Int, inner: ByteArray): ByteArray = derTag(0xA0 or tagNum, inner)

    internal fun sha256ColonHex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private fun sha1ColonHex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
