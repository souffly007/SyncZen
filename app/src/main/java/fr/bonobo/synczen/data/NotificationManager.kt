package fr.bonobo.synczen.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.bonobo.synczen.MainActivity
import fr.bonobo.synczen.R

/**
 * Gestion centralisée des notifications pour SyncZen.
 * 
 * Canal principal : SYNC_CHANNEL (pour les notifications de synchronisation)
 */
object SyncZenNotificationManager {

    private const val SYNC_CHANNEL_ID = "synczen_sync_channel"
    private const val SYNC_CHANNEL_NAME = "Synchronisation"
    private const val SYNC_CHANNEL_DESCRIPTION = "Notifications de synchronisation en cours"

    private const val SYNC_NOTIFICATION_ID = 1

    /**
     * Initialise les canaux de notification nécessaires.
     * Doit être appelé au démarrage de l'application.
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SYNC_CHANNEL_ID,
                SYNC_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = SYNC_CHANNEL_DESCRIPTION
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Crée une notification foreground pour la synchronisation.
     * Cette notification reste visible tant que la synchronisation est en cours.
     */
    fun createSyncForegroundNotification(
        context: Context,
        message: String,
        progress: Int? = null,
        max: Int? = null
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, SYNC_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SyncZen - Synchronisation")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)

        if (progress != null && max != null && max > 0) {
            builder.setProgress(max, progress, false)
        }

        return builder.build()
    }

    /**
     * Met à jour la notification foreground avec un nouveau message.
     */
    fun updateSyncNotification(
        context: Context,
        message: String,
        progress: Int? = null,
        max: Int? = null
    ) {
        val notification = createSyncForegroundNotification(context, message, progress, max)
        NotificationManagerCompat.from(context).notify(SYNC_NOTIFICATION_ID, notification)
    }

    /**
     * Supprime la notification de synchronisation.
     */
    fun cancelSyncNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(SYNC_NOTIFICATION_ID)
    }

    /**
     * Affiche une notification de succès ou d'échec après la synchronisation.
     */
    fun showSyncResultNotification(
        context: Context,
        message: String,
        isSuccess: Boolean
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val icon = if (isSuccess) android.R.drawable.ic_dialog_info else android.R.drawable.ic_dialog_alert

        val notification = NotificationCompat.Builder(context, SYNC_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("SyncZen - Résultat")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        NotificationManagerCompat.from(context).notify(
            if (isSuccess) SYNC_NOTIFICATION_ID + 1 else SYNC_NOTIFICATION_ID + 2,
            notification
        )
    }
}
