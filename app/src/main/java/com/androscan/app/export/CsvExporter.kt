package com.androscan.app.export

import android.content.Context
import com.androscan.app.data.ScanEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CsvExporter {
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun export(context: Context, entries: List<ScanEntry>): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "androscan_export_$stamp.csv")
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("id,capturedAt,barcode,articleCode")
            entries.sortedBy { it.capturedAt }.forEach { entry ->
                val iso = isoFormatter.format(Date(entry.capturedAt))
                writer.appendLine(
                    listOf(
                        escape(entry.id),
                        escape(iso),
                        escape(entry.barcode),
                        escape(entry.articleCode)
                    ).joinToString(",")
                )
            }
        }
        return file
    }

    private fun escape(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
