package fr.bonobo.synczen.worker

import android.app.Notification
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import fr.bonobo.synczen.R
import fr.bonobo.synczen.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Worker responsable de la synchronisation des fichiers vers le NAS/serveur.
 * 
 * Fonctionnalités :
 * - Synchronisation automatique ou manuelle via WorkManager
 * - Notifications foreground avec progression
 * - Filtrage par SSID Wi-Fi (si configuré)
 * - Verrouillage pour éviter les synchronisations simultanées
 * - Gestion des erreurs avec journalisation
 * 
 * @param appContext Contexte de l'application
 * @param params Paramètres du Worker (non utilisés actuellement)
 * @see [SafeFilePublisher] pour la logique de publication des fichiers
 * @see [SyncZenNotificationManager] pour les notifications
 */
class SyncWorker(private val appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private var notification: Notification? = null
    private var currentFileCount = 0
    private var totalFileCount = 0
    private var publishedCount = 0
    private var skippedCount = 0

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(appContext)
        
        // Vérifier si le SSID correspond (si configuré)
        val config = NasConfigRepository(db).getConfig()
        if (config != null && config.wifiSsid != null) {
            val currentSsid = getCurrentSsid(appContext)
            if (currentSsid != null && currentSsid != config.wifiSsid) {
                db.syncLogDao().insert(SyncLogEntry(
                    timestamp = System.currentTimeMillis(),
                    status = SyncStatus.FAILURE,
                    message = "SSID ne correspond pas: attendu ${config.wifiSsid}, actuel $currentSsid"
                ))
                return@withContext Result.failure()
            }
        }
        
        val gate = SyncExecutionGate.acquire(File(appContext.filesDir, "synczen-sync.lock"))
        if (gate == null) {
            db.syncLogDao().insert(SyncLogEntry(
                timestamp = System.currentTimeMillis(),
                status = SyncStatus.SUCCESS,
                message = "Synchronisation déjà en cours ; aucune seconde opération lancée."
            ))
            return@withContext Result.success()
        }
        
        // Passer en foreground avec notification
        setForeground(createForegroundInfo(appContext, appContext.getString(R.string.notification_sync_in_progress)))
        
        gate.use {
            try {
                runLocked(db)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    try {
                        db.syncLogDao().insert(SyncLogEntry(
                            timestamp = System.currentTimeMillis(),
                            status = SyncStatus.FAILURE,
                            message = "Synchronisation interrompue ; publications complètes conservées, temporaires non publiés conservés."
                        ))
                    } catch (_: Exception) { /* Never mask cancellation with a journal failure. */ }
                    SyncZenNotificationManager.cancelSyncNotification(appContext)
                }
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SyncZen", "Synchronisation arrêtée ; configuration et fichiers existants conservés.", e)
                try {
                    db.syncLogDao().insert(SyncLogEntry(
                        timestamp = System.currentTimeMillis(),
                        status = SyncStatus.FAILURE,
                        message = "Synchronisation arrêtée ; configuration et fichiers existants conservés: ${e.message}"
                    ))
                } catch (_: Exception) { /* Database/Keystore failure must not trigger a reset. */ }
                SyncZenNotificationManager.cancelSyncNotification(appContext)
                Result.failure()
            }
        }
    }

    /**
     * Crée une ForegroundInfo pour le Worker.
     */
    private fun createForegroundInfo(context: Context, message: String): ForegroundInfo {
        notification = SyncZenNotificationManager.createSyncForegroundNotification(context, message)
        return ForegroundInfo(NOTIFICATION_ID, notification!!)
    }

    /**
     * Met à jour la notification foreground.
     */
    private suspend fun updateForegroundNotification(context: Context, message: String) {
        notification = SyncZenNotificationManager.createSyncForegroundNotification(
            context,
            message,
            if (totalFileCount > 0) publishedCount + skippedCount else null,
            if (totalFileCount > 0) totalFileCount else null
        )
        setForeground(ForegroundInfo(NOTIFICATION_ID, notification!!))
    }

    private suspend fun runLocked(db: AppDatabase): Result {
        val started = System.currentTimeMillis()
        val config = NasConfigRepository(db).getConfig() ?: return Result.failure()
        var lastError: String? = null
        val candidates = mutableListOf<SourceFileCandidate>()
        
        if (config.address.isBlank() || config.sourceFolderUriList().isEmpty()) {
            lastError = "Adresse du serveur ou dossier source manquant"
            updateForegroundNotification(appContext, lastError)
        }
        
        // Scanner les dossiers sources
        for (source in config.sourceFolderUriList().distinct()) {
            currentCoroutineContext().ensureActive()
            try {
                val root = DocumentFile.fromTreeUri(appContext, Uri.parse(source))
                    ?: throw SyncConflict("Dossier SAF introuvable")
                candidates.addAll(SourceTreeScanner.scan(source, root))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = "Dossier source refusé ou inaccessible ; chemins invalides non publiés."
            }
        }
        
        totalFileCount = candidates.size
        updateForegroundNotification(appContext, "Prêt à synchroniser $totalFileCount fichier(s)...")
        
        val collisions = RemotePath.collisions(candidates.map { it.path })
        if (collisions.isNotEmpty()) {
            lastError = "Conflit de noms, de casse ou Unicode : chemins ambigus non publiés."
        }
        
        val repository = NasRepository(config, appContext)
        val destination = RemotePath.sha256("${config.serverType.name}|${config.address.trim()}")
        
        // Lazy: invalid source trees and collisions alone do not cause network operations.
        val publisher by lazy { SafeFilePublisher(destination, repository.safeTransport(), RoomPublicationJournal(db.syncFileDao()), config.conflictResolutionStrategy) }
        val staging = LocalStaging(appContext.cacheDir)
        staging.removeOrphans() // The shared lock excludes every other active transfer.
        
        publishedCount = 0
        skippedCount = 0
        
        for (candidate in candidates) {
            if (candidate.path in collisions) continue
            currentCoroutineContext().ensureActive()
            
            currentFileCount++
            updateForegroundNotification(appContext, "Synchronisation: $publishedCount/$totalFileCount fichiers...")
            
            var file: File? = null
            try {
                val expectedSize = candidate.document.length().takeIf { it > 0 }
                val snapshot = staging.create(expectedSize)
                file = snapshot
                val modified = candidate.document.lastModified()
                val input = appContext.contentResolver.openInputStream(candidate.document.uri)
                    ?: throw SyncConflict("Lecture source impossible")
                input.useCancellable { source -> snapshot.outputStream().use { output ->
                    copyBounded(source, output)
                } }
                val after = candidate.document.lastModified()
                if (modified > 0 && after > 0 && modified != after) {
                    throw SyncConflict("Source modifiée pendant la lecture ; aucun fichier publié")
                }
                when (val result = publisher.publish(candidate.path, snapshot, expectedSize)) {
                    is NasResult.Success -> publishedCount++
                    is NasResult.Skipped -> skippedCount++
                    is NasResult.Failure -> lastError = result.message
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = if (e is WebDavSecurityException || e is SyncConflict) e.message
                    else "Lecture ou publication interrompue : ${candidate.path.value} ; aucun écrasement."
            } finally { file?.delete() } // Only our local cache file. No remote cleanup.
        }
        
        val message = if (lastError == null) "$publishedCount fichier(s) publié(s), $skippedCount déjà publié(s) et vérifié(s)"
            else "Terminé avec erreurs : $lastError ($publishedCount publié(s), $skippedCount déjà vérifié(s))"
        
        db.syncLogDao().insert(SyncLogEntry(
            timestamp = System.currentTimeMillis(),
            status = if (lastError == null) SyncStatus.SUCCESS else SyncStatus.FAILURE,
            message = message,
            fileCount = publishedCount
        ))
        
        if (lastError == null) {
            db.nasConfigDao().updateLastSync(started)
            SyncZenNotificationManager.showSyncResultNotification(
                appContext,
                message,
                true
            )
        } else {
            SyncZenNotificationManager.showSyncResultNotification(
                appContext,
                "Erreur: $lastError",
                false
            )
        }
        
        SyncZenNotificationManager.cancelSyncNotification(appContext)
        
        // Conflicts must not trigger an endless sequence of automatic retry uploads.
        return if (lastError == null) Result.success() else Result.failure()
    }

    /**
     * Récupère le SSID du Wi-Fi actuel.
     * Nécessite la permission ACCESS_FINE_LOCATION sur Android 8+.
     */
    private fun getCurrentSsid(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        
        val wifiInfo = wifiManager.connectionInfo ?: return null
        
        // Sur Android 10+, le SSID peut être nul sans permission de localisation
        return try {
            wifiInfo.ssid?.replace("\"", "")
        } catch (e: SecurityException) {
            // Permission de localisation non accordée
            null
        }
    }

    companion object {
        const val WORK_NAME = "synczen_sync_work"
        const val MANUAL_WORK_NAME = "synczen_manual_sync_work"
        private const val NOTIFICATION_ID = 1
    }
}
