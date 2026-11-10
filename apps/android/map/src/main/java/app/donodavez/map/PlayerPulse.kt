package app.donodavez.map

/**
 * Anel pulsante sob o jogador: [CYCLES] pulsos a cada leitura nova do GPS e depois o anel some.
 * Pulsar o tempo todo obrigava o mapa 3D a redesenhar ~30 vezes por segundo mesmo com o jogador parado,
 * o que gasta bateria. Com leituras a cada 10 s parado, o mapa fica sem redesenhar a maior parte do tempo.
 */
internal object PlayerPulse {
    const val PERIOD_MS = 1_800L
    const val CYCLES = 2

    /** Fase (0..1, o anel cresce e some) [elapsedMs] após o início da série, ou null quando a série acabou. */
    fun phase(elapsedMs: Long): Float? =
        if (elapsedMs < 0 || elapsedMs >= PERIOD_MS * CYCLES) null
        else (elapsedMs % PERIOD_MS).toFloat() / PERIOD_MS

    /**
     * Início da série para uma leitura nova em [nowMs]. Com o anel já pulsando, alinha ao ciclo em curso
     * (o anel não volta ao começo no meio de um pulso) e prolonga a série; parado, começa do zero.
     */
    fun origin(previousOriginMs: Long?, nowMs: Long): Long {
        val elapsed = previousOriginMs?.let { nowMs - it } ?: return nowMs
        return if (phase(elapsed) == null) nowMs else previousOriginMs + elapsed / PERIOD_MS * PERIOD_MS
    }
}
