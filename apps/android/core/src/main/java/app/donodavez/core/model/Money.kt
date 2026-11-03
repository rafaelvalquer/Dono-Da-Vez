package app.donodavez.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Valores monetários do jogo: sempre BigDecimal a partir de String; nunca Float/Double. */
object Money {
    private val ptBR = Locale.forLanguageTag("pt-BR")
    private val OFFER = Regex("^\\d{1,13}(\\.\\d{1,2})?$")

    fun parse(value: String?): BigDecimal = value?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    /** "1234.5" → "1.234,50". Valores acumulados são truncados (nunca arredondar para cima). */
    fun format(value: String?, decimals: Int = 2): String = format(parse(value), decimals)

    fun format(value: BigDecimal, decimals: Int = 2): String {
        val df = DecimalFormat("#,##0." + "0".repeat(decimals), DecimalFormatSymbols(ptBR))
        return df.format(value.setScale(decimals, RoundingMode.DOWN))
    }

    fun withUnit(value: String?): String = "${format(value)} moedas"

    /** Entrada do usuário ("1.234,50" ou "1234.5") → "1234.50"; null se inválida para oferta. */
    fun normalizeOffer(input: String): String? {
        var s = input.trim().replace(" ", "")
        if (s.contains(',')) s = s.replace(".", "").replace(',', '.')
        if (!OFFER.matches(s)) return null
        val v = BigDecimal(s)
        if (v.signum() <= 0) return null
        return v.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
    }

    /** Renda diária projetada = valor × taxa (exibição). */
    fun dailyIncome(price: String?, rate: String?): String =
        parse(price).multiply(parse(rate)).setScale(2, RoundingMode.DOWN).toPlainString()
}
