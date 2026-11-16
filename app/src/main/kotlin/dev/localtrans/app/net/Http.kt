package dev.localtrans.app.net

import dev.localtrans.app.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpStatusException(val code: Int) : IOException("HTTP $code")

object Http {
    /** 릴리스 빌드는 https만 허용한다. http는 디버그 빌드의 로컬 테스트 서버용. */
    fun isAllowed(url: String, allowCleartext: Boolean): Boolean =
        url.startsWith("https://") || (allowCleartext && url.startsWith("http://"))

    fun open(url: String): HttpURLConnection {
        require(isAllowed(url, BuildConfig.DEBUG)) { "허용되지 않는 주소입니다: $url" }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Cache-Control", "no-cache")
        return conn
    }

    fun getText(url: String): String {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != 200) throw HttpStatusException(code)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
