package cz.tapdrop.app.nfc

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import kotlinx.coroutines.flow.MutableStateFlow

sealed class WriteState {
    object Idle : WriteState()
    object Waiting : WriteState()
    object Success : WriteState()
    data class Failed(val reason: String) : WriteState()
}

/** Komunikace mezi obrazovkou "Zapsat tag" a aktivitou, která dostává NFC intenty. */
object NfcBus {
    /** Když je nenulové, příští přiložený tag se přepíše tímto URI. */
    val writeUri = MutableStateFlow<String?>(null)
    val writeState = MutableStateFlow<WriteState>(WriteState.Idle)
}

object Nfc {
    fun tagUri(notebookId: String) = "tapdrop://p?id=$notebookId"

    @Suppress("DEPRECATION")
    fun tagFrom(intent: Intent): Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)

    /** Vytáhne NOTEBOOK_ID z tagu: nejdřív z data intentu, potom ze zprávy NDEF. */
    @Suppress("DEPRECATION")
    fun readNotebookId(intent: Intent): String? {
        idFromUri(intent.data)?.let { return it }
        val msgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        msgs?.forEach { m ->
            (m as? NdefMessage)?.records?.forEach { r -> r.toUri()?.let { u -> idFromUri(u)?.let { return it } } }
        }
        val tag = tagFrom(intent) ?: return null
        val ndef = Ndef.get(tag) ?: return null
        val cached = ndef.cachedNdefMessage ?: return null
        cached.records.forEach { r -> r.toUri()?.let { u -> idFromUri(u)?.let { return it } } }
        return null
    }

    private fun idFromUri(u: Uri?): String? {
        if (u == null || u.scheme != "tapdrop" || u.host != "p") return null
        return u.getQueryParameter("id")?.takeIf { it.isNotBlank() && it.length <= 12 }
    }

    /** Zapíše NDEF URI záznam na tag. Vrací null při úspěchu, jinak důvod chyby (česky). */
    fun write(tag: Tag, uri: String): String? {
        val msg = NdefMessage(arrayOf(NdefRecord.createUri(uri)))
        return try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                try {
                    if (!ndef.isWritable) return "Tag je chráněný proti zápisu."
                    if (ndef.maxSize < msg.byteArrayLength) return "Tag je příliš malý."
                    ndef.writeNdefMessage(msg)
                } finally {
                    runCatching { ndef.close() }
                }
                null
            } else {
                val fmt = NdefFormatable.get(tag) ?: return "Tohle není NDEF tag (zkus NTAG213/215/216)."
                fmt.connect()
                try {
                    fmt.format(msg)
                } finally {
                    runCatching { fmt.close() }
                }
                null
            }
        } catch (e: Exception) {
            "Zápis se nepodařil – drž telefon u tagu klidně, dokud neskončí. (${e.message})"
        }
    }
}
