package app.donodavez.profile

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileRulesTest {
    @Test fun nicknameMatchesServerRule() {
        assertNull(ProfileViewModel.nicknameError("dono_da.vez-1"))
        assertNotNull(ProfileViewModel.nicknameError("ab"))
        assertNotNull(ProfileViewModel.nicknameError("joão"))
        assertNotNull(ProfileViewModel.nicknameError("a".repeat(25)))
        assertNotNull(ProfileViewModel.nicknameError("com espaço"))
    }

    @Test fun notificationTextFormatsCoins() {
        val (title, body) = notificationText("property.sold", JsonObject(mapOf("price" to JsonPrimitive("1250.50"))))
        assertEquals("Imóvel vendido", title)
        assertEquals("Você recebeu 1.250,50 moedas pela venda.", body)
    }

    @Test fun unknownTypeHasFallback() = assertEquals("Aviso", notificationText("x.y", JsonObject(emptyMap())).first)
}
