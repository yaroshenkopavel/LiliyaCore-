package pro.liliya.android.semanticprovider

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal data class SemanticModelArtifactSpec(
    val identity: SemanticModelArtifactIdentity
) {
    val profileGeneration: SemanticProfileGeneration get() = identity.profileGeneration
    val expectedSizeBytes: Long get() = identity.expectedSizeBytes
    val expectedSha256: String get() = identity.expectedSha256

    override fun toString(): String =
        "SemanticModelArtifactSpec(identity=$identity)"
}

internal class ValidatedSemanticModelArtifact internal constructor(
    internal val file: File,
    val spec: SemanticModelArtifactSpec
) {
    override fun toString(): String =
        "ValidatedSemanticModelArtifact(profileGeneration=${spec.profileGeneration}, path=<redacted>)"
}

internal enum class SemanticModelArtifactAcceptance {
    PRODUCTION,
    REPRODUCIBLE_CI_FIXTURE,
    CONTROLLED_BENCHMARK
}

internal sealed interface SemanticModelArtifactValidationResult {
    data class Validated(val artifact: ValidatedSemanticModelArtifact) :
        SemanticModelArtifactValidationResult {
        override fun toString(): String = "Validated(artifact=$artifact)"
    }

    data object ProfileMismatch : SemanticModelArtifactValidationResult
    data object ArtifactIdentityMismatch : SemanticModelArtifactValidationResult
    data object IncompleteConversionProvenance : SemanticModelArtifactValidationResult
    data object FileNameMismatch : SemanticModelArtifactValidationResult
    data object OutsideAppPrivateRoot : SemanticModelArtifactValidationResult
    data object Missing : SemanticModelArtifactValidationResult
    data object NotRegularFile : SemanticModelArtifactValidationResult
    data object ArtifactTooLarge : SemanticModelArtifactValidationResult
    data object SizeMismatch : SemanticModelArtifactValidationResult
    data object DigestMismatch : SemanticModelArtifactValidationResult
    data class Failed(val exceptionClass: String) : SemanticModelArtifactValidationResult {
        override fun toString(): String = "Failed(exceptionClass=$exceptionClass)"
    }
}

/**
 * Validates candidate bytes against one separately supplied trusted artifact identity.
 *
 * The candidate spec is evidence to compare, never authority for itself. Production callers must
 * supply a repository-reviewed reproducible identity. The controlled community Q8 fixture can be
 * accepted only through an explicit benchmark-only validator instance.
 */
internal class SemanticModelArtifactValidator(
    appPrivateRoot: File,
    private val trustedIdentity: SemanticModelArtifactIdentity,
    private val acceptance: SemanticModelArtifactAcceptance = SemanticModelArtifactAcceptance.PRODUCTION
) {
    private val canonicalRoot: File = appPrivateRoot.canonicalFile

    init {
        require(SemanticModelProfileV01.matches(trustedIdentity)) {
            "trusted semantic model identity does not match provider profile"
        }
    }

    fun validate(
        candidate: File,
        spec: SemanticModelArtifactSpec
    ): SemanticModelArtifactValidationResult {
        return try {
            val identity = spec.identity
            if (!SemanticModelProfileV01.matches(identity)) {
                return SemanticModelArtifactValidationResult.ProfileMismatch
            }
            if (identity != trustedIdentity) {
                return SemanticModelArtifactValidationResult.ArtifactIdentityMismatch
            }
            if (
                acceptance != SemanticModelArtifactAcceptance.CONTROLLED_BENCHMARK &&
                !trustedIdentity.hasReproducibleConversionProvenance
            ) {
                return SemanticModelArtifactValidationResult.IncompleteConversionProvenance
            }
            if (candidate.name != trustedIdentity.ggufFileName) {
                return SemanticModelArtifactValidationResult.FileNameMismatch
            }

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
            if (
                trustedIdentity.expectedSizeBytes > SemanticModelProfileV01.MAX_ARTIFACT_BYTES ||
                canonicalCandidate.length() > SemanticModelProfileV01.MAX_ARTIFACT_BYTES
            ) {
                return SemanticModelArtifactValidationResult.ArtifactTooLarge
            }
            if (canonicalCandidate.length() != trustedIdentity.expectedSizeBytes) {
                return SemanticModelArtifactValidationResult.SizeMismatch
            }

            val actualDigest = sha256(canonicalCandidate)
            if (actualDigest != trustedIdentity.expectedSha256) {
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
