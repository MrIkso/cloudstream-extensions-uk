package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.*

class UakinoProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://uakino.club"
    override var name = "Uakino"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/filmy/page/" to "Фільми",
        "$mainUrl/seriesss/page/" to "Серіали",
        "$mainUrl/seriesss/doramy/page/" to "Дорами",
        "$mainUrl/animeukr/page/" to "Аніме",
        "$mainUrl/cartoon/page/" to "Мультфільми",
        "$mainUrl/cartoon/cartoonseries/page/" to "Мультсеріали",
    )

    val blackUrls = "(/news/)|(/franchise/)"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.owl-item, div.movie-item")
            .filterNot { el -> el.select("a.movie-title, a.full-movie").attr("href").contains(blackUrls) }
            .map {
                it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.selectFirst("a.movie-title, div.full-movie-title")?.text()?.trim().toString()
        val href = this.selectFirst("a.movie-title, a.full-movie")?.attr("href").toString()
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            url = mainUrl,
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query.replace(" ", "+")
            )
        ).document
        return document.select("div.movie-item.short-item").map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Parse info
        val title = document.selectFirst("h1 span.solototle")?.text()?.trim().toString()
        val poster = fixUrl(document.selectFirst("div.film-poster img")?.attr("src").toString())

        var tags = emptyList<String>()
        var year = 2023
        var actors = emptyList<String>()
        var rating = "0".toRatingInt()

        document.select(".fi-item-s, .fi-item").forEach { metadata ->
            with(metadata.select(".fi-label").text()) {
                when {
                    contains("Рік виходу:") -> year = metadata.select(".fi-desc").text().toInt()
                    contains("Жанр:") -> tags = metadata.select(".fi-desc").text().split(" , ")
                    contains("Актори:") -> actors = metadata.select(".fi-desc").text().split(", ")
                    contains("") -> rating = metadata.select(".fi-desc").text().substringBefore("/").toRatingInt()
                }
                // Log.d("CakesTwix-Debug", this)
                // Log.d("CakesTwix-Debug", metadata.select(".fi-desc").text())
            }
        }

        // reversed need for check "Мультсеріали"
        // tags: Мультфільми , Мультсеріали
        // It's Cartoon, not Movie
        var tvType = with(tags.reversed()){
            when{
                contains("Повнометражне аніме") -> TvType.AnimeMovie
                contains("Мультсеріали") -> TvType.Cartoon
                contains("Мультфільми") -> TvType.Movie
                contains("Багатосерійне аніме") -> TvType.Anime
                contains("Дорами") -> TvType.AsianDrama
                else -> TvType.Others
            }
        }
        // Log.d("CakesTwix-Debug", tvType.toString())
        if (tvType == TvType.Others) {
            tvType = if (url.contains(Regex("(/anime-series)|(/seriesss)|(/cartoonseries)"))) TvType.TvSeries else TvType.Movie
        }

        val description = document.selectFirst("div[itemprop=description]")?.text()?.trim()
        val trailer = document.selectFirst("iframe#pre")?.attr("data-src")


        val recommendations = document.select(".related-item").map {
            it.toSearchResponse()
        }

        // Return to app
        // Parse Episodes as Series
        return if (tvType != TvType.Movie && tvType != TvType.AnimeMovie) {
            val id = url.split("/").last().split("-").first()
            val episodes =
                app.get("$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}")
                    .parsedSafe<Responses>()?.response.let {
                        Jsoup.parse(it.toString()).select("div.playlists-videos li").mapNotNull { eps ->
                            val href = "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}"
                            val name = eps.text().trim() // Серія 1
                            if (href.isNotEmpty()) {
                                Episode(
                                    "$href,$name", // link, Серія 1
                                    name,
                                )
                            } else {
                                null
                            }
                        }
                    }
            newTvSeriesLoadResponse(title, url, tvType, episodes.distinctBy{ it.name }) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // link, episode name
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(",")
        // TODO: OPTIMIZE code!!! Remove this shitty code as soon as possible!!!!!!
        if(dataList.size == 1){
            val id = data.split("/").last().split("-").first()
            val responseGet = app.get("$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}").parsedSafe<Responses>()
            if (responseGet?.success == true) { // Its serial
                responseGet.response?.let {
                    Jsoup.parse(it).select("div.playlists-videos li")
                        .mapNotNull { eps ->
                            var href = eps.attr("data-file")  // ashdi
                            // Can be without https:
                            if (!href.contains("https://")) {
                                href = "https:$href"
                            }
                            val dub = eps.attr("data-voice")  // FanWoxUA

                            // Get m3u from player script
                            app.get(href, referer = "$mainUrl/").document.select("script")
                                .map { script ->
                                    if (script.data().contains("var player = new Playerjs({")) {
                                        val m3uLink = script.data().substringAfterLast("file:\"")
                                            .substringBefore("\",")

                                        // Add as source
                                        M3u8Helper.generateM3u8(
                                            source = dub,
                                            streamUrl = m3uLink,
                                            referer = "https://ashdi.vip/"
                                        ).forEach(callback)
                                    }
                                }
                        }
                }
            } else {
                // Its maybe film
                val document = app.get(data).document
                val iframeUrl = document.selectFirst("iframe#pre")?.attr("src")
                // Get m3u from player script
                if (iframeUrl != null) {
                    app.get(iframeUrl, referer = "$mainUrl/").document.select("script").map { script ->
                        if (script.data().contains("var player = new Playerjs({")) {
                            val m3uLink = script.data().substringAfterLast("file:\"").substringBefore("\",")

                            // Add as source
                            M3u8Helper.generateM3u8(
                                source = document.selectFirst("h1 span.solototle")?.text()?.trim().toString(),
                                streamUrl = m3uLink,
                                referer = "https://ashdi.vip/"
                            ).forEach(callback)
                        }
                    }
                }
            }
            return true
        }

        val responseGet = app.get(dataList[0]).parsedSafe<Responses>() // ajax link
        if (responseGet?.success == true){ // Its serial
            responseGet?.response?.let {
                Jsoup.parse(it).select("div.playlists-videos li:contains(${dataList[1]})").mapNotNull { eps ->
                    var href = eps.attr("data-file")  // ashdi
                    // Can be without https:
                    if (! href.contains("https://")) {
                        href = "https:$href"
                    }
                    val dub = eps.attr("data-voice")  // FanWoxUA

                    // Get m3u from player script
                    app.get(href, referer = "$mainUrl/").document.select("script").map { script ->
                        if (script.data().contains("var player = new Playerjs({")) {
                            val m3uLink = script.data().substringAfterLast("file:\"").substringBefore("\",")

                            // Add as source
                            M3u8Helper.generateM3u8(
                                source = dub,
                                streamUrl = m3uLink,
                                referer = "https://ashdi.vip/"
                            ).forEach(callback)
                        }
                    }
                }
            }
        } else {
            // Its maybe film
            val document = app.get(data).document
            val iframeUrl = document.selectFirst("iframe#pre")?.attr("src")
            // Get m3u from player script
            if (iframeUrl != null) {
                app.get(iframeUrl, referer = "$mainUrl/").document.select("script").map { script ->
                    if (script.data().contains("var player = new Playerjs({")) {
                        val m3uLink = script.data().substringAfterLast("file:\"").substringBefore("\",")

                        // Add as source
                        M3u8Helper.generateM3u8(
                            source = document.selectFirst("h1 span.solototle")?.text()?.trim().toString(),
                            streamUrl = m3uLink,
                            referer = "https://ashdi.vip/"
                        ).forEach(callback)
                    }
                }
            }
        }


        return true
    }

    data class Responses(
        val success: Boolean?,
        val response: String,
    )

}