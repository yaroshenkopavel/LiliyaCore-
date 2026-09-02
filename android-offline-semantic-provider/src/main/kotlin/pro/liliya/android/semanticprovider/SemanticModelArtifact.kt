package pro.liliya.android.semanticprovider

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal data class SemanticModelArtifactSpec(
    val profileGeneration: SemanticProfileGeneration,
    val expectedSizeBytes: Long,
    val expectedSha256: String
) {
    init {
        require(expectedSizeBytes > 0L) { "semantic model artifact size must be positive" }
        require(SHA256_PATTERN.matches(expectedSha256)) {
            "semantic model artifact SHA-256 must be lowercase hexadecimal"
        }
    }

    override fun toString(): String =
        "SemanticModelArtifactSpec(profileGeneration=$profileGeneration, expectedSizeBytes=$expectedSizeBytes, expectedSha256=<redacted>)"

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

internal class ValidatedSemanticModelArtifact internal constructor(
    internal val file: File,
    val spec: SemanticModelArtifactSpec
) {
    override fun toString(): String =
        "ValidatedSemanticModelArtifact(profileGeneration=${spec.profileGeneration}, path=<redacted>)"
}

internal sealed interface SemanticModelArtifactValidationResult {
    data class Validated(val artifact: ValidatedSemanticModelArtifact) :
        SemanticModelArtifactValidationResult {
        override fun toString(): String = "Validated(artifact=$artifact)"
    }

    data object OutsideAppPrivateRoot : SemanticModelArtifactValidationResult
    data object Missing : SemanticModelArtifactValidationResult
    data object NotRegularFile : SemanticModelArtifactValidationResult
    data object SizeMismatch : SemanticModelArtifactValidationResult
    data object DigestMismatch : SemanticModelArtifactValidationResult
    data class Failed(val exceptionClass: String) : SemanticModelArtifactValidationResult {
        override fun toString(): String = "Failed(exceptionClass=$exceptionClass)"
    }
}

internal class SemanticModelArtifactValidator(appPrivateRoot: File) {
    private val canonicalRoot: File = appPrivateRoot.canonicalFile

    fun validate(
        candidate: File,
        spec: SemanticModelArtifactSpec
    ): SemanticModelArtifactValidationResult {
        return try {
            val canonicalCandidate = candidate.canonicalFile
            if (!isInsideRoot(canonicalCandidate)) {
                return SemanticModelArtifactValidationResult.OutsideAppPrivateRoot
            }
            if (!canonicalCandidate.exists()) {
                return SemanticModelArtifactValidationResult.Missing
            }
            if (!canonicalCandidate.isFile) {
                return SemanticModelArtifactValidationResult.NotRegularFile
            }
            if (canonicalCandidate.length() != spec.expectedSizeBytes) {
                return SemanticModelArtifactValidationResult.SizeMismatch
            }

            val actualDigest = sha256(canonicalCandidate)
            if (actualDigest != spec.expectedSha256) {
                return SemanticModelArtifactValidationResult.DigestMismatch
            }

            SemanticModelArtifactValidationResult.Validated(
                ValidatedSemanticModelArtifact(canonicalCandidate, spec)
            )
        } catch (failure: Throwable) {
            SemanticModelArtifactValidationResult.Failed(
                failure::class.qualifiedName ?: failure::class.simpleName ?: "Throwable"
            )
        }
    }

    private fun isInsideRoot(candidate: File): Boolean {
        val rootPath = canonicalRoot.path
        val candidatePath = candidate.path
        return candidatePath == rootPath ||
            candidatePath.startsWith(rootPath + File.separator)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private companion object {
        const val HASH_BUFFER_BYTES = 64 * 1024
    }
}
