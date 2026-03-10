package com.arcle.intelligence.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log

/**
 * Message Manager — handles AUTO_MSG intents.
 * Sends SMS and WhatsApp messages with confirmation read-back.
 */
class MessageManager(private val context: Context) {

    companion object {
        private const val TAG = "MessageManager"
    }

    data class PendingMessage(
        val contact: String,
        val message: String,
        val isWhatsApp: Boolean = false
    )

    var pendingMessage: PendingMessage? = null

    fun prepareMessage(contact: String, message: String, viaWhatsApp: Boolean = false): String {
        pendingMessage = PendingMessage(contact, message, viaWhatsApp)
        return "I'll send $contact: \"$message\". Shall I confirm, Sir?"
    }

    fun confirmAndSend(): String {
        val pending = pendingMessage ?: return "There's no message to send, Sir."
        pendingMessage = null

        return if (pending.isWhatsApp) {
            sendWhatsAppMessage(pending.contact, pending.message)
        } else {
            sendSms(pending.contact, pending.message)
        }
    }

    fun cancelMessage(): String {
        pendingMessage = null
        return "Message cancelled, Sir."
    }

    @Suppress("DEPRECATION")
    private fun sendSms(contact: String, message: String): String {
        return try {
            val callManager = CallManager(context)
            val phoneNumber = lookupContactNumber(contact)
            if (phoneNumber != null) {
                val smsManager = SmsManager.getDefault()
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                "Message sent to $contact, Sir."
            } else {
                "I couldn't find $contact's number, Sir."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS", e)
            "I couldn't send that message, Sir."
        }
    }

    private fun sendWhatsAppMessage(contact: String, message: String): String {
        return try {
            val phoneNumber = lookupContactNumber(contact)
            if (phoneNumber != null) {
                val uri = Uri.parse("https://wa.me/$phoneNumber?text=${Uri.encode(message)}")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.setPackage("com.whatsapp")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Opening WhatsApp to send the message, Sir."
            } else {
                "I couldn't find $contact's number for WhatsApp, Sir."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending WhatsApp message", e)
            "I couldn't open WhatsApp, Sir."
        }
    }

    private fun lookupContactNumber(name: String): String? {
        try {
            val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
            val selection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$name%")
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up contact", e)
        }
        return null
    }
}
