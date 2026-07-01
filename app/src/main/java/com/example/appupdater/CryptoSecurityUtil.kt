package com.example.appupdater

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoSecurityUtil {

    private const val AES_KEY_SIZE = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val ALGORITHM = "AES/GCM/NoPadding"

    /**
     * Generates a secure, cryptographically random AES-256 key.
     */
    fun generateAES256Key(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(AES_KEY_SIZE, SecureRandom())
        return keyGen.generateKey()
    }

    /**
     * Encrypts a plaintext message using AES-256-GCM with a random IV.
     * The IV is prepended to the ciphertext and Base64-encoded for easy transmission.
     */
    fun encrypt(plaintext: String, secretKey: SecretKey): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val cipherText = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        
        // Combine IV and cipher text
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypts a Base64 combined payload of prepended IV and ciphertext using AES-256-GCM.
     */
    fun decrypt(combinedBase64: String, secretKey: SecretKey): String {
        val combined = Base64.decode(combinedBase64, Base64.NO_WRAP)
        if (combined.size < GCM_IV_LENGTH) {
            throw IllegalArgumentException("Invalid encrypted payload size")
        }
        
        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
        
        val cipherTextSize = combined.size - GCM_IV_LENGTH
        val cipherText = ByteArray(cipherTextSize)
        System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherTextSize)
        
        val cipher = Cipher.getInstance(ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        
        val decryptedBytes = cipher.doFinal(cipherText)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}
