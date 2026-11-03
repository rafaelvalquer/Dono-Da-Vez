package app.donodavez.portfolio

import app.donodavez.core.model.PortfolioItemDto
import app.donodavez.core.model.PropertySummaryDto
import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioFilterTest {
    private fun item(id: String, cat: String, addr: String?, income: String, offers: Int = 0, at: String = "2026-11-0${id}T10:00:00Z") =
        PortfolioItemDto(
            ownershipId = id, acquiredAt = at, purchasePrice = "1000.00", dailyIncome = income, collectable = income,
            pendingOffers = offers, property = PropertySummaryDto(id = "p$id", category = cat, addressLabel = addr),
        )

    private val items = listOf(
        item("1", "house_small", "Rua Harmonia, 10", "10.00"),
        item("2", "commercial", "Rua dos Pinheiros, 200", "30.00", offers = 2),
        item("3", "residential_building", null, "100.00"),
    )

    @Test fun searchIgnoresAccentsAndCase() =
        assertEquals(listOf("2"), PortfolioViewModel.visible(items, "comercio", PortfolioSort.RECENT, false).map { it.ownershipId })

    @Test fun searchByAddress() =
        assertEquals(listOf("1"), PortfolioViewModel.visible(items, "HARMONIA", PortfolioSort.RECENT, false).map { it.ownershipId })

    @Test fun sortByIncome() =
        assertEquals(listOf("3", "2", "1"), PortfolioViewModel.visible(items, "", PortfolioSort.INCOME, false).map { it.ownershipId })

    @Test fun recentFirstAndOffersFilter() {
        assertEquals(listOf("3", "2", "1"), PortfolioViewModel.visible(items, "", PortfolioSort.RECENT, false).map { it.ownershipId })
        assertEquals(listOf("2"), PortfolioViewModel.visible(items, "", PortfolioSort.RECENT, true).map { it.ownershipId })
    }
}
