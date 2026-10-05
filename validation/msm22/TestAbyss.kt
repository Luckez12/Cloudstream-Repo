package com.msm21
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.json.JSONObject
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

fun main() = runBlocking {
    var count = 0
    suspend fun test(name: String, body: suspend () -> Unit) { body();count++;println("PASS $name") }
    val page = "https://abyss.to/?v=example"
    val signed = "https://cdn.example/master.m3u8?sig=a%2Fb%2Bz&dup=1&dup=2"
    val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=10000\nchild.m3u8?sig=a%2Fb\n"
    test("Supported hosts and rejected lookalikes") {
        listOf(page,"https://abyssplayer.com/?v=x","https://playhydrax.com/?v=x", "https://abyss.msmbot.club/#fixture").forEach { check(MsmAbyssApi.supports(it)) }
        listOf("https://abyss.to.evil/?v=x","https://evilabyss.to/?v=x", "https://abyss.msmbot.club.evil/#fixture","https://user@abyss.to/?v=x","javascript:abyss.to").forEach { check(!MsmAbyssApi.supports(it)) }
    }
    test("Both working JS datas formats") {
        check(MsmAbyssApi.datas("const datas = \"enc-payload\";") == "enc-payload")
        check(MsmAbyssApi.datas("datas: 'other'") == "other")
        check(MsmAbyssApi.datas("<html>No data</html>") == null)
    }
    test("Nested media URLs preserve signatures and deduplicate") {
        val result = JSONObject().put("sources", listOf(JSONObject().put("file",signed),signed,"https://cdn.example/a.mp4"))
        check(MsmAbyssApi.mediaUrls(result) == listOf(signed,"https://cdn.example/a.mp4"))
        check(MsmAbyssApi.mediaUrls("<source src=\"https://cdn.example/a.m3u8?sig=x&amp;k=z\">") == listOf("https://cdn.example/a.m3u8?sig=x&k=z"))
    }
    test("Reject telemetry, nonmedia strings and nonHTTP schemes") {
        check(MsmAbyssApi.mediaUrls(JSONObject("""{"x":["https://google-analytics.com/a.m3u8","javascript:video.mp4","https://cdn.example/config.json"]}""")).isEmpty())
    }
    test("Decrypt request JSON and playback headers match working JS") {
        app.calls.clear()
        app.handler = { call -> Reply(call.url,200,if(call.json == null) "const datas = \"payload\";" else JSONObject().put("status",200).put("result",JSONObject().put("url",signed)).toString()) }
        val links = MsmAbyssApi.extract(page, "Abyss MalaySub 9")
        check(links.size == 1 && links[0].url == signed && links[0].type == ExtractorLinkType.M3U8)
        check(links[0].name == "Abyss MalaySub 9" && links[0].source == "Abyss MalaySub 9")
        check(links[0].referer == page && links[0].headers["Referer"] == page)
        check(app.calls[0].headers["Origin"] == "https://abyss.to")
        check(app.calls[0].headers["Referer"] == "https://abyss.to/")
        check(app.calls[1].json == mapOf("text" to "payload"))
        check(app.calls.all { it.timeout == 3L })
    }
    test("Missing datas and failed decrypt produce zero candidates") {
        app.calls.clear();app.handler = { Reply(it.url,200,"<html>missing</html>") }
        check(MsmAbyssApi.extract(page).isEmpty() && app.calls.size == 1)
        app.handler = { Reply(it.url,200,if(it.json == null) "datas='enc'" else "{\"status\":500,\"result\":{\"url\":\"$signed\"}}") }
        check(MsmAbyssApi.extract(page).isEmpty())
        app.handler = { Reply(it.url,403,"Denied") };check(MsmAbyssApi.extract(page).isEmpty())
    }
    test("Cancellation propagates to the provider deadline") {
        app.handler = { throw CancellationException("fixture") }
        try { MsmAbyssApi.extract(page);error("Cancellation swallowed") } catch (_: CancellationException) {}
    }
    test("Common policy rejects bad/unknown manifests and retains master") {
        app.handler = { call -> Reply(call.url, if(call.url.contains("unknown")) 500 else 200,
            if(call.url.contains("master")) master else "<html>denied</html>") }
        val links = listOf("bad","unknown","master").map { ExtractorLink("Abyss","Abyss","https://cdn.example/$it.m3u8",ExtractorLinkType.M3U8,referer=page) }
        val selected = MsmMediaPolicy.select(links,"Abyss MalaySub",requireVerified=true).take(1)
        check(selected.size == 1 && selected[0].url.contains("master"))
        check(selected[0].referer == page)
        check(MsmMediaPolicy.select(links.take(2),"Abyss",requireVerified=true).isEmpty())
        check(MsmMediaPolicy.select(links.take(2),"Other",requireVerified=false).size == 1) // original unverified fallback unchanged
    }
    test("Common direct-video probe filters the bad candidate") {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange ->
            val good = exchange.requestURI.path == "/good.mp4"
            check(exchange.requestHeaders.getFirst("Range") == "bytes=0-511")
            val body = if(good) ByteArray(512).apply { "ftyp".toByteArray().copyInto(this,4) } else "<html>not video</html>".toByteArray()
            exchange.sendResponseHeaders(206,body.size.toLong());exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val links = listOf("bad","good").map { ExtractorLink("Abyss","Abyss","http://127.0.0.1:${server.address.port}/$it.mp4",ExtractorLinkType.VIDEO,referer=page) }
            val selected = MsmMediaPolicy.select(links,"Abyss",requireVerified=true).take(1)
            check(selected.size == 1 && selected[0].url.endsWith("good.mp4"))
        } finally { server.stop(0) }
    }
    test("Direct video reuses verified redirect and preserves signed query and metadata") {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val base = "http://127.0.0.1:${server.address.port}"
        val target = "$base/final.mp4?sig=a%2Fb%2Bz&dup=1&dup=2"
        server.createContext("/start.mp4") { exchange ->
            exchange.responseHeaders.add("Location", target)
            exchange.sendResponseHeaders(302,-1); exchange.close()
        }
        server.createContext("/final.mp4") { exchange ->
            check(exchange.requestURI.rawQuery == "sig=a%2Fb%2Bz&dup=1&dup=2")
            check(exchange.requestHeaders.getFirst("Referer") == page)
            val body = ByteArray(512).apply { "ftyp".toByteArray().copyInto(this,4) }
            exchange.responseHeaders.add("Content-Range", "bytes 0-511/1000000")
            exchange.sendResponseHeaders(206,512); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val link = ExtractorLink("Abyss", "Abyss", "$base/start.mp4", ExtractorLinkType.VIDEO,
                referer=page, headers=mapOf("User-Agent" to "fixture"), quality=720,
                extractorData="fixture", audioTracks=listOf("Malay"))
            val selected = MsmMediaPolicy.select(listOf(link), "AbyssMalay Dub 3").single()
            check(selected.url == target && selected.referer == page && selected.headers == link.headers)
            check(selected.quality == 720 && selected.extractorData == "fixture" && selected.audioTracks == link.audioTracks)
        } finally { server.stop(0) }
    }
    test("Cross-origin redirect does not move credential headers onto emitted URL") {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/start.mp4") { exchange ->
            exchange.responseHeaders.add("Location", "http://localhost:${server.address.port}/final.mp4")
            exchange.sendResponseHeaders(302,-1); exchange.close()
        }
        server.createContext("/final.mp4") { exchange ->
            val body = ByteArray(512).apply { "ftyp".toByteArray().copyInto(this,4) }
            exchange.sendResponseHeaders(206,512); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val link = ExtractorLink("Host", "Host", "$base/start.mp4", ExtractorLinkType.VIDEO,
                headers=mapOf("Cookie" to "fixture=1"))
            check(MsmMediaPolicy.select(listOf(link),"Host").single().url == link.url)
        } finally { server.stop(0) }
    }
    test("Playmate and known labels use consistent names") {
        check(MsmServerLabels.display("playmMalaySub 10") == "Playmate • MalaySub")
        check(MsmServerLabels.linkName("playmMalaySub 10", "Playmate", false, 400) == "Playmate • MalaySub")
        check(MsmServerLabels.display("abyssMalaySub 2") == "Abyss • MalaySub")
    }
    test("A fast verified master does not wait for a stalled candidate") {
        app.handler = { call ->
            if (call.url.contains("slow")) delay(2000)
            Reply(call.url,200,master)
        }
        val links = listOf("slow", "fast").map { ExtractorLink("Seek","Seek","https://cdn.example/$it.m3u8",ExtractorLinkType.M3U8) }
        val start = System.nanoTime()
        val selected = MsmMediaPolicy.select(links,"seekpMalaySub 7")
        check(selected.single().url.contains("fast"))
        check((System.nanoTime()-start)/1_000_000 < 1000)
    }
    test("DNS failure is rejected even in optional unverified mode") {
        app.handler = { throw java.net.UnknownHostException("fixture") }
        val link = ExtractorLink("Larhu","Larhu","https://cdn.example/a.m3u8",ExtractorLinkType.M3U8)
        check(MsmMediaPolicy.select(listOf(link),"larhuMalaySub",requireVerified=false).isEmpty())
    }
    test("Malay Dub and arbitrary new servers share the formatter") {
        check(MsmServerLabels.display("rpmplMalay Dub 3") == "RPM • Malay Dub")
        check(MsmServerLabels.display("seekpMalay Dub 4") == "Seek • Malay Dub")
        check(MsmServerLabels.display("p2pstMalay Dub 5") == "P2P • Malay Dub")
        check(MsmServerLabels.display("upnsMalay Dub 6") == "Upns • Malay Dub")
        check(MsmServerLabels.display("playmMalay Dub 10") == "Playmate • Malay Dub")
        check(MsmServerLabels.display("NewServerMalay Dub 12", "NewHost") == "NewHost • Malay Dub")
        check(MsmServerLabels.display("FutureHostMalaySub 13") == "FutureHost • MalaySub")
        check(MsmServerLabels.display("futureMalay Dub 14", "PlayerX", "Auto") == "future • Malay Dub")
        check(MsmServerLabels.display("NewServerEnglish Dub 8", "NewHost") == "NewHost • English Dub")
        check(MsmServerLabels.display("FutureHost_English_Sub_9") == "FutureHost • English Sub")
    }
    test("Names retain meaningful numbers and resolution without duplicate extractor") {
        check(MsmServerLabels.display("Host2Malay Dub 7") == "Host2 • Malay Dub")
        check(MsmServerLabels.display("Host2 7") == "Host2 7")
        check(MsmServerLabels.linkName("futureMalay Dub 8", "NewHost 720p", false, 720, "NewHost") == "NewHost • Malay Dub • 720p")
        check(MsmServerLabels.linkName("futureMalaySub 8", "NewHost 720p", true, 720, "NewHost") == "NewHost • MalaySub")
    }
    test("Selected new server carries extractor identity and audio into both fields") {
        app.handler = { call -> Reply(call.url,200,master) }
        val link = ExtractorLink("Future Extractor", "Future Extractor Auto", "https://cdn.example/master.m3u8", ExtractorLinkType.M3U8)
        val selected = MsmMediaPolicy.select(listOf(link), "brandnewMalay Dub 42").single()
        check(selected.source == "Future Extractor • Malay Dub")
        check(selected.name == selected.source)
    }
    println("$count Abyss Kotlin regression cases passed")
}
