package fr.bonobo.synczen.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import fr.bonobo.synczen.data.AppDatabase
import fr.bonobo.synczen.data.NasConfig
import fr.bonobo.synczen.data.NasConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import fr.bonobo.synczen.data.NasRepository
import fr.bonobo.synczen.data.NasResult
import fr.bonobo.synczen.data.SyncLogEntry
import fr.bonobo.synczen.worker.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SyncViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val workManager = WorkManager.getInstance(application)

    private val configs = NasConfigRepository(db)
    private val _saveResult = MutableStateFlow<String?>(null)
    val saveResult: StateFlow<String?> = _saveResult

    val config: StateFlow<NasConfig?> = configs.observeConfig()
        .catch { e ->
            if (e is CancellationException) throw e
            _saveResult.value = "Erreur : configuration NAS inaccessible ; données conservées."
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val recentLogs: StateFlow<List<SyncLogEntry>> = db.syncLogDao().observeRecent()
        .catch { e ->
            if (e is CancellationException) throw e
            _saveResult.value = "Erreur : configuration NAS inaccessible ; données conservées."
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult

    fun saveConfig(newConfig: NasConfig) {
        viewModelScope.launch {
            try {
                val cleaned = newConfig.copy(
                    address = newConfig.address.trim(),
                    username = newConfig.username.trim()
                )
                if (cleaned.address.isBlank()) {
                    _saveResult.value = "Adresse du serveur obligatoire"
                    return@launch
                }
                configs.upsert(cleaned)
                scheduleAutoSync(cleaned)
                _saveResult.value = "Configuration enregistrée"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _saveResult.value = "Erreur : configuration NAS inaccessible ; données conservées."
            }
        }
    }

    /** Ajoute un dossier choisi via le sélecteur système (SAF) à la config courante. */
    fun addSourceFolder(uri: String) {
        viewModelScope.launch {
            try {
                val current = configs.getConfig() ?: NasConfig()
                val updated = current.sourceFolderUriList() + uri
                configs.upsert(current.copy(sourceFolderUris = updated.joinToString("|")))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _saveResult.value = "Erreur : configuration NAS inaccessible ; données conservées."
            }
        }
    }

    fun removeSourceFolder(uri: String) {
        viewModelScope.launch {
            try {
                val current = configs.getConfig() ?: return@launch
                val updated = current.sourceFolderUriList() - uri
                configs.upsert(current.copy(sourceFolderUris = updated.joinToString("|")))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _saveResult.value = "Erreur : configuration NAS inaccessible ; données conservées."
            }
        }
    }

    fun testConnection(candidate: NasConfig) {
        viewModelScope.launch {
            if (candidate.address.isBlank()) {
                _testResult.value = "Adresse du serveur obligatoire"
                return@launch
            }
            // On passe le contexte applicatif pour forcer la connexion sur le
            // Wi-Fi actif (cf. NetworkUtils) — sans ça, le test échoue dès que
            // les données mobiles sont actives en plus du Wi-Fi.
            val result = NasRepository(candidate, getApplication()).testConnection()
            _testResult.value = when (result) {
                is NasResult.Success -> result.message
                is NasResult.Failure -> "Échec : ${result.message}"
                is NasResult.Skipped -> result.message
            }
        }
    }

    /** Bouton "Forcer maintenant" : lance une synchronisation immédiate, sans contrainte. */
    fun forceSyncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(Data.Builder().putBoolean("full_sync", true).build())
            .build()
        workManager.enqueueUniqueWork(SyncWorker.MANUAL_WORK_NAME, androidx.work.ExistingWorkPolicy.KEEP, request)
    }

    /** Planifie la synchro périodique respectant Wi-Fi (+ charge en option). */
    private fun scheduleAutoSync(config: NasConfig) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi ou autre réseau non facturé
            .setRequiresCharging(config.requireCharging)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
