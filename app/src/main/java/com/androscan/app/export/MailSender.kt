package com.androscan.app.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object MailSender {
    fun createShareIntent(context: Context, csvFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            csvFile
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Androscan Export")
            putExtra(Intent.EXTRA_TEXT, "Androscan CSV-Export im Anhang.")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
