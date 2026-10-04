package fr.bonobo.synczen.worker

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import fr.bonobo.synczen.data.AppDatabase
import fr.bonobo.synczen.data.NasRepository
import fr.bonobo.synczen.data.NasResult
import fr.bonobo.synczen.data.SyncLogEntry
import fr.bonobo.synczen.data.SyncStatus

/**
 * Worker déclenché par WorkManager (contraintes réseau/charge posées lors de
 * l'enqueue dans SyncViewModel). Parcourt les arbres de dossiers choisis par
 * l'utilisateur via le sélecteur système (SAF) et envoie de façon
 * incrémentielle les fichiers modifiés depuis la dernière synchronisation.
 */
class SyncWorker(private val appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(appContext)
        val config = db.nasConfigDao().getConfig() ?: return Result.failure()

        if (config.address.isBlank()) {
            db.syncLogDao().insert(
                SyncLogEntry(
                    timestamp = System.currentTimeMillis(),
                    status = SyncStatus.FAILURE,
                    message = "Adresse du serveur non enregistrée"
                )
            )
            return Result.failure()
        }

        if (config.sourceFolderUriList().isEmpty()) {
            db.syncLogDao().insert(
                SyncLogEntry(
                    timestamp = System.currentTimeMillis(),
                    status = SyncStatus.FAILURE,
                    message = "Aucun dossier source configuré"
                )
            )
            return Result.failure()
        }

        // On passe appContext pour forcer la connexion sur le Wi-Fi actif
        // (cf. NetworkUtils) — sans ça, la synchro échoue dès que les données
        // mobiles sont actives en plus du Wi-Fi sur le téléphone.
        val repository = NasRepository(config, appContext)
        // Le bouton manuel demande une sauvegarde complète pour permettre
        // de réessayer proprement après une ancienne version défectueuse.
        val since = if (inputData.getBoolean("full_sync", false)) 0L else config.lastSyncTimestamp

        val newFiles = config.sourceFolderUriList()
            .mapNotNull { uriString -> DocumentFile.fromTreeUri(appContext, Uri.parse(uriString)) }
            .flatMap { root -> collectFiles(root, since) }

        if (newFiles.isEmpty()) {
            db.syncLogDao().insert(
                SyncLogEntry(
                    timestamp = System.currentTimeMillis(),
                    status = SyncStatus.FAILURE,
                    message = "Aucun fichier trouvé. Vérifiez le dossier source et ses permissions.",
                    fileCount = 0
                )
            )
            return Result.failure()
        }

        var successCount = 0
        var lastError: String? = null

        for (doc in newFiles) {
            val name = doc.name ?: continue
            val stream = appContext.contentResolver.openInputStream(doc.uri)
            if (stream == null) {
                lastError = "Impossible de lire ${name}"
                continue
            }
            when (val result = repository.uploadFile(name, stream)) {
                is NasResult.Success -> successCount++
                is NasResult.Failure -> lastError = result.message
            }
        }

        val status = if (lastError == null) SyncStatus.SUCCESS else SyncStatus.FAILURE
        val now = System.currentTimeMillis()
        val message = if (lastError == null) {
            "$successCount fichier(s) synchronisé(s)"
        } else {
            "Terminé avec erreurs : $lastError"
        }

        db.syncLogDao().insert(
            SyncLogEntry(timestamp = now, status = status, message = message, fileCount = successCount)
        )

        // On ne valide la date de synchro qu'après une opération complète.
        // En cas d'erreur, les fichiers pourront être retentés au prochain passage.
        if (lastError == null) db.nasConfigDao().updateLastSync(now)

        return if (lastError == null) Result.success() else Result.retry()
    }

    /** Parcourt récursivement un arbre SAF et renvoie les fichiers modifiés après [since]. */
    private fun collectFiles(dir: DocumentFile, since: Long): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        for (child in dir.listFiles()) {
            when {
                child.isDirectory -> result += collectFiles(child, since)
                child.isFile && (since <= 0L || child.lastModified() <= 0L || child.lastModified() > since) -> result += child
            }
        }
        return result
    }

    companion object {
        const val WORK_NAME = "synczen_sync_work"
    }
}
