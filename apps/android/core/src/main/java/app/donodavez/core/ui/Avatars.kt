package app.donodavez.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.donodavez.core.R

/** Avatares originais (assets/avatars, gerados por assets/tools/generate-assets.mjs). */
object Avatars {
    /** Selecionáveis pelo jogador; avatar_00 é reservado a contas removidas. */
    val selectable = (1..8).map { "avatar_%02d".format(it) }

    @DrawableRes
    fun drawable(id: String?): Int = when (id) {
        "avatar_01" -> R.drawable.dv_avatar_01
        "avatar_02" -> R.drawable.dv_avatar_02
        "avatar_03" -> R.drawable.dv_avatar_03
        "avatar_04" -> R.drawable.dv_avatar_04
        "avatar_05" -> R.drawable.dv_avatar_05
        "avatar_06" -> R.drawable.dv_avatar_06
        "avatar_07" -> R.drawable.dv_avatar_07
        "avatar_08" -> R.drawable.dv_avatar_08
        else -> R.drawable.dv_avatar_00
    }

    @DrawableRes
    fun category(code: String?): Int = when (code) {
        "house_small" -> R.drawable.dv_category_house_small
        "house_large" -> R.drawable.dv_category_house_large
        "commercial" -> R.drawable.dv_category_commercial
        "residential_building" -> R.drawable.dv_category_residential_building
        else -> R.drawable.dv_category_pending
    }
}

@Composable
fun AvatarImage(id: String?, modifier: Modifier = Modifier, size: Dp = 40.dp, description: String? = null) {
    Image(painterResource(Avatars.drawable(id)), contentDescription = description, modifier = modifier.size(size))
}
