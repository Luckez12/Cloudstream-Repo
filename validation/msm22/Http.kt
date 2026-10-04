package com.lagradost.cloudstream3
const val USER_AGENT = "fixture-agent"
data class Reply(val url: String, val code: Int, val text: String)
data class Call(val url: String, val headers: Map<String,String>, val referer: String?, val json: Any?, val timeout: Long)
object app {
    val calls = mutableListOf<Call>()
    var handler: suspend (Call) -> Reply = { error("No handler") }
    suspend fun get(url: String, headers: Map<String,String> = emptyMap(), referer: String? = null, timeout: Long = 30): Reply {
        val call = Call(url, headers, referer, null, timeout); calls.add(call);return handler(call)
    }
    suspend fun post(url: String, headers: Map<String,String> = emptyMap(), json: Any? = null, timeout: Long = 30): Reply {
        val call = Call(url, headers, null, json, timeout); calls.add(call);return handler(call)
    }
}
