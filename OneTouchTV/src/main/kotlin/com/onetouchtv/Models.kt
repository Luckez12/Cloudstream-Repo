package com.OneTouchTV

data class HomeResponse(
    val randomSlideShow: List<HomeItem>? = null,
    val recents: List<HomeItem>? = null,
    val result: HomeWrapper? = null,
)

data class HomeWrapper(
    val randomSlideShow: List<HomeItem>? = null,
    val recents: List<HomeItem>? = null,
)

data class HomeItem(
    val id: String? = null,
    val id2: String? = null,
    val title: String? = null,
    val image: String? = null,
    val country: String? = null,
    val type: String? = null,
    val year: String? = null,
    val popularity: Long? = null,
    val description: String? = null,
    val status: String? = null,
    val releaseDate: String? = null,
    val isSub: Boolean? = null,
)

data class SearchEnvelope(
    val status: Long = 0,
    val result: List<SearchItem> = emptyList(),
)

data class SearchItem(
    val id: String? = null,
    val loklokContentId: String? = null,
    val isSub: Boolean = false,
    val title: String? = null,
    val image: String? = null,
    val type: String? = null,
    val year: String? = null,
    val source: String? = null,
    val status: String? = null,
    val loklokCategory: Long? = null,
    val episodes: List<Any>? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val otherTitles: List<String>? = null,
)

data class DetailResponse(
    val title: String? = null,
    val image: String? = null,
    val poster: String? = null,
    val description: String? = null,
    val year: String? = null,
    val status: String? = null,
    val actors: List<ActorItem> = emptyList(),
    val genres: List<String> = emptyList(),
    val episodes: List<EpisodeItem> = emptyList(),
)

data class ActorItem(
    val name: String? = null,
    val image: String? = null,
)

data class EpisodeItem(
    val episode: String? = null,
    val identifier: String? = null,
    val playId: String? = null,
)

data class TopResponse(
    val day: List<TopItem>? = null,
    val week: List<TopItem>? = null,
    val month: List<TopItem>? = null,
)

data class TopItem(
    val _id: String? = null,
    val id: String? = null,
    val title: String? = null,
    val image: String? = null,
    val country: String? = null,
    val type: String? = null,
    val year: String? = null,
    val popularity: Int = 0,
    val status: String? = null,
    val releaseDate: String? = null,
    val isSub: Boolean = false,
)

data class SourceItem(
    val type: String,
    val contentId: String,
    val id: String,
    val name: String,
    val quality: String,
    val url: String,
    val headers: Map<String, String>,
)

data class TrackItem(
    val file: String,
    val name: String,
    val isDefault: Boolean,
    val kind: String,
    val format: String,
)
