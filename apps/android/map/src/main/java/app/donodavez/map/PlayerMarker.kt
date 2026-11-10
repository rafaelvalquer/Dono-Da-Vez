package app.donodavez.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Imagens desenhadas em tempo de execução para o marcador do jogador (arte original, sem assets externos).
 * Tudo em dp × densidade: o MapLibre usa a densidade do bitmap como proporção de pixels.
 */
internal object PlayerMarker {
    private val PRIMARY = Color.parseColor("#1B5E52")

    fun drawable(context: Context, @DrawableRes res: Int, sizePx: Int): Bitmap =
        ContextCompat.getDrawable(context, res)!!.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)

    /**
     * Pin em gota: cabeça redonda com o avatar, borda na cor primária, ponta e sombra no chão.
     * A ponta fica no centro vertical do bitmap, então o ícone usa âncora central sobre a posição.
     */
    fun pin(context: Context, @DrawableRes avatarRes: Int): Bitmap {
        val d = context.resources.displayMetrics.density
        val r = 22f * d
        val pad = 6f * d
        val w = 2 * (r + pad)
        val cx = w / 2
        val cy = pad + r
        val tipY = cy + 2.05f * r
        val bmp = Bitmap.createBitmap(w.toInt(), (2 * tipY).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Sombra elíptica no chão, sob a ponta.
        c.drawOval(RectF(cx - 11 * d, tipY - 4.5f * d, cx + 11 * d, tipY + 4.5f * d), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 0, 0, 0)
            maskFilter = BlurMaskFilter(2.5f * d, BlurMaskFilter.Blur.NORMAL)
        })

        val drop = teardrop(cx, cy, r, tipY)
        c.drawPath(drop, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(4f * d, 0f, 2f * d, Color.argb(70, 0, 0, 0))
        })
        c.drawPath(drop, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f * d; color = PRIMARY; strokeJoin = Paint.Join.ROUND
        })

        // Avatar recortado no círculo da cabeça.
        val ar = r - 5f * d
        c.save()
        c.clipPath(Path().apply { addCircle(cx, cy, ar, Path.Direction.CW) })
        ContextCompat.getDrawable(context, avatarRes)?.apply {
            setBounds((cx - ar).toInt(), (cy - ar).toInt(), (cx + ar).toInt(), (cy + ar).toInt())
            draw(c)
        }
        c.restore()
        // Brilho no topo da cabeça, como plástico de peça de jogo.
        c.drawArc(RectF(cx - ar + 3 * d, cy - ar + 3 * d, cx + ar - 3 * d, cy + ar - 3 * d), 200f, 50f, false,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND
                color = Color.argb(150, 255, 255, 255)
            })
        return bmp
    }

    /** Cone de direção apontando para o norte (girado pelo bearing no mapa). */
    fun heading(context: Context): Bitmap {
        val d = context.resources.displayMetrics.density
        val size = 120f * d
        val half = size / 2
        val bmp = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wedge = Path().apply {
            moveTo(half, half)
            arcTo(RectF(0f, 0f, size, size), 270f - 30f, 60f)
            close()
        }
        c.drawPath(wedge, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(half, half, half,
                intArrayOf(Color.argb(170, 27, 94, 82), Color.argb(90, 27, 94, 82), Color.argb(0, 27, 94, 82)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
        return bmp
    }

    /** Gota: arco pela cabeça e duas tangentes até a ponta. */
    private fun teardrop(cx: Float, cy: Float, r: Float, tipY: Float): Path {
        val l = tipY - cy
        val phi = Math.toDegrees(acos((r / l).toDouble())).toFloat()
        val rightAngle = Math.toRadians((90f - phi).toDouble())
        return Path().apply {
            moveTo(cx, tipY)
            lineTo(cx + r * cos(rightAngle).toFloat(), cy + r * sin(rightAngle).toFloat())
            arcTo(RectF(cx - r, cy - r, cx + r, cy + r), 90f - phi, -(360f - 2 * phi))
            close()
        }
    }
}
