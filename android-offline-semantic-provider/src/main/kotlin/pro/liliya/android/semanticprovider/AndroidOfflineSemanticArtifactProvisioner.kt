package pro.liliya.android.semanticprovider

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class AndroidOfflineSemanticArtifactProvisionMode {
    INSTALL_ONLY,
    EXPLICIT_REPLACE
}

sealed interface AndroidOfflineSemanticArtifactProvisionResult {
    data object Provisioned : AndroidOfflineSemanticArtifactProvisionResult
    data object AlreadyProvisioned : AndroidOfflineSemanticArtifactProvisionResult
    data object ExistingPublishedStateRejected : AndroidOfflineSemanticArtifactProvisionResult
    data object SourceRejected : AndroidOfflineSemanticArtifactProvisionResult
    data object PublicationFailed : AndroidOfflineSemanticArtifactProvisionResult
}

/**
 * Blocking local provisioner for the exact repository-approved semantic ONNX bundle.
 *
 * This class has no network capability. The caller owns acquisition of the input streams.
 * Provisioning must be scheduled off the Android main/UI thread.
 */
class AndroidOfflineSemanticArtifactProvisioner {

    fun provision(
        appPrivateRoot: File,
        encoderInput: InputStream,
        tokenizerInput: InputStream,
        mode: AndroidOfflineSemanticArtifactProvisionMode =
            AndroidOfflineSemanticArtifactProvisionMode.INSTALL_ONLY
    ): AndroidOfflineSemanticArtifactProvisionResult {
        val root = try {
            appPrivateRoot.canonicalFile
        } catch (_: IOException) {
            return AndroidOfflineSemanticArtifactProvisionResult.PublicationFailed
        }

        if (!root.exists() && !root.mkdirs()) {
            return AndroidOfflineSemanticArtifactProvisionResult.PublicationFailed
        }
        if (!root.isDirectory) {
            return AndroidOfflineSemanticArtifactProvisionResult.PublicationFailed
        }

        val identity = productionSemanticModelIdentity()
        val encoderFile = File(root, identity.modelFileName)
        val tokenizerFile = File(root, identity.tokenizerFileName)

        when (existingBundleState(root, encoderFile, identity)) {
            ExistingBundleState.EXACT ->
                return AndroidOfflineSemanticArtifactProvisionResult.AlreadyProvisioned
            ExistingBundleState.PRESENT_NON_EXACT ->
                if (mode != AndroidOfflineSemanticArtifactProvisionMode.EXPLICIT_REPLACE) {
                    return AndroidOfflineSemanticArtifactProvisionResult.ExistingPublishedStateRejected
                }
            ExistingBundleState.MISSING -> Unit
        }

        val encoderTemp = File(root, identity.modelFileName + TEMP_SUFFIX)
        val tokenizerTemp = File(root, identity.tokenizerFileName + TEMP_SUFFIX)
        encoderTemp.delete()
        tokenizerTemp.delete()

        val encoderPrepared = prepare(
            input = encoderInput,
            temp = encoderTemp,
            expectedBytes = identity.expectedSizeBytes,
            expectedSha256 = identity.expectedSha256
        )
        if (!encoderPrepared) {
            encoderTemp.delete()
            tokenizerTemp.delete()
            return AndroidOfflineSemanticArtifactProvisionResult.SourceRejected
        }

        val tokenizerPrepared = prepare(
            input = tokenizerInput,
            temp = tokenizerTemp,
            expectedBytes = identity.tokenizerExpectedSizeBytes,
            expectedSha256 = identity.tokenizerExpectedSha256
        )
        if (!tokenizerPrepared) {
            encoderTemp.delete()
            tokenizerTemp.delete()
            return AndroidOfflineSemanticArtifactProvisionResult.SourceRejected
        }

        return try {
            atomicReplace(encoderTemp, encoderFile)
            atomicReplace(tokenizerTemp, tokenizerFile)

            when (
                SemanticModelArtifactValidator(
                    appPrivateRoot = root,
                    trustedIdentity = identity
                ).validate(
                    candidate = encoderFile,
                    spec = SemanticModelArtifactSpec(identity)
                )
            ) {
                is SemanticModelArtifactValidationResult.Validated ->
                    AndroidOfflineSemanticArtifactProvisionResult.Provisioned
                else ->
                    AndroidOfflineSemanticArtifactProvisionResult.PublicationFailed
            }
        } catch (_: IOException) {
            AndroidOfflineSemanticArtifactProvisionResult.PublicationFailed
        } finally {
            encoderTemp.delete()
            tokenizerTemp.delete()
        }
    }

    private fun existingBundleState(
        root: File,
        encoderFile: File,
        identity: SemanticModelArtifactIdentity
    ): ExistingBundleState {
        val tokenizerFile = File(root, identity.tokenizerFileName)
        if (!encoderFile.exists() && !tokenizerFile.exists()) {
            return ExistingBundleState.MISSING
        }

        return when (
            SemanticModelArtifactValidator(
                appPrivateRoot = root,
                trustedIdentity = identity
            ).validate(
                candidate = encoderFile,
                spec = SemanticModelArtifactSpec(identity)
            )
        ) {
            is SemanticModelArtifactValidationResult.Validated -> ExistingBundleState.EXACT
            else -> ExistingBundleState.PRESENT_NON_EXACT
        }
    }

    private fun prepare(
        input: InputStream,
        temp: File,
        expectedBytes: Long,
        expectedSha256: String
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val buffer = ByteArray(BUFFER_BYTES)

        return try {
            FileOutputStream(temp, false).use { output ->
                while (written < expectedBytes) {
                    val remaining = expectedBytes - written
                    val requested = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, requested)
                    if (read < 0) return false
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    written += read.toLong()
                }

                // One additional byte proves the source is not oversized.
                if (input.read() != -1) {
                    return false
                }

                output.flush()
                output.fd.sync()
            }

            if (written != expectedBytes) return false
            val actual = digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            actual == expectedSha256
        } catch (_: IOException) {
            false
        } finally {
            buffer.fill(0)
            if (!temp.isFile || temp.length() != expectedBytes) {
                temp.delete()
            }
        }
    }

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException("atomic semantic artifact publication unavailable", e)
        }
    }

    private enum class ExistingBundleState {
        MISSING,
        EXACT,
        PRESENT_NON_EXACT
    }

    private companion object {
        const val TEMP_SUFFIX = ".provisioning.tmp"
        const val BUFFER_BYTES = 64 * 1024
    }
}
