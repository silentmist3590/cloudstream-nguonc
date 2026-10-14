version = 4

cloudstream {
    description = "Xem phim từ API của kkphim.com"
    authors = listOf("silentmist3590")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama")
    requiresResources = false
    language = "vi"
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
}
