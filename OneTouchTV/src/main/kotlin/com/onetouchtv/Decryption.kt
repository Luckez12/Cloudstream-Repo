package com.OneTouchTV

import com.lagradost.cloudstream3.base64DecodeArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val AES_KEY = "im72charPasswordofdInitVectorStm".toByteArray(Charsets.UTF_8)
private val AES_IV = "im72charPassword".toByteArray(Charsets.UTF_8)

private fun normalizeCustomAlphabet(input: String): String {
    return input
        .replace("-_.", "/")
        .replace("@", "+")
        .replace(Regex("\\s+"), "")
}

private fun base64ToBytes(input: String): ByteArray {
    val normalized = normalizeCustomAlphabet(input)
    val remainder = normalized.length % 4
    val padded = if (remainder == 0) {
        normalized
    } else {
        normalized + "=".repeat(4 - remainder)
    }
    return base64DecodeArray(padded)
}

private fun decryptAes256Cbc(ciphertext: ByteArray): ByteArray {
    require(ciphertext.size % 16 == 0) {
        "Encrypted payload is not aligned to the AES block size"
    }

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(AES_KEY, "AES"),
        IvParameterSpec(AES_IV),
    )
    return cipher.doFinal(ciphertext)
}

fun decryptString(encrypted: String): String {
    val decrypted = decryptAes256Cbc(base64ToBytes(encrypted))
        .toString(Charsets.UTF_8)
    return JSONObject(decrypted).getString("result")
}
