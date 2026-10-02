package ru.namaz.safadzhay

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

class SignedManifestVerifierTest {
    // Public RFC 8032 TEST 1 fixture; never a production key or signing credential.
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val publicKey = hex("302a300506032b6570032100d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val seed = hex("302e020100300506032b6570042204209d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private fun sign(bytes: ByteArray, domain: Boolean = true): ByteArray {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(seed)))
        if (domain) signer.update(SignedManifestVerifier.DOMAIN)
        signer.update(bytes)
        return signer.sign()
    }
    @Test fun authenticBytesVerifyAndTamperingFailsClosed() {
        val bytes = "{\"schemaVersion\":2}".toByteArray()
        val signature = sign(bytes)
        val verifier = SignedManifestVerifier(publicKey)
        assertTrue(verifier.verify(bytes, signature))
        assertFalse(verifier.verify(bytes + 32.toByte(), signature))
        assertFalse(verifier.verify(bytes, signature.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }))
        assertFalse(verifier.verify(bytes, ByteArray(0)))
        assertFalse(verifier.verify(ByteArray(128 * 1024 + 1), signature))
        assertFalse(verifier.verify(bytes, sign(bytes, domain = false)))
        assertFalse(SignedManifestVerifier(ByteArray(44)).verify(bytes, signature))
        assertFalse(SignedManifestVerifier(publicKey.copyOf().apply { this[20] = 0 }).verify(bytes, signature))
    }
}
