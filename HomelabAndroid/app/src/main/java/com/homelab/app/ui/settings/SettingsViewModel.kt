package com.homelab.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.glance.appwidget.updateAll
import com.homelab.app.widget.KinderDashWidget
import com.homelab.app.BuildConfig
import com.homelab.app.data.repository.LanguageMode
import com.homelab.app.data.repository.LocalPreferencesRepository
import com.homelab.app.data.repository.ServicesRepository
import com.homelab.app.data.repository.ThemeMode
import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.util.AppIconManager
import com.homelab.app.util.AppIconOption
import com.homelab.app.util.ServiceType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val servicesRepository: ServicesRepository,
    private val localPreferencesRepository: LocalPreferencesRepository,
    private val appIconManager: AppIconManager
) : ViewModel() {

    data class UpdateBannerState(
        val latestVersion: String,
        val currentVersion: String,
        val updateUrl: String
    )

    data class UpdatePopupState(
        val latestVersion: String,
        val changelog: String?,
        val updateUrl: String
    )

    private val _updateBannerState = MutableStateFlow<UpdateBannerState?>(null)
    val updateBannerState: StateFlow<UpdateBannerState?> = _updateBannerState

    private val _updatePopupState = MutableStateFlow<UpdatePopupState?>(null)
    val updatePopupState: StateFlow<UpdatePopupState?> = _updatePopupState

    private val _appIconApplying = MutableStateFlow(false)
    val appIconApplying: StateFlow<Boolean> = _appIconApplying

    // Both come from build config and are empty unless a build explicitly opts in — see the
    // buildConfigField block in app/build.gradle.kts for why the default matters.
    private val updateManifestUrl = BuildConfig.UPDATE_MANIFEST_URL
    private val defaultUpdateUrl = BuildConfig.UPDATE_RELEASES_URL
    private val updateCheckIntervalMs = 15 * 60 * 1000L

    /** True when this build has been given somewhere to check. */
    private val updateCheckEnabled: Boolean get() = updateManifestUrl.isNotBlank()

    /**
     * Where "Update" should send the user: the manifest's own link if it has one, else the build's
     * releases URL. Null when neither exists — a banner whose button opens nothing is worse than
     * no banner.
     */
    private fun resolveUpdateUrl(candidate: String?): String? =
        candidate?.takeIf { it.isNotBlank() } ?: defaultUpdateUrl.takeIf { it.isNotBlank() }

    val instancesByType: StateFlow<Map<ServiceType, List<ServiceInstance>>> = servicesRepository.instancesByType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val preferredInstanceIdByType: StateFlow<Map<ServiceType, String?>> = servicesRepository.preferredInstanceIdByType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val themeMode: StateFlow<ThemeMode> = localPreferencesRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val languageMode: StateFlow<LanguageMode> = localPreferencesRepository.languageMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LanguageMode.ENGLISH)

    val hiddenServices: StateFlow<Set<String>> = localPreferencesRepository.hiddenServices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val appIcon: StateFlow<AppIconOption> = localPreferencesRepository.appIcon
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppIconOption.DEFAULT)

    val serviceOrder: StateFlow<List<ServiceType>> = localPreferencesRepository.serviceOrder
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            ServiceType.entries.filter { it != ServiceType.UNKNOWN }
        )

    val biometricEnabled: StateFlow<Boolean> = localPreferencesRepository.biometricEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isPinSet: StateFlow<Boolean> = localPreferencesRepository.appPin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
        .let { flow ->
            kotlinx.coroutines.flow.combine(flow, kotlinx.coroutines.flow.flowOf(Unit)) { pin, _ -> pin != null }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        }

    private val _storedPin = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            localPreferencesRepository.appPin.collect { _storedPin.value = it }
        }
        viewModelScope.launch {
            checkForUpdateBanner(force = false)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            localPreferencesRepository.setThemeMode(mode)
        }
    }

    fun setLanguageMode(mode: LanguageMode) {
        viewModelScope.launch {
            localPreferencesRepository.setLanguageMode(mode)
        }
        // Apply locale change on all API levels
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(mode.code)
        )
    }

    fun setAppIcon(icon: AppIconOption) {
        viewModelScope.launch {
            if (appIcon.value == icon) return@launch
            _appIconApplying.value = true
            localPreferencesRepository.setAppIcon(icon)
            try {
                appIconManager.apply(icon)
            } finally {
                _appIconApplying.value = false
            }
        }
    }

    val widgetTitle: StateFlow<String> = localPreferencesRepository.widgetTitle
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            LocalPreferencesRepository.DEFAULT_WIDGET_TITLE
        )

    val nextcloudCapacityGb: StateFlow<Int> = localPreferencesRepository.nextcloudCapacityGb
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Blank or unparseable clears the value, which turns the storage bar off rather than guessing. */
    fun setNextcloudCapacityGb(raw: String) {
        viewModelScope.launch {
            localPreferencesRepository.setNextcloudCapacityGb(raw.trim().toIntOrNull() ?: 0)
            runCatching { KinderDashWidget().updateAll(appContext) }
        }
    }

    fun setWidgetTitle(title: String) {
        viewModelScope.launch {
            localPreferencesRepository.setWidgetTitle(title)
            // Re-render the widget directly rather than enqueuing a refresh: the title comes from
            // preferences, not from the services, so there is nothing to re-fetch. Without this the
            // change would not appear until the next 15-minute cycle.
            runCatching { KinderDashWidget().updateAll(appContext) }
        }
    }

    fun toggleServiceVisibility(type: ServiceType) {
        viewModelScope.launch {
            localPreferencesRepository.toggleServiceVisibility(type.name)
        }
    }

    fun moveService(type: ServiceType, offset: Int) {
        viewModelScope.launch {
            localPreferencesRepository.moveService(type, offset)
        }
    }

    /**
     * Moves [type] one place within [within], ignoring anything outside that set.
     *
     * Needed because the settings list only shows visible services. A plain [moveService] shifts by
     * one position in the *global* order, so swapping with a hidden neighbour would look like the
     * button did nothing at all.
     */
    fun moveServiceWithin(type: ServiceType, offset: Int, within: Set<ServiceType>) {
        viewModelScope.launch {
            localPreferencesRepository.moveServiceWithin(type, offset, within)
        }
    }

    fun deleteInstance(instanceId: String) {
        viewModelScope.launch {
            servicesRepository.disconnectInstance(instanceId)
        }
    }

    fun setPreferredInstance(type: ServiceType, instanceId: String) {
        viewModelScope.launch {
            servicesRepository.setPreferredInstance(type, instanceId)
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            localPreferencesRepository.setBiometricEnabled(enabled)
        }
    }

    fun savePin(pin: String) {
        viewModelScope.launch {
            localPreferencesRepository.savePin(pin)
        }
    }

    fun verifyPin(pin: String): Boolean {
        return _storedPin.value == pin
    }

    fun clearSecurity() {
        viewModelScope.launch {
            localPreferencesRepository.clearSecurity()
        }
    }

    fun dismissUpdateBanner() {
        val latest = _updateBannerState.value?.latestVersion ?: return
        viewModelScope.launch {
            localPreferencesRepository.setDismissedUpdateVersion(latest)
            _updateBannerState.value = null
        }
    }

    fun dismissUpdatePopup() {
        val latest = _updatePopupState.value?.latestVersion ?: return
        viewModelScope.launch {
            localPreferencesRepository.setDismissedPopupVersion(latest)
            _updatePopupState.value = null
        }
    }

    fun refreshUpdateBanner(force: Boolean = true) {
        viewModelScope.launch {
            checkForUpdateBanner(force = force)
        }
    }

    private suspend fun checkForUpdateBanner(force: Boolean) {
        if (!updateCheckEnabled) {
            // No manifest configured for this build: never fetch, never surface anything.
            _updateBannerState.value = null
            _updatePopupState.value = null
            return
        }

        val current = BuildConfig.VERSION_NAME
        val dismissed = localPreferencesRepository.dismissedUpdateVersion.firstOrNull()
        val dismissedPopup = localPreferencesRepository.dismissedPopupVersion.firstOrNull()
        val lastCheckedAt = localPreferencesRepository.updateLastCheckedAt.firstOrNull()
        val cachedLatest = localPreferencesRepository.updateAvailableVersion.firstOrNull()
        val cachedUrl = localPreferencesRepository.updateAvailableUrl.firstOrNull()
        val cachedChangelog = localPreferencesRepository.updateAvailableChangelog.firstOrNull()
        val now = System.currentTimeMillis()

        val cachedUpdateUrl = resolveUpdateUrl(cachedUrl)

        val cachedState = cachedLatest
            ?.trim()
            ?.takeIf { it.isNotEmpty() && compareVersions(it, current) > 0 && dismissed != it }
            ?.let { version ->
                cachedUpdateUrl?.let { url ->
                    UpdateBannerState(
                        latestVersion = version,
                        currentVersion = current,
                        updateUrl = url
                    )
                }
            }

        _updateBannerState.value = cachedState

        // Restore popup from cache
        cachedLatest?.trim()
            ?.takeIf { it.isNotEmpty() && compareVersions(it, current) > 0 && dismissed != it && dismissedPopup != it }
            ?.let { version ->
                _updatePopupState.value = cachedUpdateUrl?.let { url ->
                    UpdatePopupState(
                        latestVersion = version,
                        changelog = cachedChangelog,
                        updateUrl = url
                    )
                }
            }

        if (!force && lastCheckedAt != null && (now - lastCheckedAt) < updateCheckIntervalMs) {
            return
        }

        val payload = fetchUpdateManifest() ?: run {
            return
        }

        localPreferencesRepository.setUpdateLastCheckedAt(now)

        val latest = payload.latest.trim()
        if (latest.isEmpty()) {
            localPreferencesRepository.setAvailableUpdate(version = null, url = null, changelog = null)
            _updateBannerState.value = null
            _updatePopupState.value = null
            return
        }

        val updateUrl = resolveUpdateUrl(payload.androidUrl)
        val isNewer = compareVersions(latest, current) > 0
        if (updateUrl == null || !isNewer) {
            localPreferencesRepository.setAvailableUpdate(version = null, url = null, changelog = null)
            _updateBannerState.value = null
            _updatePopupState.value = null
            return
        }

        localPreferencesRepository.setAvailableUpdate(version = latest, url = updateUrl, changelog = payload.changelog)
        val shouldShow = dismissed != latest

        _updateBannerState.value = if (shouldShow) {
            UpdateBannerState(
                latestVersion = latest,
                currentVersion = current,
                updateUrl = updateUrl
            )
        } else {
            null
        }

        // Popup: only if not dismissed for this version
        _updatePopupState.value = if (shouldShow && dismissedPopup != latest) {
            UpdatePopupState(
                latestVersion = latest,
                changelog = payload.changelog,
                updateUrl = updateUrl
            )
        } else {
            null
        }
    }

    private suspend fun fetchUpdateManifest(): UpdateManifest? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(updateManifestUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                UpdateManifest(
                    latest = json.optString("latest"),
                    changelog = json.optString("changelog").takeIf { it.isNotBlank() },
                    androidUrl = json.optString("android_url").takeIf { it.isNotBlank() }
                )
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun compareVersions(leftVersion: String, rightVersion: String): Int {
        val left = leftVersion.split('.').map { it.toIntOrNull() ?: 0 }
        val right = rightVersion.split('.').map { it.toIntOrNull() ?: 0 }
        val maxSize = maxOf(left.size, right.size)
        for (index in 0 until maxSize) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private data class UpdateManifest(
        val latest: String,
        val changelog: String?,
        val androidUrl: String?
    )
}
