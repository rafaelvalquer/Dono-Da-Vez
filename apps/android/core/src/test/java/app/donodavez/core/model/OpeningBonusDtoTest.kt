package app.donodavez.core.model

import app.donodavez.core.net.DvJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Formato do bônus de inauguração no contrato (packages/contracts/openapi.yaml, schema OpeningBonus). */
class OpeningBonusDtoTest {
    @Test fun purchaseOfferCarriesDuration() {
        val d = DvJson.decodeFromString<PropertyDetailDto>(
            """{"property":{"id":"p1"},"actions":{"purchase":{"allowed":true,"price":"1000.00","daily_income":"10.00",
               "opening_bonus":{"daily":"40.00","seconds":604800}}}}""",
        )
        assertEquals(OpeningBonusDto(daily = "40.00", seconds = 604_800), d.actions.purchase.openingBonus)
    }

    @Test fun mineCarriesEndAndNullMeansNoBonus() {
        val d = DvJson.decodeFromString<PropertyDetailDto>(
            """{"property":{"id":"p1"},"mine":{"accrued":"1.00","collectable":"1.00","opening_bonus":{"daily":"40.00","until":"2026-11-10T15:00:05Z"}}}""",
        )
        assertEquals("2026-11-10T15:00:05Z", d.mine?.openingBonus?.until)
        val ended = DvJson.decodeFromString<PropertyDetailDto>("""{"property":{"id":"p1"},"mine":{"opening_bonus":null}}""")
        assertNull(ended.mine?.openingBonus)
    }

    @Test fun portfolioTotalsDefaultToZeroOnOlderServers() {
        val p = DvJson.decodeFromString<PortfolioDto>("""{"items":[],"totals":{"count":0,"daily_income":"0.00"}}""")
        assertEquals("0.00", p.totals.openingBonusDaily)
    }
}
