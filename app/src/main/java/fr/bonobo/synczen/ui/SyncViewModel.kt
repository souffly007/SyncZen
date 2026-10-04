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

    val config: StateFlow<NasConfig?> = db.nasConfigDao().observeConfig()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val recentLogs: StateFlow<List<SyncLogEntry>> = db.syncLogDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult

    private val _saveResult = MutableStateFlow<String?>(null)
    val saveResult: StateFlow<String?> = _saveResult

    fun saveConfig(newConfig: NasConfig) {
        viewModelScope.launch {
            val cleaned = newConfig.copy(
                address = newConfig.address.trim(),
                username = newConfig.username.trim()
            )
            if (cleaned.address.isBlank()) {
                _saveResult.value = "Adresse du serveur obligatoire"
                return@launch
            }
            db.nasConfigDao().upsert(cleaned)
            scheduleAutoSync(cleaned)
            _saveResult.value = "Configuration enregistrée"
        }
    }

    /** Ajoute un dossier choisi via le sélecteur système (SAF) à la config courante. */
    fun addSourceFolder(uri: String) {
        viewModelScope.launch {
            val current = db.nasConfigDao().getConfig() ?: NasConfig()
            val updated = current.sourceFolderUriList() + uri
            db.nasConfigDao().upsert(current.copy(sourceFolderUris = updated.joinToString("|")))
        }
    }

    fun removeSourceFolder(uri: String) {
        viewModelScope.launch {
            val current = db.nasConfigDao().getConfig() ?: return@launch
            val updated = current.sourceFolderUriList() - uri
            db.nasConfigDao().upsert(current.copy(sourceFolderUris = updated.joinToString("|")))
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
            }
        }
    }

    /** Bouton "Forcer maintenant" : lance une synchronisation immédiate, sans contrainte. */
    fun forceSyncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(Data.Builder().putBoolean("full_sync", true).build())
            .build()
        workManager.enqueue(request)
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
