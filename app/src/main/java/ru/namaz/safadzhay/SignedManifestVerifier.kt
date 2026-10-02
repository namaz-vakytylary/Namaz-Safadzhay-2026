package ru.namaz.safadzhay

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Migration building block only: intentionally NOT wired to the current unsigned feed.
 * Platform Ed25519 is available on Android API 33+. Missing provider fails closed;
 * deployment to API 23..32 requires a separately reviewed compatible provider first.
 * publicKey must come from the APK's pinned trust set, never from remote JSON.
 */
internal class SignedManifestVerifier(publicKey: ByteArray) {
    private val pinnedPublicKey = publicKey.copyOf()
    fun verify(manifestBytes: ByteArray, detachedSignature: ByteArray): Boolean = runCatching {
        require(manifestBytes.size in 1..128 * 1024 && detachedSignature.size == 64)
        require(pinnedPublicKey.size == 44 && pinnedPublicKey.copyOfRange(0, 12).contentEquals(SPKI_PREFIX))
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(pinnedPublicKey))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(key)
        verifier.update(DOMAIN)
        verifier.update(manifestBytes)
        verifier.verify(detachedSignature)
    }.getOrDefault(false)

    companion object {
        internal val DOMAIN = "namaz-update-manifest-v2\u0000".toByteArray(Charsets.US_ASCII)
        private val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    }
}
