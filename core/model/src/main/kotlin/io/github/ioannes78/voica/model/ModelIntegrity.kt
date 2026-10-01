package io.github.ioannes78.voica.model

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

enum class ModelIntegrityIssueKind {
    PATH_ESCAPE,
    MISSING_FILE,
    SIZE_MISMATCH,
    SHA256_MISMATCH,
}

data class ModelIntegrityIssue(
    val relativePath: String,
    val kind: ModelIntegrityIssueKind,
    val expected: String? = null,
    val actual: String? = null,
)

data class ModelIntegrityReport(
    val valid: Boolean,
    val issues: List<ModelIntegrityIssue>,
)

class ModelIntegrityException(
    val report: ModelIntegrityReport,
) : IllegalStateException(
        report.issues.joinToString(
            prefix = "model integrity verification failed: ",
            separator = "; ",
        ) { "${it.relativePath}:${it.kind}" },
    )

object ModelIntegrityVerifier {
    fun verifyDirectory(
        root: Path,
        descriptor: ModelDescriptor,
    ): ModelIntegrityReport {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val issues = mutableListOf<ModelIntegrityIssue>()

        descriptor.files.forEach { expected ->
            val candidate = normalizedRoot.resolve(expected.relativePath).normalize()
            if (!candidate.startsWith(normalizedRoot)) {
                issues +=
                    ModelIntegrityIssue(
                        relativePath = expected.relativePath,
                        kind = ModelIntegrityIssueKind.PATH_ESCAPE,
                    )
                return@forEach
            }
            if (!Files.isRegularFile(candidate)) {
                issues +=
                    ModelIntegrityIssue(
                        relativePath = expected.relativePath,
                        kind = ModelIntegrityIssueKind.MISSING_FILE,
                    )
                return@forEach
            }

            val actualSize = Files.size(candidate)
            if (actualSize != expected.sizeBytes) {
                issues +=
                    ModelIntegrityIssue(
                        relativePath = expected.relativePath,
                        kind = ModelIntegrityIssueKind.SIZE_MISMATCH,
                        expected = expected.sizeBytes.toString(),
                        actual = actualSize.toString(),
                    )
                return@forEach
            }

            val actualSha = sha256(candidate)
            if (!actualSha.equals(expected.sha256, ignoreCase = true)) {
                issues +=
                    ModelIntegrityIssue(
                        relativePath = expected.relativePath,
                        kind = ModelIntegrityIssueKind.SHA256_MISMATCH,
                        expected = expected.sha256.lowercase(),
                        actual = actualSha,
                    )
            }
        }

        return ModelIntegrityReport(
            valid = issues.isEmpty(),
            issues = issues,
        )
    }

    fun requireValidDirectory(
        root: Path,
        descriptor: ModelDescriptor,
    ) {
        val report = verifyDirectory(root, descriptor)
        if (!report.valid) throw ModelIntegrityException(report)
    }
}

internal fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}
