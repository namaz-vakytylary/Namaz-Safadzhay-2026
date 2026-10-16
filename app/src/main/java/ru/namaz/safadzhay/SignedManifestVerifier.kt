package ru.namaz.safadzhay

import android.util.Base64
import com.google.crypto.tink.subtle.Ed25519Verify

/** Prepared, deliberately not connected to the legacy updater. No production trust keys yet. */
internal class SignedManifestVerifier(private val trustedKeys: Map<String, ByteArray>) {
    data class Verified(val sequence: Int, val manifest: ByteArray)
    fun verify(envelope: ByteArray, minimumSequence: Int): Verified {
        require(envelope.size <= 256 * 1024)
        val root = RemoteJson.objectFrom(envelope)
        require(root.strictInt("schemaVersion") == 2)
        val keyId = root.strictString("keyId")
        val key = trustedKeys[keyId] ?: throw IllegalArgumentException("Unknown manifest signing key")
        val payload = decode(root.strictString("payload"), 128 * 1024)
        val signature = decode(root.strictString("signature"), 64)
        require(key.size == 32 && signature.size == 64)
        // Sign exact bytes, including the sequence inside the payload: never reserialize JSON.
        Ed25519Verify(key).verify(signature, DOMAIN + payload)
        val manifest = RemoteJson.objectFrom(payload)
        val sequence = manifest.strictInt("sequence")
        require(sequence > 0 && sequence >= minimumSequence)
        return Verified(sequence, payload)
    }
    private fun decode(value: String, limit: Int): ByteArray {
        require(value.matches(Regex("[A-Za-z0-9+/]*={0,2}")))
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size <= limit && Base64.encodeToString(bytes, Base64.NO_WRAP) == value)
        return bytes
    }
    companion object {
        val DOMAIN: ByteArray get() = "Namaz Vakytylary schedules manifest v2\u0000".toByteArray(Charsets.UTF_8)
    }
}
