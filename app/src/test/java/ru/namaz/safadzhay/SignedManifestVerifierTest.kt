package ru.namaz.safadzhay

import android.util.Base64
import com.google.crypto.tink.subtle.Ed25519Verify
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class SignedManifestVerifierTest {
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun rfc8032PublicVerificationVectorAndTamperRejection() {
        val key = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        Ed25519Verify(key).verify(signature, byteArrayOf())
        assertThrows(Exception::class.java) { Ed25519Verify(key).verify(signature, byteArrayOf(1)) }
        signature[0] = (signature[0].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { Ed25519Verify(key).verify(signature, byteArrayOf()) }
    }
    @Test fun signedSequenceUnknownKeyModifiedPayloadAndUnsignedDowngrade() {
        // Ephemeral host-JVM test key, never stored or used as an application trust anchor.
        val pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val key = pair.public.encoded.takeLast(32).toByteArray()
        val payload = "{\"sequence\":7,\"schedules\":[]}".toByteArray()
        val signer = java.security.Signature.getInstance("Ed25519")
        signer.initSign(pair.private); signer.update(SignedManifestVerifier.DOMAIN + payload)
        fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val envelope = JSONObject().put("schemaVersion", 2).put("keyId", "test-ephemeral")
            .put("payload", b64(payload)).put("signature", b64(signer.sign()))
        val verifier = SignedManifestVerifier(mapOf("test-ephemeral" to key))
        assertEquals(7, verifier.verify(envelope.toString().toByteArray(), 7).sequence)
        assertThrows(Exception::class.java) { verifier.verify(envelope.toString().toByteArray(), 8) }
        assertThrows(Exception::class.java) { SignedManifestVerifier(emptyMap()).verify(envelope.toString().toByteArray(), 0) }
        envelope.put("payload", b64("{\"sequence\":8}".toByteArray()))
        assertThrows(Exception::class.java) { verifier.verify(envelope.toString().toByteArray(), 0) }
        assertThrows(Exception::class.java) { verifier.verify("{\"schemaVersion\":1}".toByteArray(), 0) }
    }
}
