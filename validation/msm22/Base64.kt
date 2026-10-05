package android.util
object Base64 {
    const val URL_SAFE = 8
    const val NO_WRAP = 2
    fun decode(value: String, flags: Int): ByteArray =
        if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder().decode(value)
        else java.util.Base64.getDecoder().decode(value)
}
