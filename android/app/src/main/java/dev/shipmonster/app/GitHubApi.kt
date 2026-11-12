package dev.shipmonster.app

import java.net.HttpURLConnection
import java.net.URL

class ApiResponse(val code: Int, val body: String, val etag: String?)

/** Minimal read-only GitHub REST client. The app only ever talks to api.github.com. */
object GitHubApi {
    private const val BASE = "https://api.github.com"

    /** Throws IOException when the network is unavailable. */
    fun get(path: String, token: String?, etag: String? = null): ApiResponse {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "ShipMonster-Android")
            if (!token.isNullOrEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (!etag.isNullOrEmpty()) conn.setRequestProperty("If-None-Match", etag)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return ApiResponse(code, body, conn.getHeaderField("ETag"))
        } finally {
            conn.disconnect()
        }
    }
}
