package app.donodavez.map

import app.donodavez.map.PlayerPulse.CYCLES
import app.donodavez.map.PlayerPulse.PERIOD_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPulseTest {
    @Test fun pulsesForAFewCyclesThenStops() {
        assertEquals(0f, PlayerPulse.phase(0)!!, 0f)
        assertEquals(0.5f, PlayerPulse.phase(PERIOD_MS / 2)!!, 1e-6f)
        assertEquals(0f, PlayerPulse.phase(PERIOD_MS)!!, 0f)
        assertEquals(0.5f, PlayerPulse.phase(PERIOD_MS * (CYCLES - 1) + PERIOD_MS / 2)!!, 1e-6f)
        assertNull(PlayerPulse.phase(PERIOD_MS * CYCLES))
        assertNull(PlayerPulse.phase(PERIOD_MS * CYCLES + 10_000))
        assertNull(PlayerPulse.phase(-1))
    }

    @Test fun stoppedPlayerIsIdleMostOfTheTime() {
        // Parado, o GPS lê a cada 10 s (LocationClient): o anel não pode ocupar o intervalo inteiro.
        assertTrue(PERIOD_MS * CYCLES < 10_000 / 2)
    }

    @Test fun firstReadingOrAfterTheSeriesStartsFromZero() {
        assertEquals(5_000L, PlayerPulse.origin(null, 5_000))
        val origin = 1_000L
        val after = origin + PERIOD_MS * CYCLES + 1
        assertEquals(after, PlayerPulse.origin(origin, after))
    }

    @Test fun readingWhilePulsingKeepsThePhaseAndExtendsTheSeries() {
        val origin = 1_000L
        val now = origin + PERIOD_MS + 700 // meio do segundo pulso
        val next = PlayerPulse.origin(origin, now)
        assertEquals(PlayerPulse.phase(now - origin), PlayerPulse.phase(now - next))
        assertEquals(origin + PERIOD_MS, next)
        // A série nova vai além do fim da anterior.
        assertTrue(next + PERIOD_MS * CYCLES > origin + PERIOD_MS * CYCLES)
    }
}
