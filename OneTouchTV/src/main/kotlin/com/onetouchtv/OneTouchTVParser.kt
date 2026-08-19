package com.OneTouchTV

import org.json.JSONObject

fun parseSourcesAndTracks(json: String): Pair<List<SourceItem>, List<TrackItem>> {
    val root = JSONObject(json)
    val payload = root.optJSONObject("result") ?: root

    val sources = buildList {
        val array = payload.optJSONArray("sources") ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val headersObject = item.optJSONObject("headers")
            val headers = buildMap {
                if (headersObject != null) {
                    val keys = headersObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, headersObject.optString(key, ""))
                    }
                }
            }

            add(
                SourceItem(
                    type = item.optString("type", ""),
                    contentId = item.optString("contentId", ""),
                    id = item.optString("id", ""),
                    name = item.optString("name", ""),
                    quality = item.optString("quality", ""),
                    url = item.optString("url", ""),
                    headers = headers,
                ),
            )
        }
    }

    val tracks = buildList {
        val array = payload.optJSONArray("track")
            ?: payload.optJSONArray("tracks")
            ?: return@buildList

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                TrackItem(
                    file = item.optString("file", ""),
                    name = item.optString("name", ""),
                    isDefault = item.optBoolean("default", false),
                    kind = item.optString("kind", ""),
                    format = item.optString("format", ""),
                ),
            )
        }
    }

    return sources to tracks
}
