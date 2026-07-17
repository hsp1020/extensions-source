package eu.kanade.tachiyomi.extension.ko.navercomic

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

@Serializable
class ApiMangaSearchResponse(
    private val pageInfo: PageInfo,
    private val searchList: List<Manga>,
) {
    val hasNextPage: Boolean get() = pageInfo.nextPage != 0
    fun toSMangas(mType: String): List<SManga> = searchList.map { it.toSManga(mType) }
}

@Serializable
class ApiMangaChallengeResponse(
    val pageInfo: PageInfo? = null,
    private val list: List<MangaChallenge>,
) {
    fun toSMangas(mType: String): List<SManga> = list.map { it.toSManga(mType) }
}

@Serializable
class ApiMangaChapterListResponse(
    val pageInfo: PageInfo,
    val titleId: Int,
    val articleList: List<MangaChapter>,
) {
    val hasNextPage: Boolean get() = pageInfo.nextPage != 0
}

@Serializable
class PageInfo(
    val nextPage: Int,
)

@Serializable
class MangaChapter(
    val serviceDateDescription: String,
    private val subtitle: String,
    private val no: Int,
) {
    fun toSChapter(mType: String, titleId: Int) = SChapter.create().apply {
        url = "/$mType/detail?titleId=$titleId&no=$no"
        name = subtitle
        chapter_number = no.toFloat()
    }
}

@Serializable
class Manga(
    private val thumbnailUrl: String? = null,
    private val posterThumbnailUrl: String? = null, // 요일/장르 API 등에서 넘어올 수 있는 썸네일 추가 대응
    private val titleName: String,
    private val titleId: Int,
    private val finished: Boolean = false,
    private val rest: Boolean = false,
    private val communityArtists: List<Author> = emptyList(), // 검색 API에서 넘어오는 작가 리스트
    private val synopsis: String = "",
    private val author: String? = null, // 요일/장르 API에서 넘어오는 단일 작가 문자열
) {
    fun toSManga(mType: String) = SManga.create().apply {
        title = titleName
        description = synopsis
        thumbnail_url = thumbnailUrl ?: posterThumbnailUrl ?: ""
        url = "/$mType/list?titleId=$titleId"
        
        // 검색 API인지, 목록 API인지에 따라 작가명이 들어오는 필드가 다르므로 분기 처리
        this.author = this@Manga.author ?: communityArtists.joinToString { it.name }
        
        status = when {
            rest -> SManga.ON_HIATUS
            finished -> SManga.COMPLETED
            else -> SManga.ONGOING
        }
    }
}

@Serializable
class MangaChallenge(
    private val thumbnailUrl: String,
    private val titleName: String,
    private val titleId: Int,
) {
    fun toSManga(mType: String) = SManga.create().apply {
        title = titleName
        thumbnail_url = thumbnailUrl
        url = "/$mType/list?titleId=$titleId"
    }
}

@Serializable
class Author(
    val name: String,
)
