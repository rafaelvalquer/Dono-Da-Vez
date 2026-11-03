package app.donodavez.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("dv_settings")

data class CameraMemory(val lat: Double, val lon: Double, val zoom: Double)

/** Preferências locais (não são regras do jogo). */
class SettingsStore(private val context: Context) {
    private val topViewKey = booleanPreferencesKey("top_view")
    private val onboardingKey = booleanPreferencesKey("onboarding_done")
    private val latKey = doublePreferencesKey("camera_lat")
    private val lonKey = doublePreferencesKey("camera_lon")
    private val zoomKey = doublePreferencesKey("camera_zoom")

    val topView: Flow<Boolean> = context.settingsDataStore.data.map { it[topViewKey] ?: false }
    val onboardingDone: Flow<Boolean> = context.settingsDataStore.data.map { it[onboardingKey] ?: false }

    suspend fun setTopView(value: Boolean) = context.settingsDataStore.edit { it[topViewKey] = value }

    suspend fun setOnboardingDone() = context.settingsDataStore.edit { it[onboardingKey] = true }

    suspend fun saveCamera(camera: CameraMemory) = context.settingsDataStore.edit {
        it[latKey] = camera.lat; it[lonKey] = camera.lon; it[zoomKey] = camera.zoom
    }

    suspend fun lastCamera(): CameraMemory? {
        val p = context.settingsDataStore.data.first()
        val lat = p[latKey] ?: return null
        val lon = p[lonKey] ?: return null
        return CameraMemory(lat, lon, p[zoomKey] ?: 17.5)
    }

    suspend fun clear() = context.settingsDataStore.edit { it.clear() }
}
