package dev.localtrans.app.update

import dev.localtrans.app.net.Http
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object ApkDownloader {

    /** APK를 받으면서 SHA-256을 계산하고, latest.json 의 값과 다르면 파일을 지우고 실패한다. */
    suspend fun download(manifest: UpdateManifest, dest: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val conn = Http.open(manifest.apkUrl)
            try {
                val code = conn.responseCode
                check(code == 200) { "다운로드 실패 (HTTP $code)" }
                val total = if (manifest.sizeBytes > 0) manifest.sizeBytes else conn.contentLengthLong
                val actual = conn.inputStream.use { input ->
                    copyWithDigest(input, dest) { done ->
                        ensureActive()
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
                if (!actual.equals(manifest.sha256.trim(), ignoreCase = true)) {
                    dest.delete()
                    error("파일 검증 실패 (SHA-256 불일치)")
                }
            } catch (t: Throwable) {
                dest.delete()
                throw t
            } finally {
                conn.disconnect()
            }
        }

    /** @return 받은 내용의 SHA-256 (소문자 16진수) */
    fun copyWithDigest(input: InputStream, dest: File, onBytes: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var done = 0L
        dest.outputStream().use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                digest.update(buf, 0, n)
                done += n
                onBytes(done)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
