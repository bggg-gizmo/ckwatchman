package com.bggg.watchman.net

import com.bggg.watchman.data.ProviderConfig
import com.bggg.watchman.service.NavigationRuntimePolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

object HttpClient {
    private const val MAX_RESPONSE_CHARS = 16 * 1024 * 1024
    private val deadlineScheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "watchman-http-deadline").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    fun get(url: String, timeoutMs: Int = 12000, attempts: Int = 2): String =
        request(url, "GET", null, timeoutMs, attempts)

    /** POST application/x-www-form-urlencoded. Preferred for large Overpass queries. */
    fun postForm(url: String, fields: Map<String, String>, timeoutMs: Int = 18000, attempts: Int = 1): String {
        val body = fields.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return request(url, "POST", body, timeoutMs, attempts)
    }

    fun postJson(url: String, body: String, timeoutMs: Int, headers: Map<String, String> = emptyMap()): String =
        request(url, "POST", body, timeoutMs, 1, "application/json; charset=UTF-8", headers)

    /** Single-attempt bounded binary GET sharing the same total deadline and disconnect watchdog. */
    fun getBytes(
        url: String,
        timeoutMs: Int = 12_000,
        maxBytes: Int = 2 * 1024 * 1024,
        accept: String = "application/octet-stream"
    ): ByteArray {
        if (Thread.currentThread().isInterrupted) throw IOException("Network request interrupted")
        val boundedMax = maxBytes.coerceIn(1_024, 8 * 1024 * 1024)
        val deadline = OperationDeadline(timeoutMs.coerceIn(1_000, 60_000).toLong())
        val remaining = deadline.remainingMillis()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = deadline.socketTimeoutMillis()
        connection.readTimeout = deadline.socketTimeoutMillis()
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", ProviderConfig.USER_AGENT)
        connection.setRequestProperty("Accept", accept)
        connection.instanceFollowRedirects = true
        val watchdog = deadlineScheduler.schedule({ connection.disconnect() }, remaining, TimeUnit.MILLISECONDS)
        try {
            val code = connection.responseCode
            val contentLength = connection.contentLengthLong
            if (contentLength > boundedMax) throw IOException("Binary response is too large ($contentLength bytes)")
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { readBytesBounded(it, boundedMax) } ?: ByteArray(0)
            if (code !in 200..299) throw HttpStatusException(code, null, "HTTP $code")
            if (deadline.isExpired()) throw timeout()
            return bytes
        } catch (error: Exception) {
            if (deadline.isExpired()) throw timeout(error)
            throw error
        } finally {
            watchdog.cancel(false)
            connection.disconnect()
        }
    }

    private fun request(url: String, method: String, body: String?, timeoutMs: Int, attempts: Int,
        contentType: String = "application/x-www-form-urlencoded; charset=UTF-8",
        headers: Map<String, String> = emptyMap()): String {
        var last: Exception? = null
        val totalAttempts = attempts.coerceIn(1, 3)
        val deadline = OperationDeadline(timeoutMs.coerceIn(1_000, 60_000).toLong())
        repeat(totalAttempts) { attempt ->
            try {
                if (Thread.currentThread().isInterrupted) throw IOException("Network request interrupted")
                val remaining = deadline.remainingMillis()
                if (remaining <= 0L) throw timeout(last)
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = deadline.socketTimeoutMillis()
                connection.readTimeout = deadline.socketTimeoutMillis()
                connection.requestMethod = method
                connection.setRequestProperty("User-Agent", ProviderConfig.USER_AGENT)
                connection.setRequestProperty("Accept", "application/json, application/geo+json, application/xml, text/xml, */*;q=0.5")
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                connection.instanceFollowRedirects = true
                val watchdog = deadlineScheduler.schedule({ connection.disconnect() }, remaining, TimeUnit.MILLISECONDS)
                try {
                    if (body != null) {
                        val bytes = body.toByteArray(StandardCharsets.UTF_8)
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", contentType)
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                    }
                    val code = connection.responseCode
                    val contentLength = connection.contentLengthLong
                    if (contentLength > MAX_RESPONSE_CHARS.toLong() * 4L) {
                        throw IOException("Response is too large to process safely ($contentLength bytes)")
                    }
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.use(::readBounded).orEmpty()
                    if (code !in 200..299) {
                        val retryAfterMs = connection.getHeaderField("Retry-After")
                            ?.trim()?.toLongOrNull()?.times(1_000L)
                        throw HttpStatusException(code, retryAfterMs, "HTTP $code: ${text.take(220)}")
                    }
                    if (deadline.isExpired()) throw timeout()
                    return text
                } finally {
                    watchdog.cancel(false)
                    connection.disconnect()
                }
            } catch (error: Exception) {
                last = error
                if (deadline.isExpired()) throw timeout(error)
                val retryable = when (error) {
                    is HttpStatusException -> NavigationRuntimePolicy.shouldRetryHttp(error.code)
                    is IOException -> true
                    else -> false
                }
                if (!retryable || attempt + 1 >= totalAttempts) throw error
                val retryAfter = (error as? HttpStatusException)?.retryAfterMs
                val retryDelay = deadline.admittedRetryDelayMillis(
                    NavigationRuntimePolicy.retryDelayMs(attempt, retryAfter)
                ) ?: throw timeout(error)
                try {
                    Thread.sleep(retryDelay)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Network retry interrupted", interrupted)
                }
            }
        }
        throw last ?: IOException("Network request failed")
    }

    private fun timeout(cause: Exception? = null): SocketTimeoutException =
        SocketTimeoutException("Network operation exceeded its total deadline").apply {
            if (cause != null) initCause(cause)
        }

    private fun readBytesBounded(stream: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw IOException("Network request interrupted")
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) throw IOException("Binary response exceeds the $maxBytes byte processing limit")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun readBounded(stream: InputStream): String {
        val reader = InputStreamReader(stream, StandardCharsets.UTF_8)
        val buffer = CharArray(8 * 1024)
        val result = StringBuilder()
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (result.length + read > MAX_RESPONSE_CHARS) {
                throw IOException("Response exceeds the ${MAX_RESPONSE_CHARS / (1024 * 1024)} MB processing limit")
            }
            result.append(buffer, 0, read)
        }
        return result.toString()
    }

    private class HttpStatusException(
        val code: Int,
        val retryAfterMs: Long?,
        message: String
    ) : IOException(message)

    fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8.name())
}
