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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

val ARTICLE_CODES = listOf("1VMP", "1VOP", "1PL", "1PI", "1KN", "1EN")

class ScanViewModel(
    application: Application,
    private val repository: ScanRepository
) : AndroidViewModel(application) {

    val entries: StateFlow<List<ScanEntry>> = repository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _pendingBarcode = MutableStateFlow<String?>(null)
    val pendingBarcode: StateFlow<String?> = _pendingBarcode.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun onBarcodeDetected(barcode: String) {
        if (_pendingBarcode.value == null) {
            _pendingBarcode.value = barcode
        }
    }

    fun clearPending() {
        _pendingBarcode.value = null
    }

    fun confirmArticle(articleCode: String) {
        val barcode = _pendingBarcode.value ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entry = ScanEntry(
                id = IdGenerator.create(getApplication(), now),
                barcode = barcode,
                articleCode = articleCode,
                capturedAt = now
            )
            repository.insert(entry)
            _pendingBarcode.value = null
            _message.value = "$articleCode erfasst"
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.deleteAll()
            _message.value = "Liste geleert"
        }
    }

    fun shareCsv(): Boolean {
        val current = entries.value
        if (current.isEmpty()) {
            _message.value = "Keine Einträge zum Senden"
            return false
        }
        val context = getApplication<Application>()
        val file = CsvExporter.export(context, current)
        val intent = MailSender.createShareIntent(context, file)
        context.startActivity(
            android.content.Intent.createChooser(intent, "CSV per Mail senden")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
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
