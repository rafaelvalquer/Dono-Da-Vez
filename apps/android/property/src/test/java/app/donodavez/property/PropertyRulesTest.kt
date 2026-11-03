package app.donodavez.property

import app.donodavez.core.model.OwnershipDto
import app.donodavez.core.model.PropertyDetailDto
import app.donodavez.core.model.PropertyInfoDto
import app.donodavez.core.model.ProposalDto
import app.donodavez.core.ui.PropertyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PropertyRulesTest {
    private val base = PropertyDetailDto(property = PropertyInfoDto(id = "p1"))
    private fun owner(mine: Boolean) = OwnershipDto(ownershipId = "o1", isMine = mine, purchasePrice = "1000.00")

    @Test fun availableWithoutOwner() = assertEquals(PropertyState.AVAILABLE, PropertyRules.viewerState(base))

    @Test fun mineWinsOverEverything() =
        assertEquals(PropertyState.MINE, PropertyRules.viewerState(base.copy(ownership = owner(true), property = base.property.copy(eligibility = "suspended"))))

    @Test fun pendingProposalIsPurple() =
        assertEquals(PropertyState.PROPOSAL, PropertyRules.viewerState(base.copy(ownership = owner(false), myProposal = ProposalDto(id = "x", amount = "10", status = "pending"))))

    @Test fun otherOwner() = assertEquals(PropertyState.OTHER, PropertyRules.viewerState(base.copy(ownership = owner(false))))

    @Test fun ineligibleIsGray() =
        assertEquals(PropertyState.UNAVAILABLE, PropertyRules.viewerState(base.copy(property = base.property.copy(eligibility = "under_review"))))

    @Test fun offerValidation() {
        assertNull(PropertyRules.offerError("1.234,50", "2000.00"))
        assertNotNull(PropertyRules.offerError("0", "2000.00"))
        assertNotNull(PropertyRules.offerError("10.123", "2000.00"))
        assertNotNull(PropertyRules.offerError("2000.01", "2000.00"))
        assertNull(PropertyRules.offerError("2000", "2000.00"))
    }
}
