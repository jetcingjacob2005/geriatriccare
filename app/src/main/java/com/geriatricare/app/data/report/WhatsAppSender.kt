package com.geriatricare.app.data.report

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.io.File

/**
 * Sends the generated PDF health report straight to WhatsApp for the
 * patient's bystander (the emergency contact saved on the patient profile),
 * instead of the general Android share sheet.
 *
 * How it works (public WhatsApp/Android APIs only — there is no official
 * API to silently push a file into a specific chat without user action):
 *  1. Opens the bystander's WhatsApp chat directly via a wa.me deep link
 *     built from their saved phone number, with a pre-filled text message.
 *  2. Immediately follows up with a share intent for the PDF targeted
 *     directly at the WhatsApp app (skipping the generic share sheet) —
 *     WhatsApp opens straight to its own chat picker, and the bystander's
 *     chat (just opened in step 1) is the top/most-recent entry, so
 *     attaching it is effectively one tap — never a manually-typed number.
 */
object WhatsAppSender {

    private const val WHATSAPP_PACKAGE = "com.whatsapp"

    /** Normalizes an Indian mobile number to the digits-only form wa.me expects.
     *  Returns null if the number doesn't look like a real phone number at all —
     *  callers should surface that as an error instead of opening a broken chat. */
    private fun normalizePhone(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        val withCountryCode = when {
            digits.length == 10 -> "91$digits"                                  // bare 10-digit Indian number
            digits.startsWith("0") && digits.length == 11 -> "91${digits.drop(1)}" // leading trunk 0
            else -> digits                                                       // assume it already has a country code
        }
        // A real number with country code is 11-15 digits (India: 91 + 10 = 12).
        return withCountryCode.takeIf { it.length in 11..15 }
    }

    fun isWhatsAppInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(WHATSAPP_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun Intent.withTaskFlagIfNeeded(context: Context): Intent {
        // Starting an activity from a non-Activity context (e.g. a ViewModel
        // using the application context for an automatic send) requires this flag.
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return this
    }

    /** Opens a WhatsApp chat for any phone number with a pre-filled message — used both
     *  for the bystander report flow and for messaging a doctor's hospital directly. */
    fun openChat(context: Context, phone: String, message: String) {
        if (phone.isBlank()) {
            Toast.makeText(context, "No phone number available for WhatsApp.", Toast.LENGTH_LONG).show()
            return
        }
        val normalized = normalizePhone(phone)
        if (normalized == null) {
            Toast.makeText(context, "\"$phone\" doesn't look like a valid phone number.", Toast.LENGTH_LONG).show()
            return
        }
        if (!isWhatsAppInstalled(context)) {
            Toast.makeText(context, "WhatsApp isn't installed on this device.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/$normalized?text=${Uri.encode(message)}")
                setPackage(WHATSAPP_PACKAGE)
            }.withTaskFlagIfNeeded(context)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Couldn't open WhatsApp. Is it installed and up to date?", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Opens the bystander's WhatsApp chat with a pre-filled summary message,
     * then hands the PDF report to WhatsApp so it can be attached in the
     * same chat. Safe to call automatically (e.g. right after a scan
     * finishes) — it silently no-ops with a clear log/toast if the number
     * is missing or invalid, rather than opening a broken chat.
     */
    fun sendReportToBystander(
        context: Context,
        bystanderName: String,
        bystanderPhone: String,
        patientName: String,
        reportFile: File,
        showToast: Boolean = true
    ) {
        if (bystanderPhone.isBlank()) {
            if (showToast) Toast.makeText(context, "No bystander phone number saved for this patient. Add it in Edit Patient.", Toast.LENGTH_LONG).show()
            return
        }
        val phone = normalizePhone(bystanderPhone)
        if (phone == null) {
            if (showToast) Toast.makeText(context, "The saved bystander number (\"$bystanderPhone\") isn't valid. Fix it in Edit Patient.", Toast.LENGTH_LONG).show()
            return
        }
        if (!isWhatsAppInstalled(context)) {
            if (showToast) Toast.makeText(context, "WhatsApp isn't installed on this device.", Toast.LENGTH_LONG).show()
            return
        }

        val who = bystanderName.ifBlank { "the bystander" }
        val message = "JeevaMitra health report for $patientName is attached below."

        try {
            // Step 1 — open the exact chat for this phone number. Since the
            // number is pre-validated and put straight into the wa.me link,
            // there is nothing for the user to type in manually here.
            val chatIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/$phone?text=${Uri.encode(message)}")
                setPackage(WHATSAPP_PACKAGE)
            }.withTaskFlagIfNeeded(context)
            context.startActivity(chatIntent)

            // Step 2 — hand the PDF to WhatsApp directly (not the full Android
            // share sheet), so the same chat — already open, top of the list —
            // is one tap away to attach it.
            val fileUri = ReportGenerator.shareUri(context, reportFile)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_TEXT, message)
                setPackage(WHATSAPP_PACKAGE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.withTaskFlagIfNeeded(context)
            context.startActivity(sendIntent)

            if (showToast) Toast.makeText(context, "Select $who's chat in WhatsApp to attach the report.", Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            if (showToast) Toast.makeText(context, "Couldn't open WhatsApp. Is it installed and up to date?", Toast.LENGTH_LONG).show()
        }
    }
}
