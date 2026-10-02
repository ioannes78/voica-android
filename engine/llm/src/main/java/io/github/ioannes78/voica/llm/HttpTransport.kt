package io.github.ioannes78.voica.llm

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class LlmHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) {
    init {
        require(method == "GET" || method == "POST")
        require(connectTimeoutMs in 1..120_000)
        require(readTimeoutMs in 1..300_000)
        require(maxResponseBytes in 1..ABSOLUTE_MAX_RESPONSE_BYTES)
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val ABSOLUTE_MAX_RESPONSE_BYTES = 16 * 1024 * 1024
    }
}

data class LlmHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>>,
)

enum class TransportFailureKind {
    INVALID_URL,
    DNS,
    TLS,
    TIMEOUT,
    NETWORK,
    CANCELLED,
    RESPONSE_TOO_LARGE,
}

class LlmTransportException(
    val kind: TransportFailureKind,
    cause: Throwable? = null,
) : IOException(kind.name, cause)

interface LlmHttpTransport {
    suspend fun execute(
        requestId: String,
        request: LlmHttpRequest,
    ): LlmHttpResponse

    fun cancel(requestId: String)
}

class UrlConnectionLlmHttpTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LlmHttpTransport {
    private val active = ConcurrentHashMap<String, HttpURLConnection>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    override suspend fun execute(
        requestId: String,
        request: LlmHttpRequest,
    ): LlmHttpResponse =
        withContext(ioDispatcher) {
            require(requestId.isNotBlank())
            cancelled.remove(requestId)
            val url =
                try {
                    URL(request.url)
                } catch (error: Exception) {
                    throw LlmTransportException(TransportFailureKind.INVALID_URL, error)
                }
            if (!url.protocol.equals("https", ignoreCase = true)) {
                throw LlmTransportException(TransportFailureKind.INVALID_URL)
            }

            val connection =
                try {
                    url.openConnection() as HttpURLConnection
                } catch (error: Exception) {
                    throw mapTransportError(error, requestId)
                }
            active[requestId] = connection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = request.connectTimeoutMs
                connection.readTimeout = request.readTimeoutMs
                connection.requestMethod = request.method
                connection.doInput = true
                request.headers.forEach(connection::setRequestProperty)

                request.body?.let { body ->
                    connection.doOutput = true
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }

                currentCoroutineContext().ensureActive()
                val status = connection.responseCode
                val stream =
                    if (status in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                val responseBody =
                    stream?.use { input ->
                        val length = connection.contentLengthLong
                        if (length > request.maxResponseBytes) {
                            throw LlmTransportException(TransportFailureKind.RESPONSE_TOO_LARGE)
                        }
                        val output =
                            ByteArrayOutputStream(
                                if (length in 1..request.maxResponseBytes.toLong()) {
                                    length.toInt()
                                } else {
                                    16 * 1024
                                },
                            )
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            if (output.size() + read > request.maxResponseBytes) {
                                throw LlmTransportException(TransportFailureKind.RESPONSE_TOO_LARGE)
                            }
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray().toString(Charsets.UTF_8)
                    }.orEmpty()

                if (cancelled.contains(requestId)) {
                    throw LlmTransportException(TransportFailureKind.CANCELLED)
                }
                LlmHttpResponse(
                    statusCode = status,
                    body = responseBody,
                    headers =
                        connection.headerFields
                            .filterKeys { it != null }
                            .mapKeys { it.key!! },
                )
            } catch (error: LlmTransportException) {
                throw error
            } catch (error: Exception) {
                throw mapTransportError(error, requestId)
            } finally {
                active.remove(requestId, connection)
                cancelled.remove(requestId)
                connection.disconnect()
            }
        }

    override fun cancel(requestId: String) {
        cancelled += requestId
        active[requestId]?.disconnect()
    }

    private fun mapTransportError(
        error: Exception,
        requestId: String,
    ): LlmTransportException =
        if (cancelled.contains(requestId)) {
            LlmTransportException(TransportFailureKind.CANCELLED, error)
        } else {
            when (error) {
                is UnknownHostException ->
                    LlmTransportException(TransportFailureKind.DNS, error)
                is SSLException ->
                    LlmTransportException(TransportFailureKind.TLS, error)
                is SocketTimeoutException ->
                    LlmTransportException(TransportFailureKind.TIMEOUT, error)
                is IllegalArgumentException ->
                    LlmTransportException(TransportFailureKind.INVALID_URL, error)
                else ->
                    LlmTransportException(TransportFailureKind.NETWORK, error)
            }
        }
}
