package com.example.ui.screens

import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.SigningProfileConfig
import com.example.engine.ApkInspectionResult
import com.example.engine.SigningCertSummary
import com.example.ui.components.ForgeBadge
import com.example.ui.components.formatByteSize
import com.example.ui.theme.JetBrainsMonoFontFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SigningToolsScreen(
    signingProfile: SigningProfileConfig,
    certSummary: SigningCertSummary?,
    externalApkResult: ApkInspectionResult?,
    isInspectingExternal: Boolean,
    onSaveProfile: (SigningProfileConfig) -> Unit,
    onRegenerateKey: () -> Unit,
    onPickExternalApk: () -> Unit,
    modifier: Modifier = Modifier
) {
    var cn by remember(signingProfile.commonName) { mutableStateOf(signingProfile.commonName) }
    var org by remember(signingProfile.organization) { mutableStateOf(signingProfile.organization) }
    var ou by remember(signingProfile.organizationalUnit) { mutableStateOf(signingProfile.organizationalUnit) }
    var country by remember(signingProfile.country) { mutableStateOf(signingProfile.country) }
    var alignEntries by remember(signingProfile.alignUncompressedEntries) {
        mutableStateOf(signingProfile.alignUncompressedEntries)
    }
    var includeMeta by remember(signingProfile.includeSourceMetadata) {
        mutableStateOf(signingProfile.includeSourceMetadata)
    }

    LaunchedEffect(signingProfile) {
        cn = signingProfile.commonName
        org = signingProfile.organization
        ou = signingProfile.organizationalUnit
        country = signingProfile.country
        alignEntries = signingProfile.alignUncompressedEntries
        includeMeta = signingProfile.includeSourceMetadata
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
            // Active Certificate Card
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
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Active APK Signing Keystore", style = MaterialTheme.typography.titleMedium)
                    }

                    if (certSummary != null) {
                        Text(
                            text = certSummary.subjectDn,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ForgeBadge(text = certSummary.algorithm)
                            ForgeBadge(
                                text = "Valid ${certSummary.validFrom} – ${certSummary.validUntil}",
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "SHA-256: ${certSummary.sha256Fingerprint}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "SHA-1: ${certSummary.sha1Fingerprint}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Initializing RSA-2048 keystore...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedButton(
                        onClick = onRegenerateKey,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("regenerate_key_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Regenerate RSA-2048 Keypair")
                    }
                }
            }

            // Certificate DN & Packager Preferences
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
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Text("Certificate Owner & Packaging", style = MaterialTheme.typography.titleMedium)
                    }

                    OutlinedTextField(
                        value = cn,
                        onValueChange = { cn = it },
                        label = { Text("Common Name (CN)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = org,
                        onValueChange = { org = it },
                        label = { Text("Organization (O)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = ou,
                            onValueChange = { ou = it },
                            label = { Text("Unit (OU)") },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = country,
                            onValueChange = { country = it.take(2).uppercase() },
                            label = { Text("Country") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Store & align resources.arsc", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Required uncompressed alignment for Android 11+",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = alignEntries, onCheckedChange = { alignEntries = it })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Embed build properties", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Writes META-INF/forge-build.properties inside APK",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = includeMeta, onCheckedChange = { includeMeta = it })
                    }

                    Button(
                        onClick = {
                            onSaveProfile(
                                SigningProfileConfig(
                                    commonName = cn,
                                    organization = org,
                                    organizationalUnit = ou,
                                    country = country,
                                    alignUncompressedEntries = alignEntries,
                                    includeSourceMetadata = includeMeta
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_signing_profile_button")
                    ) {
                        Text("Save & Apply Certificate Profile")
                    }
                }
            }

            // External APK Analyzer
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
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text("External APK Binary Analyzer", style = MaterialTheme.typography.titleMedium)
                    }

                    Text(
                        text = "Inspect any .apk file on your device to verify its AXML binary manifest, resources.arsc, DEX count, JAR signature block, and SHA-256 checksum.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = onPickExternalApk,
                        enabled = !isInspectingExternal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("analyze_external_apk_button")
                    ) {
                        if (isInspectingExternal) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Inspecting APK...")
                        } else {
                            Text("Pick .apk File to Inspect")
                        }
                    }

                    if (externalApkResult != null) {
                        HorizontalDivider()
                        Text(
                            text = externalApkResult.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ForgeBadge(text = formatByteSize(externalApkResult.totalSizeBytes))
                            ForgeBadge(text = "${externalApkResult.entryCount} entries")
                            ForgeBadge(
                                text = if (externalApkResult.hasBinaryManifest) "AXML Valid" else "No Binary Manifest",
                                containerColor = if (externalApkResult.hasBinaryManifest)
                                    MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                            )
                            ForgeBadge(text = "${externalApkResult.dexFiles.size} DEX")
                            if (externalApkResult.hasV1Signature) {
                                ForgeBadge(text = "v1 Signed")
                            }
                        }
                        Text(
                            text = "SHA-256: ${externalApkResult.sha256}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                externalApkResult.entries.take(10).forEach { entry ->
                                    Text(
                                        text = "${entry.name} (${formatByteSize(entry.uncompressedSize)})",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (externalApkResult.entries.size > 10) {
                                    Text(
                                        text = "+ ${externalApkResult.entries.size - 10} more entries",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
