package com.sistermesh.app.communication

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

interface MeshSigner {
    fun identity(deviceId: String): DeviceIdentity
    fun sign(bytes: ByteArray): String
    fun verify(publicKeyBase64: String, bytes: ByteArray, signatureBase64: String): Boolean
}

class AndroidKeystoreMeshSigner : MeshSigner {
    private val alias = "sistermesh.mesh.identity.v1"

    override fun identity(deviceId: String): DeviceIdentity {
        val publicKey = Base64.encodeToString(keyPair().public.encoded, Base64.NO_WRAP)
        return DeviceIdentity(
            deviceId = deviceId,
            publicKeyBase64 = publicKey,
            identitySignature = sign(MeshCanonical.identity(deviceId, publicKey))
        )
    }

    override fun sign(bytes: ByteArray): String {
        val keyStore = keyStore()
        val privateKey = keyStore.getKey(alias, null)
            ?: throw IllegalStateException("Mesh signing key is unavailable.")
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey as java.security.PrivateKey)
        signer.update(bytes)
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }

    override fun verify(publicKeyBase64: String, bytes: ByteArray, signatureBase64: String): Boolean =
        runCatching {
            val keyBytes = Base64.decode(publicKeyBase64, Base64.DEFAULT)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(bytes)
            verifier.verify(Base64.decode(signatureBase64, Base64.DEFAULT))
        }.getOrDefault(false)

    private fun keyPair(): KeyPair {
        val store = keyStore()
        val existing = store.getCertificate(alias)
        if (existing != null) {
            val privateKey = store.getKey(alias, null) as? java.security.PrivateKey
                ?: throw IllegalStateException("Mesh signing private key is unavailable.")
            return KeyPair(existing.publicKey, privateKey)
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKeyPair()
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
