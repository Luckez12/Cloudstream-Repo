package com.animextv

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.net.URLEncoder

// The public Megaplay JavaScript uses zero-padded AES-256-CBC and a short-lived
// CDN token. These mirror its normal player request, not a media DRM scheme.
internal object AnimeXTVCrypto {
    private const val KEY = "i?LMTAx0Q6,:}50U"
    private const val IV = "W0;27ToaUpl_P%'c"
    private const val TOKEN_KEY = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"
    fun decrypt(enc: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.toByteArray().copyOf(32), "AES"), IvParameterSpec(IV.toByteArray().copyOf(16)))
        return String(cipher.doFinal(Base64.decode(enc, Base64.URL_SAFE)), Charsets.UTF_8)
    }
    fun signed(url: String, seconds: Long = System.currentTimeMillis() / 1000): String {
        if (Regex("[?&]token=").containsMatchIn(url)) return url
        val match = Regex("/([a-f0-9]{32})/([a-f0-9]{32})/", RegexOption.IGNORE_CASE).find(url) ?: return url
        val path = "${match.groupValues[1].lowercase()}/${match.groupValues[2].lowercase()}"
        val data = "${seconds + 90}|$path".toByteArray()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TOKEN_KEY.toByteArray(), "HmacSHA256"))
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        val token = Base64.encodeToString(data, flags) + "." + Base64.encodeToString(mac.doFinal(data), flags)
        return url + (if ('?' in url) "&" else "?") + "token=" + URLEncoder.encode(token, "UTF-8")
    }
}
