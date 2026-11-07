package com.awaken.app.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.awaken.app.data.settings.AppSettings
import com.awaken.app.di.AppContainer
import com.awaken.app.update.UpdateUi
import com.awaken.app.ui.components.AwakenLogo
import com.awaken.app.ui.components.GhostButton
import com.awaken.app.ui.components.GlassCard
import com.awaken.app.ui.components.GoldButton
import com.awaken.app.ui.components.SectionLabel
import com.awaken.app.ui.components.StatusPill
import com.awaken.app.ui.components.SwitchRow
import com.awaken.app.ui.components.glow
import com.awaken.app.ui.nav.Fmt
import com.awaken.app.ui.nav.LocalContainer
import com.awaken.app.ui.nav.Permissions
import com.awaken.app.ui.nav.ScreenColumn
import com.awaken.app.ui.nav.awakenViewModel
import com.awaken.app.ui.theme.LocalPalette
import com.awaken.core.update.UpdateManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class UpdateViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<UpdateUi> = c.updates.state
    val settings = c.settings.settings

    private val _remoteStatus = MutableStateFlow("")
    val remoteStatus: StateFlow<String> = _remoteStatus
    private val _installHint = MutableStateFlow("")
    val installHint: StateFlow<String> = _installHint

    val installedName get() = c.updates.installedVersionName
    val installedCode get() = c.updates.installedVersionCode

    fun check() = viewModelScope.launch { c.updates.check() }
    fun download(m: UpdateManifest, mandatory: Boolean) = viewModelScope.launch { c.updates.downloadAndVerify(m, mandatory) }

    /** Opens Android's package installer for the verified file. Returns the intent to launch, or null. */
    fun prepareInstall(m: UpdateManifest): android.content.Intent? {
        if (!c.updates.canRequestInstall()) {
            _installHint.value = "Android needs your permission first. Allow AWAKEN to install apps in the screen that opens, come back, then tap Install again."
            return c.updates.installPermissionIntent()
        }
        _installHint.value = ""
        val i = c.updates.installIntent() ?: run {
            _installHint.value = "The verified file is no longer available. Download it again."
            return null
        }
        c.updates.markInstallerOpened(m)
        return i
    }

    fun saveManifestUrl(url: String) = viewModelScope.launch { c.settings.setUpdateManifestUrl(url) }
    fun setAutoCheck(v: Boolean) = viewModelScope.launch { c.settings.setAutoCheckUpdates(v) }
    fun setRemoteEnabled(v: Boolean) = viewModelScope.launch { c.settings.setRemoteConfigEnabled(v) }
    fun saveRemoteUrl(url: String) = viewModelScope.launch { c.settings.setRemoteConfigUrl(url) }
    fun refreshRemote() = viewModelScope.launch { _remoteStatus.value = "Fetching…"; _remoteStatus.value = c.remote.refresh() }
}

/** The whole update flow in one card, shared by the Update Center and the mandatory-update lock screen. */
@Composable
fun UpdateFlowCard(vm: UpdateViewModel, mandatoryContext: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val hint by vm.installHint.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val p = LocalPalette.current

    fun launchInstall(m: UpdateManifest) {
        vm.prepareInstall(m)?.let { Permissions.launch(ctx, it) }
    }

    GlassCard(accent = mandatoryContext || state is UpdateUi.Available) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Installed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${vm.installedName} (${vm.installedCode})", style = MaterialTheme.typography.titleLarge)
                }
                when (val s = state) {
                    is UpdateUi.Available -> StatusPill(if (s.mandatory) "REQUIRED" else "AVAILABLE", if (s.mandatory) MaterialTheme.colorScheme.error else p.primary)
                    is UpdateUi.UpToDate -> StatusPill("UP TO DATE", p.primary)
                    else -> {}
                }
            }

            when (val s = state) {
                UpdateUi.Idle -> {
                    Text("Check the update server for a newer version.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GoldButton("Check for updates", { vm.check() }, Modifier.fillMaxWidth(), Icons.Rounded.Refresh)
                }
                UpdateUi.NotConfigured -> Text(
                    "No update server address is set. Enter your manifest URL below to enable updates.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateUi.Checking -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking…") }
                is UpdateUi.UpToDate -> {
                    Text("You have the latest version. Last checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.checkedAt))}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GhostButton("Check again", { vm.check() }, Modifier.fillMaxWidth(), Icons.Rounded.Refresh)
                }
                is UpdateUi.Available -> {
                    ManifestDetails(s.manifest)
                    GoldButton("Download and verify", { vm.download(s.manifest, s.mandatory) }, Modifier.fillMaxWidth(), Icons.Rounded.CloudDownload)
                    Text("The file is downloaded over HTTPS and checked (size, checksum, package, version and signing certificate) before Android's installer is opened.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is UpdateUi.Downloading -> {
                    Text("Downloading ${s.manifest.versionName}…")
                    LinearProgressIndicator(progress = { (s.bytes.toFloat() / s.manifest.sizeBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${Fmt.size(s.bytes)} of ${Fmt.size(s.manifest.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                }
                UpdateUi.Verifying -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Verifying the download…") }
                is UpdateUi.ReadyToInstall -> {
                    Text("Verified: checksum, package identity, version and signature all match. Nothing is installed yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GoldButton("Install ${s.manifest.versionName}", { launchInstall(s.manifest) }, Modifier.fillMaxWidth(), Icons.Rounded.SystemUpdate)
                    if (hint.isNotEmpty()) Text(hint, color = p.primary, style = MaterialTheme.typography.bodySmall)
                }
                is UpdateUi.AwaitingAndroid -> {
                    Text("Android's installer is open. AWAKEN will only report this update as installed once Android confirms the new version.")
                    GhostButton("Open installer again", { launchInstall(s.manifest) }, Modifier.fillMaxWidth())
                }
                is UpdateUi.InstalledConfirmed -> Text("Android confirms version ${s.versionName} is installed.", color = p.primary)
                is UpdateUi.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    if (s.manifest != null) GoldButton("Try again", { vm.download(s.manifest, s.mandatory) }, Modifier.fillMaxWidth(), Icons.Rounded.CloudDownload)
                    GhostButton("Check for updates", { vm.check() }, Modifier.fillMaxWidth(), Icons.Rounded.Refresh)
                }
            }
        }
    }
}

@Composable
private fun ManifestDetails(m: UpdateManifest) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Latest", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${m.versionName} (${m.versionCode})", style = MaterialTheme.typography.titleLarge)
        Text(
            buildString {
                append(Fmt.size(m.sizeBytes))
                if (m.releaseDate.isNotEmpty()) append("  ·  released ").append(m.releaseDate)
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (m.changelog.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("What's new", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            m.changelog.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
fun UpdateScreen(onBack: () -> Unit) {
    val vm = awakenViewModel { UpdateViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val remoteStatus by vm.remoteStatus.collectAsStateWithLifecycle()
    val config by LocalContainer.current.remote.config.collectAsStateWithLifecycle()
    var manifestUrl by remember(settings.updateManifestUrl) { mutableStateOf(settings.updateManifestUrl) }
    var remoteUrl by remember(settings.remoteConfigUrl) { mutableStateOf(settings.remoteConfigUrl) }

    LaunchedEffect(Unit) { vm.check() }

    ScreenColumn("Update center", onBack) {
        UpdateFlowCard(vm, mandatoryContext = false)

        SectionLabel("Update server")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    manifestUrl, { manifestUrl = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Update manifest URL (https)") },
                )
                GhostButton("Save and check", { vm.saveManifestUrl(manifestUrl); vm.check() }, Modifier.fillMaxWidth())
                SwitchRow("Check automatically", "At most every 6 hours while the app is used", settings.autoCheckUpdates, vm::setAutoCheck)
            }
        }

        SectionLabel("Remote configuration")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Remote configuration can change branding, themes, dashboard layout, announcements, feature flags and suggested content. It is plain data for features already in this app; it never runs code.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow("Use remote configuration", null, settings.remoteConfigEnabled, vm::setRemoteEnabled)
                OutlinedTextField(
                    remoteUrl, { remoteUrl = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Remote config URL (https)") },
                )
                GhostButton("Save and refresh", { vm.saveRemoteUrl(remoteUrl); vm.refreshRemote() }, Modifier.fillMaxWidth())
                if (remoteStatus.isNotEmpty()) Text(remoteStatus, style = MaterialTheme.typography.bodySmall)
                Text("Applied revision: ${config.revision}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MandatoryUpdateScreen(requiredVersionCode: Long) {
    val vm = awakenViewModel { UpdateViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val p = LocalPalette.current
    var manifestUrl by remember(settings.updateManifestUrl) { mutableStateOf(settings.updateManifestUrl) }
    LaunchedEffect(Unit) { vm.check() }

    Column(
        Modifier.fillMaxSize().glow(p.secondary, 480.dp, .12f).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        AwakenLogo(size = 96.dp)
        Spacer(Modifier.height(12.dp))
        Text("Update required", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "This version of AWAKEN can no longer be used. Install version code $requiredVersionCode or newer to continue. Your schedules and media are kept.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        UpdateFlowCard(vm, mandatoryContext = true)
        if (settings.updateManifestUrl.isBlank()) {
            Spacer(Modifier.height(12.dp))
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(manifestUrl, { manifestUrl = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Update manifest URL (https)") })
                    GhostButton("Save and check", { vm.saveManifestUrl(manifestUrl); vm.check() }, Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "This notice stays until Android reports the required version as installed.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = Color(0xFF8A7F70),
        )
    }
}
