import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    // 고장난 alias(kei.plugins.extension) 대신
    // 문자열 ID로 직접 플러그인을 불러옵니다!
    id("kei.plugins.extension")
}

keiyoushi {
    name = "Naver Comic"
    versionCode = 8
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        name = "Naver Webtoon"
        lang = "ko"
        baseUrl = "https://comic.naver.com"
    }

    source {
        name = "Naver Webtoon Best Challenge"
        lang = "ko"
        baseUrl = "https://comic.naver.com"
    }

    source {
        name = "Naver Webtoon Challenge"
        lang = "ko"
        baseUrl = "https://comic.naver.com"
    }
}
