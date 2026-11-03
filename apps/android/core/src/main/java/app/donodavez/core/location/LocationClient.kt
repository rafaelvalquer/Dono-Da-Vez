package app.donodavez.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

data class LocationSample(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val elapsedRealtimeNanos: Long,
    val speedMps: Float?,
    val bearing: Float?,
    val isMock: Boolean,
) {
    fun ageMs(): Long = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
}

enum class LocationPermission { DENIED, APPROXIMATE, PRECISE }

/**
 * Fused Location em primeiro plano. Frequência adaptativa: 3 s em movimento, 10 s parado.
 * As atualizações param quando o coletor do Flow é cancelado (tela fora de foco/app em segundo plano).
 */
class LocationClient(private val context: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    fun permission(): LocationPermission {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return when {
            fine -> LocationPermission.PRECISE
            coarse -> LocationPermission.APPROXIMATE
            else -> LocationPermission.DENIED
        }
    }

    @SuppressLint("MissingPermission")
    fun updates(): Flow<LocationSample> = callbackFlow {
        if (permission() == LocationPermission.DENIED) {
            close(); return@callbackFlow
        }
        var intervalMs = MOVING_INTERVAL_MS
        lateinit var callback: LocationCallback

        fun request(ms: Long) {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ms)
                .setMinUpdateIntervalMillis(ms / 2)
                .setWaitForAccurateLocation(false)
                .build()
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        }

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val sample = loc.toSample()
                trySend(sample)
                val moving = (sample.speedMps ?: 0f) > 0.8f
                val wanted = if (moving) MOVING_INTERVAL_MS else STILL_INTERVAL_MS
                if (wanted != intervalMs) {
                    intervalMs = wanted
                    fused.removeLocationUpdates(this)
                    request(wanted)
                }
            }
        }
        request(intervalMs)
        awaitClose { fused.removeLocationUpdates(callback) }
    }

    /** Amostra recente e precisa para ações presenciais (não usa posição suavizada do avatar). */
    @SuppressLint("MissingPermission")
    suspend fun freshFix(timeoutMs: Long = 15_000): LocationSample? {
        if (permission() == LocationPermission.DENIED) return null
        val cts = CancellationTokenSource()
        return try {
            withTimeoutOrNull(timeoutMs) {
                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()?.toSample()
            }
        } finally {
            cts.cancel()
        }
    }

    companion object {
        const val MOVING_INTERVAL_MS = 3_000L
        const val STILL_INTERVAL_MS = 10_000L
    }
}

private fun Location.toSample(): LocationSample = LocationSample(
    lat = latitude,
    lon = longitude,
    accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    speedMps = if (hasSpeed()) speed else null,
    bearing = if (hasBearing()) bearing else null,
    isMock = if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider,
)
