package com.kshow123

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object KShow123Crypto {
    // Matches the site's decodeLink(value, 8080), using OpenSSL's salted
    // AES-256-CBC format and MD5 EVP_BytesToKey derivation.
    fun decodeLink(value: String, domain: String, token: String = "8080"): String? = runCatching {
        val raw = Base64.decode(value, Base64.DEFAULT)
        require(raw.size >= 32 && raw.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "Salted__")
        val salt = raw.copyOfRange(8, 16)
        val password = (domain + "4590481877" + token).toByteArray(Charsets.UTF_8)
        var previous = byteArrayOf()
        var material = byteArrayOf()
        while (material.size < 48) {
            previous = MessageDigest.getInstance("MD5").digest(previous + password + salt)
            material += previous
        }
        decrypt(raw.copyOfRange(16, raw.size), material.copyOfRange(0, 32), material.copyOfRange(32, 48))
    }.getOrNull()

    // Vidbasic's Standard Server passes a raw key/IV to CryptoJS.AES.
    fun decodeStandard(value: String): String? = runCatching {
        decrypt(
            Base64.decode(value, Base64.DEFAULT),
            "94588293375053432799222445521289".toByteArray(Charsets.UTF_8),
            "5259228356829423".toByteArray(Charsets.UTF_8)
        )
    }.getOrNull()

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data).toString(Charsets.UTF_8).trim()
    }
}
