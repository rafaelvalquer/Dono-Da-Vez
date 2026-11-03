package app.donodavez.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.donodavez.MainActivity
import app.donodavez.R

/** Canais (Android 8+) e exibição de notificações recebidas com o app aberto. */
object Notifications {
    const val EXTRA_ROUTE = "dv_route"

    private val channels = listOf(
        Triple("proposals", "Propostas", "Propostas recebidas, respostas e prazos"),
        Triple("transfers", "Vendas e carteira", "Vendas, compras sem aceite e ajustes"),
        Triple("support", "Suporte", "Atualizações dos seus chamados"),
    )

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        channels.forEach { (id, name, description) ->
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply { this.description = description })
        }
    }

    /** Tela aberta ao tocar: propostas, carteira ou central de notificações. */
    fun routeFor(type: String?): String = when {
        type == null -> "notifications"
        type.startsWith("proposal.") || type == "risk.review_decided" -> "proposals"
        type.startsWith("property.") || type.startsWith("wallet.") -> "wallet"
        else -> "notifications"
    }

    fun show(context: Context, type: String?, title: String, body: String, channel: String?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_ROUTE, routeFor(type))
        val pending = PendingIntent.getActivity(context, (type ?: "").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel?.takeIf { c -> channels.any { it.first == c } } ?: "proposals")
            .setSmallIcon(R.drawable.ic_stat_dv)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification) }
    }
}
