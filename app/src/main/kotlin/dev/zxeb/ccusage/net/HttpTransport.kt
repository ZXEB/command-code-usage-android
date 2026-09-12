package dev.zxeb.ccusage.net

/**
 * 极薄的 HTTP 传输层抽象。
 *
 * 单独抽出来是为了让 [CommandCodeClient] 的并行取数 / 降级 / 重试逻辑能被单元测试覆盖，
 * 而不需要起真实网络或引入 MockWebServer。
 */
fun interface HttpTransport {
    /**
     * 发一个 GET。
     *
     * @throws dev.zxeb.ccusage.model.UsageError 网络层错误（超时 / 连不上）
     */
    suspend fun get(url: String, headers: Map<String, String>, timeoutSeconds: Long): TransportResponse
}

data class TransportResponse(
    val status: Int,
    val body: String,
) {
    val isSuccess: Boolean get() = status in 200..299
    val isAuthFailure: Boolean get() = status == 401 || status == 403
}
