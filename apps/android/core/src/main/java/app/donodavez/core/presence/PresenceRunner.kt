package app.donodavez.core.presence

import app.donodavez.core.game.GameRepository
import app.donodavez.core.location.LocationClient
import app.donodavez.core.location.LocationPermission
import app.donodavez.core.net.ApiClient
import app.donodavez.core.net.ApiException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom
import kotlin.math.roundToInt

/**
 * Obtém uma prova de presença (30 s, uso único) com uma leitura nova do GPS. O servidor calcula a
 * distância com ST_DWithin; o app nunca afirma "estou perto". Checagens locais só melhoram a mensagem.
 */
class PresenceRunner(
    private val api: ApiClient,
    private val location: LocationClient,
    private val game: GameRepository,
) {
    private val random = SecureRandom()

    suspend fun prove(propertyId: String): String {
        when (location.permission()) {
            LocationPermission.DENIED -> throw ApiException(0, "PRESENCE_REQUIRED", "Permita o acesso à localização para negociar no local")
            LocationPermission.APPROXIMATE -> throw ApiException(0, "PRESENCE_REQUIRED", "Ative a localização precisa para negociar no local")
            LocationPermission.PRECISE -> Unit
        }
        val cfg = game.gameConfig()
        val fix = location.freshFix()
            ?: throw ApiException(0, "LOCATION_STALE", "Não foi possível obter sua localização agora; tente em um local aberto")
        if (fix.accuracyM > cfg.maxGpsAccuracyM) {
            throw ApiException(0, "LOCATION_INACCURATE",
                "Sinal de GPS impreciso (±${fix.accuracyM.roundToInt()} m). Vá para um local aberto e tente de novo")
        }
        val body = buildJsonObject {
            put("property_id", propertyId)
            put("lat", fix.lat)
            put("lon", fix.lon)
            put("accuracy_m", fix.accuracyM.toDouble())
            put("fix_age_ms", fix.ageMs().coerceAtLeast(0))
            fix.speedMps?.let { put("speed_mps", it.toDouble()) }
            put("nonce", nonce())
            put("mock_location", fix.isMock)
        }
        val res = api.post("/v1/presence", body)
        return (res["proof_id"] as? JsonPrimitive)?.contentOrNull
            ?: throw ApiException(422, "PRESENCE_REQUIRED", "Não foi possível confirmar sua presença")
    }

    private fun nonce(): String {
        val bytes = ByteArray(18).also(random::nextBytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }
}
