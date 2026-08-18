package com.kisskh

import com.lagradost.cloudstream3.base64DecodeArray
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val KEY = "AmSmZVcH93UQUezi"
private const val KEY2 = "8056483646328763"
private val IV = intArrayOf(1382367819, 1465333859, 1902406224, 1164854838)
private val IV2 = intArrayOf(909653298, 909193779, 925905208, 892483379)

fun decrypt(encryptedB64: String): String {
    if (encryptedB64.isBlank()) return ""

    val encryptedBytes = base64DecodeArray(encryptedB64.trim())
    var lastException: Exception? = null

    val pairs = listOf(
        KEY.toByteArray(Charsets.UTF_8) to IV.toByteArray(),
        KEY2.toByteArray(Charsets.UTF_8) to IV2.toByteArray()
    )

    for ((keyBytes, ivBytes) in pairs) {
        try {
            return decryptWithKeyIv(keyBytes, ivBytes, encryptedBytes)
        } catch (e: Exception) {
            lastException = e
        }
    }

    throw IOException("KissKH subtitle decryption failed", lastException)
}

private fun decryptWithKeyIv(
    keyBytes: ByteArray,
    ivBytes: ByteArray,
    encryptedBytes: ByteArray
): String {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(keyBytes, "AES"),
        IvParameterSpec(ivBytes)
    )
    return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
}

private fun IntArray.toByteArray(): ByteArray {
    return ByteArray(size * 4).also { bytes ->
        forEachIndexed { index, value ->
            bytes[index * 4] = (value shr 24).toByte()
            bytes[index * 4 + 1] = (value shr 16).toByte()
            bytes[index * 4 + 2] = (value shr 8).toByte()
            bytes[index * 4 + 3] = value.toByte()
        }
    }
}
