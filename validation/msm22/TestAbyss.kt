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
    suspend fun stalledVideoRace(stallBody: Boolean, cancelOnly: Boolean = false) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val handlerPool = java.util.concurrent.Executors.newCachedThreadPool()
        server.executor = handlerPool
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val exited = java.util.concurrent.CountDownLatch(1)
        val good = ByteArray(512).apply { "ftyp".toByteArray().copyInto(this,4) }
        server.createContext("/slow.mp4") { exchange ->
            try {
                if (stallBody) {
                    exchange.sendResponseHeaders(206,512)
                    exchange.responseBody.write(good,0,8); exchange.responseBody.flush()
                }
                entered.countDown()
                release.await(2,java.util.concurrent.TimeUnit.SECONDS)
                if (!stallBody) exchange.sendResponseHeaders(206,512)
                exchange.responseBody.use { it.write(good,if(stallBody) 8 else 0,if(stallBody) 504 else 512) }
            } catch (_: Exception) { exchange.close() }
            finally { exited.countDown() }
        }
        server.createContext("/fast.mp4") { exchange ->
            check(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
            exchange.sendResponseHeaders(206,512);exchange.responseBody.use { it.write(good) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val links = (if(cancelOnly) listOf("slow") else listOf("slow","fast")).map {
                ExtractorLink("Abyss","Abyss","$base/$it.mp4",ExtractorLinkType.VIDEO)
            }
            val start = System.nanoTime()
            val result = withTimeoutOrNull(if(cancelOnly) 200L else 900L) {
                MsmMediaPolicy.select(links,"AbyssMalaySub")
            }
            val elapsed = (System.nanoTime()-start)/1_000_000
            if (cancelOnly) { check(result == null);check(elapsed < 800) { "Cancellation blocked $elapsed ms" } }
            else {
                check(result?.single()?.url == "$base/fast.mp4") { "Verified source lost to cleanup: $result ($elapsed ms)" }
                check(elapsed < 800) { "Winner waited for losing socket $elapsed ms" }
            }
            check(entered.count == 0L)
        } finally {
            release.countDown();exited.await(1,java.util.concurrent.TimeUnit.SECONDS)
            server.stop(0);handlerPool.shutdownNow()
        }
    }
    test("Verified direct winner survives a stalled response-header loser and outer budget") {
        stalledVideoRace(stallBody=false)
    }
    test("Verified direct winner survives a stalled response-body loser and outer budget") {
        stalledVideoRace(stallBody=true)
    }
    test("Parent cancellation returns promptly while direct-video socket is blocked") {
        stalledVideoRace(stallBody=true,cancelOnly=true)
    }
    test("Website names are preserved without legacy server aliases") {
        check(MsmServerLabels.display("playmMalaySub 10") == "playm • MalaySub")
        check(MsmServerLabels.display("rpmplMalay Dub 3") == "rpmpl • Malay Dub")
        check(MsmServerLabels.display("seekpMalaySub 4") == "seekp • MalaySub")
        check(MsmServerLabels.display("playmMalaySub 10", "Playmate") == "Playmate • MalaySub")
        check(MsmServerLabels.linkName("playmMalaySub 10", "Auto", true, 400) == "playm • MalaySub • Auto")
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
    test("Arbitrary servers and languages share the formatter without a name table") {
        check(MsmServerLabels.display("p2pstMalay Dub 5") == "p2pst • Malay Dub")
        check(MsmServerLabels.display("upnsMalay Dub 6") == "upns • Malay Dub")
        check(MsmServerLabels.display("NewServerMalay Dub 12", "NewHost") == "NewHost • Malay Dub")
        check(MsmServerLabels.display("FutureHostMalaySub 13") == "FutureHost • MalaySub")
        check(MsmServerLabels.display("futureMalay Dub 14", "futureMalay Dub 14", "Auto") == "future • Malay Dub")
        check(MsmServerLabels.display("NewServerEnglish Dub 8", "NewHost") == "NewHost • English Dub")
        check(MsmServerLabels.display("FutureHost_English_Sub_9") == "FutureHost • English Sub")
    }
    test("Names retain meaningful numbers and resolution without duplicate extractor") {
        check(MsmServerLabels.display("Host2Malay Dub 7") == "Host2 • Malay Dub")
        check(MsmServerLabels.display("Host2 7") == "Host2 7")
        check(MsmServerLabels.linkName("futureMalay Dub 8", "NewHost 720p", false, 720, "NewHost") == "NewHost • Malay Dub • 720p")
        check(MsmServerLabels.linkName("futureMalaySub 8", "NewHost 720p", true, 720, "NewHost") == "NewHost • MalaySub • Auto")
    }
    test("Selected new server carries extractor identity and audio into both fields") {
        app.handler = { call -> Reply(call.url,200,master) }
        val link = ExtractorLink("Future Extractor", "Future Extractor Auto", "https://cdn.example/master.m3u8", ExtractorLinkType.M3U8)
        val selected = MsmMediaPolicy.select(listOf(link), "brandnewMalay Dub 42").single()
        check(selected.source == "Future Extractor • Malay Dub • Auto")
        check(selected.name == selected.source)
    }
    test("Resolution suffix distinguishes verified master, rendition and unknown") {
        check(MsmServerLabels.linkName("HostMalaySub 4", "Host 1080p", true,1080,"Host") == "Host • MalaySub • Auto")
        check(MsmServerLabels.linkName("HostMalay Dub 4", "Host 720p", false,400,"Host") == "Host • Malay Dub • 720p")
        check(MsmServerLabels.linkName("HostMalaySub 4", "Host Auto", false,1080,"Host") == "Host • MalaySub • 1080p")
        check(MsmServerLabels.linkName("HostMalaySub 4", "Auto", false,400) == "Host • MalaySub • Unknown")
        check(MsmServerLabels.linkName("HostMalaySub 4", "Auto", false,0) == "Host • MalaySub • Unknown")
        check(MsmServerLabels.linkName("Host720MalaySub 4", "Host720", false,400) == "Host720 • MalaySub • Unknown")
    }
    test("Already formatted labels retain one suffix and support no-language sources") {
        val label = "NewHost • Malay Dub • 720p"
        check(MsmServerLabels.linkName(label,label,false,720,label) == label)
        check(MsmServerLabels.linkName(label,label,true,720,label) == "NewHost • Malay Dub • Auto")
        check(MsmServerLabels.linkName("Host2 7","Auto",false,400) == "Host2 7 • Unknown")
        check(MsmServerLabels.linkName("","",false,400) == "Unknown • Unknown")
    }
    test("An unverified fallback is Unknown, never Auto merely from its old label") {
        app.handler = { call -> Reply(call.url,500,"busy") }
        val link = ExtractorLink("Host","Host Auto","https://cdn.example/a.m3u8",ExtractorLinkType.M3U8)
        val result = MsmMediaPolicy.select(listOf(link),"HostMalaySub",requireVerified=false).single()
        check(result.source == "Host • MalaySub • Unknown" && result.name == result.source)
    }
    test("Player API uses website server identity instead of a hardcoded display name") {
        val data = JSONObject().put("source","/hls/master.m3u8").toString()
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec("kiemtienmua911ca".toByteArray(),"AES"),
            javax.crypto.spec.IvParameterSpec("1234567890oiuytr".toByteArray()))
        val hex = cipher.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        app.handler = { call -> Reply(call.url,200,hex) }
        val links = MsmPlayerApi.extract("https://playerx.rpmplay.online/#fixture",page,"FutureHostMalaySub 2")
        check(links.isNotEmpty() && links.all { it.source == "FutureHostMalaySub 2" && it.name == it.source })
    }
    println("$count Abyss Kotlin regression cases passed")
}
