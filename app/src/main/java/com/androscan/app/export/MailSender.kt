package com.androscan.app.export

import com.androscan.app.BuildConfig
import jakarta.activation.DataHandler
import jakarta.activation.FileDataSource
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.io.File
import java.util.Properties

object MailSender {
    fun sendCsv(csvFile: File, entryCount: Int) {
        require(BuildConfig.SMTP_HOST.isNotBlank()) { "SMTP host not configured" }
        require(BuildConfig.SMTP_USER.isNotBlank()) { "SMTP user not configured" }
        require(BuildConfig.SMTP_PASSWORD.isNotBlank()) { "SMTP password not configured" }
        require(BuildConfig.SMTP_TO.isNotBlank()) { "SMTP recipient not configured" }

        val port = BuildConfig.SMTP_PORT.toIntOrNull() ?: 587
        val props = Properties().apply {
            put("mail.smtp.host", BuildConfig.SMTP_HOST)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
            put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.writetimeout", "30000")
        }

        val session = Session.getInstance(
            props,
            object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(
                        BuildConfig.SMTP_USER,
                        BuildConfig.SMTP_PASSWORD
                    )
                }
            }
        )

        val from = BuildConfig.SMTP_FROM.ifBlank { BuildConfig.SMTP_USER }
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(from))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(BuildConfig.SMTP_TO, false))
            subject = "Androscan Export ($entryCount Einträge)"
        }

        val textPart = MimeBodyPart().apply {
            setText(
                "Androscan CSV-Export mit $entryCount Einträgen im Anhang.",
                "UTF-8"
            )
        }
        val attachmentPart = MimeBodyPart().apply {
            dataHandler = DataHandler(FileDataSource(csvFile))
            fileName = csvFile.name
        }

        message.setContent(
            MimeMultipart().apply {
                addBodyPart(textPart)
                addBodyPart(attachmentPart)
            }
        )

        Transport.send(message)
    }
}
