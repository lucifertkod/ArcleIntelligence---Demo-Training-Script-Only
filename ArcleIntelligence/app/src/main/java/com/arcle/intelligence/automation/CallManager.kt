package com.arcle.intelligence.automation

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

/**
 * Call Manager — handles AUTO_CALL intents.
 * Looks up contacts and initiates phone calls.
 */
class CallManager(private val context: Context) {

    companion object {
        private const val TAG = "CallManager"
    }

    fun makeCall(contactName: String): String {
        return try {
            val phoneNumber = lookupContactNumber(contactName)
            if (phoneNumber != null) {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
                callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(callIntent)
                "Calling $contactName now, Sir."
            } else {
                "I couldn't find $contactName in your contacts, Sir."
            }
        } catch (e: SecurityException) {
            "I don't have permission to make calls, Sir."
        } catch (e: Exception) {
            Log.e(TAG, "Error making call", e)
            "I couldn't make that call, Sir."
        }
    }

    private fun lookupContactNumber(name: String): String? {
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$name%")

            val cursor: Cursor? = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up contact", e)
        }
        return null
    }
}
