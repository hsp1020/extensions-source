package eu.kanade.tachiyomi.extension.ko.navercomic

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

@Serializable
class ApiMangaSearchResponse(
    val pageInfo: PageInfo,
    val searchList: List<Manga>,
) {
    val hasNextPage: Boolean get() = pageInfo.nextPage != 0
    fun toSMangas(mType: String): List<SManga> = searchList.map { it.toSManga(mType) }
}

@Serializable
class ApiMangaChallengeResponse(
    val pageInfo: PageInfo? = null,
    val list: List<MangaChallenge>,
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

// 연령, 장르, 태그 파싱을 위한 보조 클래스들
@Serializable
class AgeInfo(val description: String)

@Serializable
class GenreInfo(val description: String)

@Serializable
class CurationTag(val tagName: String)

@Serializable
class Manga(
    private val thumbnailUrl: String? = null,
    private val posterThumbnailUrl: String? = null,
    private val titleName: String,
    private val titleId: Int,
    private val finished: Boolean = false,
    private val rest: Boolean = false,
    private val communityArtists: List<Author> = emptyList(),
    private val synopsis: String = "",
    private val author: String? = null,
    val starScore: Double = 0.0,
    val viewCount: Long = 0L,
    
    // 상세 페이지 태그 표시용 변수들
    private val publishDescription: String? = null, // [추가] "월요웹툰", "완결웹툰" 등
    private val age: AgeInfo? = null,
    private val genres: List<GenreInfo> = emptyList(),
    private val curationTagList: List<CurationTag> = emptyList(),
    private val challengeTagList: List<String> = emptyList(),
) {
    fun toSManga(mType: String) = SManga.create().apply {
        title = titleName
        description = synopsis
        thumbnail_url = thumbnailUrl ?: posterThumbnailUrl ?: ""
        url = "/$mType/list?titleId=$titleId"
        
        this.author = this@Manga.author ?: communityArtists.joinToString { it.name }
        
        status = when {
            rest -> SManga.ON_HIATUS
            finished -> SManga.COMPLETED
            else -> SManga.ONGOING
        }

        // 연재 요일, 연령, 장르, 태그들을 모아서 쉼표로 연결 (타치요미 UI에서 Chip 형태로 표시됨)
        val tags = mutableListOf<String>()
        
        publishDescription?.takeIf { it.isNotEmpty() }?.let { tags.add(it) } // 가장 맨 앞에 연재 요일 추가
        age?.description?.let { tags.add(it) } // ex) "15세 이용가", "전체연령가"
        genres.forEach { tags.add(it.description) } // 베도/도전 대분류 장르
        curationTagList.forEach { tags.add(it.tagName) } // 정식 연재 장르 및 세부 태그
        challengeTagList.forEach { tags.add(it) } // 베도/도전 세부 태그

        if (tags.isNotEmpty()) {
            genre = tags.joinToString(", ")
        }
    }
}

@Serializable
class MangaChallenge(
    private val thumbnailUrl: String,
    private val titleName: String,
    private val titleId: Int,
    val starScore: Double = 0.0,
    val viewCount: Long = 0L,
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
