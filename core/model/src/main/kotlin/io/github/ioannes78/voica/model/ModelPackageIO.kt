package io.github.ioannes78.voica.model

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

data class ModelDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
)

fun interface ModelDownloadProgressListener {
    suspend fun onProgress(progress: ModelDownloadProgress)
}

interface ModelPackageDownloader {
    suspend fun download(
        url: String,
        destinationPart: File,
        expectedBytes: Long?,
        progressListener: ModelDownloadProgressListener? = null,
    )
}

class HttpModelPackageDownloader(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModelPackageDownloader {
    override suspend fun download(
        url: String,
        destinationPart: File,
        expectedBytes: Long?,
        progressListener: ModelDownloadProgressListener?,
    ) = withContext(ioDispatcher) {
        require(url.startsWith("https://")) { "model downloads require HTTPS" }
        require(expectedBytes == null || expectedBytes >= 0L)

        destinationPart.parentFile?.mkdirs()
        if (destinationPart.exists()) destinationPart.delete()

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"

        try {
            connection.connect()
            val code = connection.responseCode
            check(code in 200..299) { "model download HTTP $code" }

            val total =
                expectedBytes
                    ?: connection.contentLengthLong.takeIf { it >= 0L }
            var downloaded = 0L

            BufferedInputStream(connection.inputStream, BUFFER_BYTES).use { input ->
                FileOutputStream(destinationPart).buffered(BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        downloaded += read
                        check(expectedBytes == null || downloaded <= expectedBytes) {
                            "download exceeded manifest size"
                        }
                        progressListener?.onProgress(
                            ModelDownloadProgress(downloaded, total),
                        )
                    }
                    output.flush()
                }
            }

            check(expectedBytes == null || downloaded == expectedBytes) {
                "download size mismatch: expected $expectedBytes, got $downloaded"
            }
        } catch (error: Throwable) {
            destinationPart.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 64 * 1024
    }
}

class ModelPackageExtractor {
    fun verifyPackage(
        descriptor: ModelDescriptor,
        packageFile: File,
    ) {
        val expectedSize = descriptor.downloadSizeBytes
            ?: error("descriptor has no downloadable package size")
        val expectedSha = descriptor.packageSha256
            ?: error("descriptor has no downloadable package SHA-256")

        check(packageFile.isFile) { "model package missing" }
        check(packageFile.length() == expectedSize) { "model package size mismatch" }
        check(sha256Hex(packageFile).equals(expectedSha, ignoreCase = true)) {
            "model package SHA-256 mismatch"
        }
    }

    fun extract(
        descriptor: ModelDescriptor,
        packageFile: File,
        stagingDirectory: File,
    ) {
        require(stagingDirectory.isDirectory)
        val format = descriptor.packageFormat
            ?: error("descriptor has no package format")
        val expectedByPackagePath = descriptor.files.associateBy { normalize(it.packagePath) }

        when (format) {
            ModelPackageFormat.SINGLE_FILE -> {
                check(descriptor.files.size == 1) {
                    "single-file package must contain exactly one installed file"
                }
                copyExpected(
                    inputFile = packageFile,
                    expected = descriptor.files.single(),
                    stagingDirectory = stagingDirectory,
                )
            }

            ModelPackageFormat.ZIP ->
                ZipInputStream(BufferedInputStream(FileInputStream(packageFile))).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) {
                            val normalized = normalize(entry.name)
                            val expected = expectedByPackagePath[normalized]
                            if (expected != null) {
                                writeExpectedStream(
                                    input = zip,
                                    expected = expected,
                                    stagingDirectory = stagingDirectory,
                                )
                            }
                        }
                        zip.closeEntry()
                    }
                }

            ModelPackageFormat.TAR_BZ2 ->
                TarArchiveInputStream(
                    BZip2CompressorInputStream(
                        BufferedInputStream(FileInputStream(packageFile)),
                        true,
                    ),
                ).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.isFile) {
                            val normalized = normalize(entry.name)
                            val expected = expectedByPackagePath[normalized]
                            if (expected != null) {
                                check(entry.size == expected.sizeBytes) {
                                    "archive entry size mismatch: ${expected.packagePath}"
                                }
                                writeExpectedStream(
                                    input = tar,
                                    expected = expected,
                                    stagingDirectory = stagingDirectory,
                                )
                            }
                        }
                    }
                }
        }

        check(
            ModelStorage(stagingDirectory.parentFile.parentFile)
                .verifyDirectory(descriptor, stagingDirectory) == ModelVerificationResult.Valid,
        ) { "extracted model files failed verification" }
    }

    private fun copyExpected(
        inputFile: File,
        expected: ModelFileDescriptor,
        stagingDirectory: File,
    ) {
        check(inputFile.length() == expected.sizeBytes) {
            "single-file installed size mismatch"
        }
        inputFile.inputStream().use { input ->
            writeExpectedStream(input, expected, stagingDirectory)
        }
    }

    private fun writeExpectedStream(
        input: java.io.InputStream,
        expected: ModelFileDescriptor,
        stagingDirectory: File,
    ) {
        val target = safeTarget(stagingDirectory, expected.relativePath)
        target.parentFile?.mkdirs()

        var written = 0L
        FileOutputStream(target).buffered(BUFFER_BYTES).use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (written < expected.sizeBytes) {
                val remaining = expected.sizeBytes - written
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(read > 0) { "archive entry shorter than manifest" }
                output.write(buffer, 0, read)
                written += read
            }
            output.flush()
        }
        check(written == expected.sizeBytes)
    }

    private fun safeTarget(
        root: File,
        relativePath: String,
    ): File {
        require(isSafeRelativePath(relativePath))
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relativePath).canonicalFile
        val rootPath = canonicalRoot.path
        check(
            target.path == rootPath ||
                target.path.startsWith(rootPath + File.separator),
        ) { "archive path escapes staging root" }
        return target
    }

    private fun normalize(path: String): String {
        val normalized = path.replace('\\', '/').removePrefix("./")
        require(isSafeRelativePath(normalized)) { "unsafe archive path" }
        return normalized
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
