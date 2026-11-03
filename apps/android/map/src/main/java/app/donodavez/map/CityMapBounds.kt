package app.donodavez.map

/**
 * Janela de navegação do jogo para o município de São Paulo (SP).
 *
 * Os limites retangulares são deliberadamente aproximados, apenas para restringir a câmera.
 * A delimitação exata das propriedades negociáveis é feita pelas regiões oficiais importadas
 * no PostGIS, jamais por este retângulo do cliente.
 */
object CityMapBounds {
    const val WEST = -46.86
    const val EAST = -46.33
    const val SOUTH = -24.02
    const val NORTH = -23.34
    const val CENTER_LAT = -23.5505
    const val CENTER_LON = -46.6333

    // Não permitir afastar até o nível de cidade/estado: cada consulta é um bairro ou quarteirão.
    const val MIN_ZOOM = 15.5
    const val MAX_ZOOM = 19.5

    fun contains(lat: Double, lon: Double): Boolean =
        lat in SOUTH..NORTH && lon in WEST..EAST
}
