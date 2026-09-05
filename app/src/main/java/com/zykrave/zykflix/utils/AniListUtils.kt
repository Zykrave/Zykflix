package com.zykrave.zykflix.utils

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class AniListAnime(
    val id: Int,
    val title: String,
    val description: String,
    val episodeCount: Int?,
    val rating: Double?,
    val poster: String?,
    val banner: String?,
    val genres: List<String>,
)

object AniListUtils {
    private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"

    private val ANIME_PROVIDER_NAMES = setOf(
        "AnimeWorldProvider",
        "AniWorldProvider",
        "AnikotoProvider",
        "LatanimeProvider",
        "JKAnimeProvider",
        "TioAnimeProvider",
        "AnimeAv1Provider",
        "AnimeOnlineNinjaProvider",
        "AnimeUnityProvider",
        "FrenchMangaProvider",
    )

    private val fallbackClient by lazy { OkHttpClient() }

    private val httpClient: OkHttpClient
        get() = runCatching { NetworkClient.default }.getOrElse { fallbackClient }

    fun isAnimeProvider(providerClassName: String): Boolean {
        return ANIME_PROVIDER_NAMES.contains(providerClassName)
    }

    suspend fun getAnimeInfo(title: String, year: Int? = null): AniListAnime? = withContext(Dispatchers.IO) {
        val query = """
            query (${'$'}search: String, ${'$'}seasonYear: Int) {
                Media(search: ${'$'}search, type: ANIME, seasonYear: ${'$'}seasonYear) {
                    id
                    title { romaji english }
                    description
                    episodes
                    averageScore
                    coverImage { extraLarge }
                    bannerImage
                    genres
                }
            }
        """.trimIndent()

        val variables = JsonObject().apply {
            addProperty("search", title)
            if (year != null) addProperty("seasonYear", year) else add("seasonYear", JsonNull.INSTANCE)
        }
        val body = JsonObject().apply {
            addProperty("query", query)
            add("variables", variables)
        }

        val request = Request.Builder()
            .url(ANILIST_GRAPHQL_URL)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val responseBody = response.body?.string() ?: return@withContext null
                val media = JsonParser.parseString(responseBody).asJsonObject
                    .getAsJsonObject("data")
                    ?.getAsJsonObject("Media") ?: return@withContext null

                val titleObj = media.getAsJsonObject("title")
                val resolvedTitle = titleObj?.get("english")?.takeIf { !it.isJsonNull }?.asString
                    ?: titleObj?.get("romaji")?.takeIf { !it.isJsonNull }?.asString
                    ?: title

                val rawDescription = media.get("description")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val cleanDescription = rawDescription
                    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("<[^>]*>"), "")
                    .trim()

                AniListAnime(
                    id = media.get("id").asInt,
                    title = resolvedTitle,
                    description = cleanDescription,
                    episodeCount = media.get("episodes")?.takeIf { !it.isJsonNull }?.asInt,
                    rating = media.get("averageScore")?.takeIf { !it.isJsonNull }?.asDouble?.div(10.0),
                    poster = media.getAsJsonObject("coverImage")?.get("extraLarge")?.takeIf { !it.isJsonNull }?.asString,
                    banner = media.get("bannerImage")?.takeIf { !it.isJsonNull }?.asString,
                    genres = media.getAsJsonArray("genres")?.map { it.asString } ?: listOf(),
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
