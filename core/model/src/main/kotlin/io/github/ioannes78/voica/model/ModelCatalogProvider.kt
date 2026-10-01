package io.github.ioannes78.voica.model

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface ModelCatalogTextSource {
    suspend fun load(force: Boolean): String
}

class DecodingModelCatalogProvider(
    private val source: ModelCatalogTextSource,
) : ModelCatalogProvider {
    private val mutex = Mutex()

    @Volatile
    private var cached: ModelCatalog? = null

    override suspend fun load(force: Boolean): ModelCatalog {
        if (!force) {
            cached?.let { return it }
        }

        return mutex.withLock {
            if (!force) {
                cached?.let { return@withLock it }
            }
            val decoded = ModelCatalogCodec.decode(source.load(force))
            cached = decoded
            decoded
        }
    }
}

class HttpsModelCatalogTextSource(
    catalogUrl: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxBytes: Int = DEFAULT_MAX_MANIFEST_BYTES,
) : ModelCatalogTextSource {
    private val url = URL(catalogUrl)

    init {
        require(url.protocol.equals("https", ignoreCase = true)) {
            "model catalog requires HTTPS"
        }
        require(maxBytes in 1..ABSOLUTE_MAX_MANIFEST_BYTES)
    }

    override suspend fun load(force: Boolean): String =
        withContext(ioDispatcher) {
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            if (force) {
                connection.setRequestProperty("Cache-Control", "no-cache")
            }

            try {
                connection.connect()
                check(connection.url.protocol.equals("https", ignoreCase = true)) {
                    "model catalog redirect left HTTPS"
                }
                val code = connection.responseCode
                check(code in 200..299) {
                    "model catalog HTTP " + code
                }
                val contentLength = connection.contentLengthLong
                check(contentLength < 0L || contentLength <= maxBytes.toLong()) {
                    "model catalog exceeds size limit"
                }

                val output = ByteArrayOutputStream(
                    if (contentLength in 1..maxBytes.toLong()) {
                        contentLength.toInt()
                    } else {
                        16 * 1024
                    },
                )
                connection.inputStream.buffered(BUFFER_BYTES).use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        check(output.size() + read <= maxBytes) {
                            "model catalog exceeds size limit"
                        }
                        output.write(buffer, 0, read)
                    }
                }
                output.toByteArray().toString(Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        const val DEFAULT_MAX_MANIFEST_BYTES = 1024 * 1024
        private const val ABSOLUTE_MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val BUFFER_BYTES = 16 * 1024
    }
}
