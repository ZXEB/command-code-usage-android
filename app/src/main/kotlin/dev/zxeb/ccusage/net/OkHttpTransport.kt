package dev.zxeb.ccusage.net

import dev.zxeb.ccusage.model.UsageError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 基于 OkHttp 的传输实现。
 *
 * 超时按官方实测延迟设定：这些账单端点很慢（whoami 7~17s、subscriptions 可达 20s+），
 * 所以单个请求默认 **45 秒**，不要凭感觉调小 —— 调小必然误报超时（docs/QUOTA.md §5）。
 */
class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
) : HttpTransport {

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Long,
    ): TransportResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()

        // 按请求粒度控制超时，避免为一个慢端点把全局客户端调钝
        val scoped = client.newBuilder()
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()

        try {
            scoped.newCall(request).execute().use { response ->
                TransportResponse(
                    status = response.code,
                    body = response.body?.string().orEmpty(),
                )
            }
        } catch (e: SocketTimeoutException) {
            throw UsageError.Timeout(pathOf(url), timeoutSeconds)
        } catch (e: IOException) {
            throw UsageError.Network(e.message ?: "连接失败")
        }
    }

    private fun pathOf(url: String): String =
        runCatching { java.net.URI(url).path }.getOrNull() ?: url

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
    }
}
