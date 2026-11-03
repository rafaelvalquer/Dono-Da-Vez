package app.donodavez.core.time

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** O servidor é o relógio das regras: prazos exibidos usam a diferença medida em cada resposta. */
class ServerClock {
    @Volatile
    private var skewMs: Long = 0

    fun update(serverTimeIso: String) {
        parseInstant(serverTimeIso)?.let { skewMs = it.toEpochMilli() - System.currentTimeMillis() }
    }

    fun now(): Instant = Instant.ofEpochMilli(System.currentTimeMillis() + skewMs)

    fun remaining(untilIso: String?): Duration? = parseInstant(untilIso)?.let { Duration.between(now(), it) }
}

fun parseInstant(iso: String?): Instant? {
    if (iso.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(iso).toInstant() }
        .recoverCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso.replace(' ', 'T').let { if (it.length > 19 && (it.endsWith("+00") || it.endsWith("-00"))) "$it:00" else it }).toInstant() }
        .getOrNull()
}
