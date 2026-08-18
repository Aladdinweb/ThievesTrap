package com.thievestrap

import android.content.Context
import android.util.Log
import java.util.Properties
import javax.mail.*
import javax.mail.internet.*

/**
 * EmailAlertManager v2.8.8
 * Sends security alerts and intruder photos via Gmail SMTP.
 * Uses JavaMail (android-mail + android-activation libraries).
 * Premium feature — requires email + app password configured in Settings.
 */
object EmailAlertManager {

    private const val TAG = "TT-Email"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)

    fun isConfigured(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getBoolean("email_enabled", false) &&
               p.getString("email_from", "").isNullOrBlank().not() &&
               p.getString("email_password", "").isNullOrBlank().not() &&
               p.getString("email_to", "").isNullOrBlank().not()
    }

    /** Send a plain text alert email */
    fun sendAlert(ctx: Context, subject: String, body: String) {
        if (!isConfigured(ctx)) return
        val p = prefs(ctx)
        val from     = p.getString("email_from", "") ?: return
        val password = p.getString("email_password", "") ?: return
        val to       = p.getString("email_to", "") ?: return

        Thread {
            try {
                val session = buildSession(from, password)
                val msg = MimeMessage(session).apply {
                    setFrom(InternetAddress(from))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                    setSubject("[Thieves Trap] $subject", "UTF-8")
                    setText(body, "UTF-8")
                }
                Transport.send(msg)
                Log.i(TAG, "Alert sent to $to")
            } catch (e: Exception) {
                Log.e(TAG, "sendAlert: ${e.message}")
            }
        }.start()
    }

    /** Send an alert with an attached JPEG photo */
    fun sendPhotoAlert(ctx: Context, subject: String, body: String, photoBytes: ByteArray, filename: String = "intruder.jpg") {
        if (!isConfigured(ctx)) return
        val p = prefs(ctx)
        val from     = p.getString("email_from", "") ?: return
        val password = p.getString("email_password", "") ?: return
        val to       = p.getString("email_to", "") ?: return

        Thread {
            try {
                val session = buildSession(from, password)
                val msg = MimeMessage(session).apply {
                    setFrom(InternetAddress(from))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                    setSubject("[Thieves Trap] $subject", "UTF-8")
                }

                val multipart = MimeMultipart()

                // Text part
                val textPart = MimeBodyPart().apply { setText(body, "UTF-8") }
                multipart.addBodyPart(textPart)

                // Photo attachment
                val photoPart = MimeBodyPart().apply {
                    val ds = javax.activation.ByteArrayDataSource(photoBytes, "image/jpeg")
                    dataHandler = javax.activation.DataHandler(ds)
                    fileName = filename
                }
                multipart.addBodyPart(photoPart)

                msg.setContent(multipart)
                Transport.send(msg)
                Log.i(TAG, "Photo alert sent to $to (${photoBytes.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "sendPhotoAlert: ${e.message}")
            }
        }.start()
    }

    /** Send test email to verify configuration */
    fun sendTest(ctx: Context, onResult: (Boolean, String) -> Unit) {
        val p = prefs(ctx)
        val from     = p.getString("email_from", "") ?: ""
        val password = p.getString("email_password", "") ?: ""
        val to       = p.getString("email_to", "") ?: ""

        if (from.isBlank() || password.isBlank() || to.isBlank()) {
            onResult(false, "Please fill in all email fields first.")
            return
        }

        Thread {
            try {
                val session = buildSession(from, password)
                val msg = MimeMessage(session).apply {
                    setFrom(InternetAddress(from))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                    setSubject("[Thieves Trap] Test Alert", "UTF-8")
                    setText("This is a test alert from Thieves Trap.\n\nYour email alerts are configured correctly!", "UTF-8")
                }
                Transport.send(msg)
                onResult(true, "Test email sent successfully to $to")
            } catch (e: Exception) {
                onResult(false, "Failed: ${e.message}")
            }
        }.start()
    }

    private fun buildSession(from: String, password: String): Session {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
            put("mail.smtp.ssl.trust", "smtp.gmail.com")
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
        }
        return Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(from, password)
        })
    }
}
