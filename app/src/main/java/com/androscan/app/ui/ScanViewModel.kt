package com.androscan.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.androscan.app.data.AppDatabase
import com.androscan.app.data.ScanEntry
import com.androscan.app.data.ScanRepository
import com.androscan.app.export.CsvExporter
import com.androscan.app.export.MailSender
import com.androscan.app.util.IdGenerator
import com.androscan.app.util.ScanFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

val ARTICLE_CODES = listOf("1VMP", "1VOP", "1PL", "1PI", "1KN", "1EN")

private val ENTRY_TTL_MS = TimeUnit.HOURS.toMillis(48)
private val CLEANUP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15)
private const val POST_SAVE_COOLDOWN_MS = 2000L

class ScanViewModel(
    application: Application,
    private val repository: ScanRepository
) : AndroidViewModel(application) {

    val entries: StateFlow<List<ScanEntry>> = repository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _pendingBarcode = MutableStateFlow<String?>(null)
    val pendingBarcode: StateFlow<String?> = _pendingBarcode.asStateFlow()

    private val _scanReady = MutableStateFlow(true)
    val scanReady: StateFlow<Boolean> = _scanReady.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                purgeExpiredEntries()
                delay(CLEANUP_INTERVAL_MS)
            }
        }
    }

    fun onBarcodeDetected(barcode: String) {
        if (_pendingBarcode.value == null && _scanReady.value) {
            _pendingBarcode.value = barcode
        }
    }

    fun onScanError(message: String) {
        if (_pendingBarcode.value == null && _scanReady.value) {
            _message.value = message
        }
    }

    fun clearPending() {
        _pendingBarcode.value = null
    }

    fun confirmArticle(articleCode: String) {
        val barcode = _pendingBarcode.value ?: return
        if (!hasNonNumericCountryPrefix(barcode)) {
            _pendingBarcode.value = null
            _scanReady.value = false
            _message.value = "Ländercode fehlt (erste 2 Zeichen müssen Buchstaben sein)"
            viewModelScope.launch {
                delay(POST_SAVE_COOLDOWN_MS)
                _scanReady.value = true
            }
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entry = ScanEntry(
                id = IdGenerator.create(getApplication(), now),
                barcode = barcode,
                articleCode = articleCode,
                capturedAt = now,
                sentByMail = false
            )
            repository.insert(entry)
            _pendingBarcode.value = null
            _scanReady.value = false
            ScanFeedback.doublePeep()
            ScanFeedback.vibrateDouble(getApplication())
            _message.value = "$articleCode erfasst"
            delay(POST_SAVE_COOLDOWN_MS)
            _scanReady.value = true
        }
    }

    /** Eartag must start with a 2-letter country code (e.g. AT, DE), not digits. */
    private fun hasNonNumericCountryPrefix(barcode: String): Boolean {
        if (barcode.length < 2) return false
        return !barcode[0].isDigit() && !barcode[1].isDigit()
    }

    fun clearMessage() {
        _message.value = null
    }

    fun sendMail() {
        val current = entries.value
        if (current.isEmpty()) {
            _message.value = "Keine Einträge zum Senden"
            return
        }
        if (_isSending.value) return

        viewModelScope.launch {
            _isSending.value = true
            _message.value = "Sende Mail per SMTP…"
            try {
                val context = getApplication<Application>()
                val file = withContext(Dispatchers.IO) {
                    CsvExporter.export(context, current)
                }
                withContext(Dispatchers.IO) {
                    MailSender.sendCsv(file, current.size)
                }
                repository.markAllSentByMail()
                _message.value = "Mail erfolgreich gesendet"
            } catch (e: Exception) {
                _message.value = "Mail fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _isSending.value = false
            }
        }
    }

    private suspend fun purgeExpiredEntries() {
        val cutoff = System.currentTimeMillis() - ENTRY_TTL_MS
        repository.deleteOlderThan(cutoff)
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory {
            val db = AppDatabase.getInstance(application)
            val repo = ScanRepository(db.scanDao())
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ScanViewModel(application, repo) as T
                }
            }
        }
    }
}
