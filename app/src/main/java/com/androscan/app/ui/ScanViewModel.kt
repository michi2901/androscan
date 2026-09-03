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
import com.androscan.app.util.EartagCheckDigit
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

val COLD_ROOMS = listOf("E-13", "E-14", "NB-E-15", "NB-E16", "HÄLFTEN")
val ARTICLE_CODES = listOf("1VMP", "1VOP", "1PL", "1PI", "1KN", "1EN", "1H")

private val ENTRY_TTL_MS = TimeUnit.HOURS.toMillis(48)
private val CLEANUP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15)
private const val POST_SAVE_COOLDOWN_MS = 2000L

class ScanViewModel(
    application: Application,
    private val repository: ScanRepository
) : AndroidViewModel(application) {

    val entries: StateFlow<List<ScanEntry>> = repository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedColdRoom = MutableStateFlow<String?>(null)
    val selectedColdRoom: StateFlow<String?> = _selectedColdRoom.asStateFlow()

    private val _selectedArticle = MutableStateFlow<String?>(null)
    val selectedArticle: StateFlow<String?> = _selectedArticle.asStateFlow()

    private val _scanReady = MutableStateFlow(true)
    val scanReady: StateFlow<Boolean> = _scanReady.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val canScan: Boolean
        get() = _selectedColdRoom.value != null && _selectedArticle.value != null

    init {
        viewModelScope.launch {
            while (isActive) {
                purgeExpiredEntries()
                delay(CLEANUP_INTERVAL_MS)
            }
        }
    }

    fun selectColdRoom(coldRoom: String) {
        _selectedColdRoom.value = coldRoom
    }

    fun selectArticle(articleCode: String) {
        _selectedArticle.value = articleCode
    }

    fun onBarcodeDetected(barcode: String) {
        if (!_scanReady.value || !canScan) return
        saveScan(barcode)
    }

    fun onScanError(message: String) {
        if (_scanReady.value && canScan) {
            _message.value = message
        }
    }

    fun submitManualBarcode(raw: String) {
        if (!_scanReady.value || !canScan) {
            _message.value = "Bitte zuerst Kühlraum und Artikel wählen"
            return
        }
        val payload = prepareBarcodePayload(raw)
        if (payload.isBlank()) {
            _message.value = "Leere Ohrmarke"
            return
        }
        if (!hasAtLeastTwoNonNumericChars(payload)) {
            _message.value = "Ungültige Ohrmarke (Ländercode fehlt)"
            return
        }
        when (val result = EartagCheckDigit.validate(payload)) {
            is EartagCheckDigit.ValidationResult.Valid -> saveScan(payload)
            is EartagCheckDigit.ValidationResult.InvalidLength,
            is EartagCheckDigit.ValidationResult.InvalidCheckDigit -> {
                _message.value = result.errorMessage ?: "Ungültige Ohrmarke"
            }
            is EartagCheckDigit.ValidationResult.Unsupported -> {
                _message.value = "Ohrmarke nicht erkannt"
            }
        }
    }

    private fun saveScan(barcode: String) {
        val article = _selectedArticle.value ?: return
        val coldRoom = _selectedColdRoom.value ?: return
        if (!hasNonNumericCountryPrefix(barcode)) {
            _scanReady.value = false
            _message.value = "Ländercode fehlt (erste 2 Zeichen müssen Buchstaben sein)"
            viewModelScope.launch {
                delay(POST_SAVE_COOLDOWN_MS)
                _scanReady.value = true
            }
            return
        }
        viewModelScope.launch {
            _scanReady.value = false
            val now = System.currentTimeMillis()
            val entry = ScanEntry(
                id = IdGenerator.create(getApplication(), now),
                barcode = barcode,
                articleCode = article,
                coldRoom = coldRoom,
                capturedAt = now,
                sentByMail = false
            )
            repository.insert(entry)
            ScanFeedback.doublePeep()
            ScanFeedback.vibrateDouble(getApplication())
            _message.value = "$article / $coldRoom erfasst"
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
