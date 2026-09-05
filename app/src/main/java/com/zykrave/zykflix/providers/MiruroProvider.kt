package com.zykrave.zykflix.providers

import android.util.Base64
import android.util.Log
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zykrave.zykflix.adapters.AppAdapter
import com.zykrave.zykflix.models.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.concurrent.TimeUnit

object MiruroProvider : Provider {

    override val name = "Miruro"
    override val baseUrl = "https://www.miruro.to"
    override val language = "en"
    override val logo = "$baseUrl/icon-512x512.png"

    private const val ANILIST_URL = "https://graphql.anilist.co"
    private const val PIPE_URL = "https://www.miruro.to/api/secure/pipe"

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    // ─── Pipe encode/decode helpers (base64 + gzip) ───

    private fun encodePipeRequest(payload: JsonObject): String {
        return Base64.encodeToString(
            payload.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun decodePipeResponse(encoded: String): JsonObject {
        val decoded = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
        val gzipStream = GZIPInputStream(ByteArrayInputStream(decoded))
        val json = gzipStream.bufferedReader().use { it.readText() }
        return JsonParser.parseString(json).asJsonObject
    }

    private fun decodeEpisodeId(rawId: String): String {
        return try {
            val decodedBytes = Base64.decode(rawId, Base64.URL_SAFE)
            val decodedStr = String(decodedBytes, Charsets.UTF_8)
            if (decodedStr.all { it.code in 32..126 || it == '\n' || it == '\r' || it == '\t' }) {
                decodedStr
            } else {
                rawId
            }
        } catch (_: Exception) {
            rawId
        }
    }

    private fun fetchPipe(payload: JsonObject): JsonObject {
        val encoded = encodePipeRequest(payload)
        val request = Request.Builder()
            .url("$PIPE_URL?e=$encoded")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .addHeader("Referer", baseUrl)
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Pipe request failed with HTTP ${response.code}")
            }
            val responseBody = response.body?.string()
                ?: throw Exception("Empty response body from pipe endpoint")
            decodePipeResponse(responseBody)
        }
    }

    // ─── Stub methods (to be implemented in later steps) ───

    override suspend fun getHome(): List<Category> = emptyList()

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()

        return try {
            val graphqlQuery = """
                query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
                  Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
                      id
                      title { romaji english }
                      coverImage { extraLarge }
                      bannerImage
                      description(asHtml: false)
                      episodes
                      averageScore
                      genres
                    }
                  }
                }
            """.trimIndent()

            val variables = JsonObject().apply {
                addProperty("search", query)
                addProperty("page", page)
                addProperty("perPage", 20)
            }

            val body = JsonObject().apply {
                addProperty("query", graphqlQuery)
                add("variables", variables)
            }

            val request = Request.Builder()
                .url(ANILIST_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val responseBody = response.body?.string() ?: return emptyList()

                val mediaArray = JsonParser.parseString(responseBody).asJsonObject
                    .getAsJsonObject("data")
                    ?.getAsJsonObject("Page")
                    ?.getAsJsonArray("media") ?: return emptyList()

                mediaArray.mapNotNull { element ->
                    val media = element.asJsonObject
                    val id = media.get("id")?.takeIf { !it.isJsonNull }?.asInt?.toString()
                        ?: return@mapNotNull null

                    val titleObj = media.getAsJsonObject("title")
                    val title = titleObj?.get("english")?.takeIf { !it.isJsonNull }?.asString
                        ?: titleObj?.get("romaji")?.takeIf { !it.isJsonNull }?.asString
                        ?: ""

                    val poster = media.getAsJsonObject("coverImage")
                        ?.get("extraLarge")?.takeIf { !it.isJsonNull }?.asString
                    val banner = media.get("bannerImage")
                        ?.takeIf { !it.isJsonNull }?.asString

                    val rawDescription = media.get("description")
                        ?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val cleanDescription = rawDescription
                        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                        .replace(Regex("<[^>]*>"), "")
                        .trim()

                    val rating = media.get("averageScore")
                        ?.takeIf { !it.isJsonNull }?.asDouble?.div(10.0)

                    val genres = media.getAsJsonArray("genres")?.mapNotNull { g ->
                        val genreName = g.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                        Genre(id = genreName, name = genreName)
                    } ?: emptyList()

                    TvShow(
                        id = id,
                        title = title,
                        poster = poster,
                        banner = banner,
                        overview = cleanDescription,
                        rating = rating,
                        genres = genres,
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()

    override suspend fun getGenre(id: String, page: Int): Genre =
        Genre(id = id, name = "", shows = emptyList())

    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "")

    override suspend fun getTvShow(id: String): TvShow {
        val numericId = id.toIntOrNull() ?: return TvShow(id = id, title = "Error al cargar")

        return try {
            val graphqlQuery = """
                query (${'$'}id: Int) {
                  Media(id: ${'$'}id, type: ANIME) {
                    id
                    title { romaji english }
                    coverImage { extraLarge }
                    bannerImage
                    description(asHtml: false)
                    episodes
                    averageScore
                    genres
                    startDate { year }
                  }
                }
            """.trimIndent()

            val variables = JsonObject().apply {
                addProperty("id", numericId)
            }

            val body = JsonObject().apply {
                addProperty("query", graphqlQuery)
                add("variables", variables)
            }

            val request = Request.Builder()
                .url(ANILIST_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return TvShow(id = id, title = "Error al cargar")
                val responseBody = response.body?.string() ?: return TvShow(id = id, title = "Error al cargar")

                val media = JsonParser.parseString(responseBody).asJsonObject
                    .getAsJsonObject("data")
                    ?.getAsJsonObject("Media") ?: return TvShow(id = id, title = "Error al cargar")

                val titleObj = media.getAsJsonObject("title")
                val title = titleObj?.get("english")?.takeIf { !it.isJsonNull }?.asString
                    ?: titleObj?.get("romaji")?.takeIf { !it.isJsonNull }?.asString
                    ?: ""

                val poster = media.getAsJsonObject("coverImage")
                    ?.get("extraLarge")?.takeIf { !it.isJsonNull }?.asString
                val banner = media.get("bannerImage")
                    ?.takeIf { !it.isJsonNull }?.asString

                val rawDescription = media.get("description")
                    ?.takeIf { !it.isJsonNull }?.asString ?: ""
                val cleanDescription = rawDescription
                    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("<[^>]*>"), "")
                    .trim()

                val rating = media.get("averageScore")
                    ?.takeIf { !it.isJsonNull }?.asDouble?.div(10.0)

                val genres = media.getAsJsonArray("genres")?.mapNotNull { g ->
                    val genreName = g.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                    Genre(id = genreName, name = genreName)
                } ?: emptyList()

                val year = media.getAsJsonObject("startDate")
                    ?.get("year")?.takeIf { !it.isJsonNull }?.asInt?.toString()

                val seasons = listOf(
                    Season(
                        id = id,
                        number = 1,
                        title = "Episodes",
                    )
                )

                TvShow(
                    id = id,
                    title = title,
                    poster = poster,
                    banner = banner,
                    overview = cleanDescription,
                    rating = rating,
                    released = year,
                    seasons = seasons,
                    genres = genres,
                )
            }
        } catch (_: Exception) {
            TvShow(id = id, title = "Error al cargar")
        }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val anilistId = seasonId.toIntOrNull() ?: return emptyList()

        return try {
            val payload = JsonObject().apply {
                addProperty("path", "episodes")
                addProperty("method", "GET")
                add("query", JsonObject().apply {
                    addProperty("anilistId", anilistId)
                })
                add("body", JsonNull.INSTANCE)
                addProperty("version", "0.1.0")
            }

            val jsonResponse = fetchPipe(payload)
            val providersObj = jsonResponse.getAsJsonObject("providers") ?: return emptyList()

            val episodeMap = LinkedHashMap<Int, String?>()

            for ((_, providerElement) in providersObj.entrySet()) {
                if (!providerElement.isJsonObject) continue
                val providerObj = providerElement.asJsonObject
                val episodesObj = providerObj.getAsJsonObject("episodes") ?: continue

                for (cat in listOf("sub", "dub")) {
                    val catArray = episodesObj.getAsJsonArray(cat) ?: continue
                    for (epElement in catArray) {
                        if (!epElement.isJsonObject) continue
                        val epObj = epElement.asJsonObject

                        // Parse and decode episode entry fields according to spec
                        val rawId = epObj.get("id")?.takeIf { !it.isJsonNull }?.asString
                        if (rawId != null) {
                            decodeEpisodeId(rawId)
                        }

                        val number = epObj.get("number")?.takeIf { !it.isJsonNull }?.asInt ?: continue
                        val rawTitle = epObj.get("title")?.takeIf { !it.isJsonNull }?.asString

                        if (!episodeMap.containsKey(number)) {
                            episodeMap[number] = rawTitle
                        } else if (episodeMap[number].isNullOrBlank() && !rawTitle.isNullOrBlank()) {
                            episodeMap[number] = rawTitle
                        }
                    }
                }
            }

            episodeMap.map { (number, title) ->
                Episode(
                    id = "$seasonId:$number",
                    number = number,
                    title = title
                )
            }.sortedBy { it.number }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val parts = id.split(":")
            val anilistIdStr = parts[0]
            val anilistId = anilistIdStr.toIntOrNull() ?: return emptyList()

            val epNumber = if (videoType is Video.Type.Movie) {
                1
            } else {
                if (parts.size >= 2) parts[1].toIntOrNull() ?: return emptyList() else return emptyList()
            }

            val payload = JsonObject().apply {
                addProperty("path", "episodes")
                addProperty("method", "GET")
                add("query", JsonObject().apply {
                    addProperty("anilistId", anilistId)
                })
                add("body", JsonNull.INSTANCE)
                addProperty("version", "0.1.0")
            }

            val jsonResponse = fetchPipe(payload)
            val providersObj = jsonResponse.getAsJsonObject("providers") ?: return emptyList()

            val servers = mutableListOf<Video.Server>()

            for ((providerName, providerElement) in providersObj.entrySet()) {
                if (!providerElement.isJsonObject) continue
                val providerObj = providerElement.asJsonObject
                val episodesObj = providerObj.getAsJsonObject("episodes") ?: continue

                for (category in listOf("sub", "dub")) {
                    val catArray = episodesObj.getAsJsonArray(category) ?: continue
                    for (epElement in catArray) {
                        if (!epElement.isJsonObject) continue
                        val epObj = epElement.asJsonObject
                        val number = epObj.get("number")?.takeIf { !it.isJsonNull }?.asInt ?: continue

                        if (number == epNumber) {
                            val originalEpisodeId = epObj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: continue
                            val serverId = "$anilistId|$providerName|$category|$originalEpisodeId"
                            val serverName = "$providerName - ${category.uppercase()}"
                            servers.add(Video.Server(id = serverId, name = serverName))
                        }
                    }
                }
            }

            servers
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val parts = server.id.split("|")
        if (parts.size < 4) {
            throw Exception("Invalid server id format: ${server.id}")
        }

        val anilistIdStr = parts[0]
        val provider = parts[1]
        val category = parts[2]
        val originalEpisodeId = parts[3]

        val anilistId = anilistIdStr.toIntOrNull()
            ?: throw Exception("Invalid anilistId in server id: $anilistIdStr")

        val encodedEpisodeId = Base64.encodeToString(
            originalEpisodeId.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val payload = JsonObject().apply {
            addProperty("path", "sources")
            addProperty("method", "GET")
            add("query", JsonObject().apply {
                addProperty("episodeId", encodedEpisodeId)
                addProperty("provider", provider)
                addProperty("category", category)
                addProperty("anilistId", anilistId)
            })
            add("body", JsonNull.INSTANCE)
            addProperty("version", "0.1.0")
        }

        val jsonResponse = fetchPipe(payload)

        val streamsArray = jsonResponse.getAsJsonArray("streams")
            ?: throw Exception("No streams found in response")

        var selectedUrl: String? = null
        var maxRes = -1

        for (streamElem in streamsArray) {
            if (!streamElem.isJsonObject) continue
            val streamObj = streamElem.asJsonObject
            val url = streamObj.get("url")?.takeIf { !it.isJsonNull }?.asString ?: continue
            val quality = streamObj.get("quality")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val res = Regex("(\\d+)p").find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            if (selectedUrl == null || res > maxRes) {
                selectedUrl = url
                maxRes = res
            }
        }

        val videoUrl = selectedUrl ?: throw Exception("No valid stream URL found in response")

        val subtitlesList = mutableListOf<Video.Subtitle>()
        val subtitlesArray = jsonResponse.getAsJsonArray("subtitles")
        if (subtitlesArray != null) {
            for (subElem in subtitlesArray) {
                if (!subElem.isJsonObject) continue
                val subObj = subElem.asJsonObject
                val file = subObj.get("file")?.takeIf { !it.isJsonNull }?.asString ?: continue
                val label = subObj.get("label")?.takeIf { !it.isJsonNull }?.asString ?: "Unknown"
                val isDefault = subObj.get("default")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                subtitlesList.add(Video.Subtitle(label = label, file = file, default = isDefault))
            }
        }

        return Video(
            source = videoUrl,
            subtitles = subtitlesList
        )
    }

    override suspend fun getPeople(id: String, page: Int): People =
        throw Exception("Not available on Miruro")
}
