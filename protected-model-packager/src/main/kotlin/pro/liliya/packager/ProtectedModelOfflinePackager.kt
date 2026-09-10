package pro.liliya.packager

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import pro.liliya.core.protectedmodel.LargeProtectedModelManifest
import pro.liliya.core.protectedmodel.LargeProtectedModelManifestFactory
import pro.liliya.core.protectedmodel.LargeProtectedModelManifestRequest
import pro.liliya.core.protectedmodel.LargeProtectedModelManifestResult
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelPackagingPrimitives
import pro.liliya.core.protectedmodel.LargeProtectedModelPayloadProfile
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentDraft
import pro.liliya.core.protectedmodel.LargeProtectedModelSignedManifest
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.ProtectedModelDekMaterial
import pro.liliya.core.protectedmodel.ProtectedModelEncryptionProfile
import pro.liliya.core.protectedmodel.ProtectedModelFormatVersion
import pro.liliya.core.protectedmodel.ProtectedModelProfileId
import pro.liliya.core.protectedmodel.ProtectedModelReference
import pro.liliya.core.protectedmodel.ProtectedModelSignatureAlgorithm
import pro.liliya.core.protectedmodel.ProtectedModelSignerId
import pro.liliya.core.protectedmodel.SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES
import pro.liliya.core.protectedmodel.SEGMENT_NONCE_SIZE_BYTES
import pro.liliya.core.protectedmodel.SEGMENT_PROTECTED_PAYLOAD_DIGEST_SIZE_BYTES

data class ProtectedModelPackagingContainerBudgets(
    val maxContainerBytes: Long,
    val maxSignatureBytes: Int
) {
    init {
        require(maxContainerBytes > 0L) {
            "packaging container budget must be positive"
        }
        require(maxSignatureBytes >= ED25519_SIGNATURE_BYTES) {
            "packaging signature budget cannot hold Ed25519 signature"
        }
    }
}

fun interface ProtectedModelPackagingSigner {
    fun sign(
        canonicalInput: ByteArray
    ): ProtectedModelPackagingSignResult
}

class ProtectedModelPackagingSignature(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.isNotEmpty()) { "packaging signature must not be empty" }
    }

    fun copyBytes(): ByteArray = value.copyOf()

    override fun toString(): String =
        "ProtectedModelPackagingSignature(<redacted:${value.size} bytes>)"
}

sealed interface ProtectedModelPackagingSignResult {
    data class Signed(
        val signature: ProtectedModelPackagingSignature
    ) : ProtectedModelPackagingSignResult

    data object Rejected : ProtectedModelPackagingSignResult

    data class Failed(
        val throwable: Throwable? = null
    ) : ProtectedModelPackagingSignResult {
        override fun toString(): String =
            "Failed(throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

fun interface ProtectedModelPackagingNonceSource {
    fun next(
        segmentIndex: Int
    ): ByteArray
}

data class ProtectedModelPackagingRequest(
    val source: File,
    val destination: File,
    val model: ProtectedModelReference,
    val modelProfileId: ProtectedModelProfileId,
    val modelDek: ModelDekReference,
    val modelDekMaterial: ProtectedModelDekMaterial,
    val signerId: ProtectedModelSignerId,
    val signer: ProtectedModelPackagingSigner,
    val resourceBudgets: LargeProtectedModelResourceBudgets,
    val packageBudgets: LargeProtectedModelPackageBudgets,
    val containerBudgets: ProtectedModelPackagingContainerBudgets,
    val segmentPlaintextBytes: Int
) {
    init {
        require(segmentPlaintextBytes > 0) {
            "packaging segment size must be positive"
        }
    }

    override fun toString(): String =
        "ProtectedModelPackagingRequest(" +
            "source=<redacted>,destination=<redacted>,model=$model," +
            "modelProfileId=$modelProfileId,modelDek=$modelDek," +
            "modelDekMaterial=<redacted>,signerId=$signerId," +
            "segmentPlaintextBytes=$segmentPlaintextBytes)"
}

enum class ProtectedModelPackagingFailure {
    SOURCE_REJECTED,
    DESTINATION_REJECTED,
    RESOURCE_LIMIT_REJECTED,
    MANIFEST_REJECTED,
    NONCE_REJECTED,
    ENCRYPTION_FAILED,
    SIGNING_REJECTED,
    SIGNING_FAILED,
    SIGNATURE_REJECTED,
    PUBLICATION_UNAVAILABLE,
    PUBLICATION_FAILED,
    IO_FAILED,
    INTERNAL_FAILURE
}

class ProtectedModelPackageDigest(bytes: ByteArray) {
    private val value = bytes.copyOf()

    fun copyBytes(): ByteArray = value.copyOf()

    override fun toString(): String =
        "ProtectedModelPackageDigest(${value.size} bytes)"
}

sealed interface ProtectedModelPackagingResult {
    data class Packaged(
        val destination: File,
        val model: ProtectedModelReference,
        val modelDek: ModelDekReference,
        val signerId: ProtectedModelSignerId,
        val segmentCount: Int,
        val plaintextBytes: Long,
        val packageBytes: Long,
        val packageSha256: ProtectedModelPackageDigest
    ) : ProtectedModelPackagingResult {
        override fun toString(): String =
            "Packaged(destination=<redacted>,model=$model,modelDek=$modelDek," +
                "signerId=$signerId,segmentCount=$segmentCount," +
                "plaintextBytes=$plaintextBytes,packageBytes=$packageBytes," +
                "packageSha256=$packageSha256)"
    }

    data class Rejected(
        val reason: ProtectedModelPackagingFailure
    ) : ProtectedModelPackagingResult
}

/**
 * Offline bounded packager for the frozen LPM1 segmented protected-model representation.
 *
 * The source is processed one segment at a time. The final manifest precedes payload bytes in LPM1,
 * so a same-directory temporary file is populated with a fixed-size canonical-manifest reservation.
 * After all protected segment digests are known, the exact final manifest and Ed25519 signature
 * overwrite only that reservation. The temporary file is published only after file-data sync.
 *
 * Packager != Android Runtime.
 * Packager != License/Authority.
 * Packager != Model Selection.
 * Packager != DEK Provisioning Transport.
 */
class ProtectedModelOfflinePackager(
    private val nonceSource: ProtectedModelPackagingNonceSource =
        SecureRandomProtectedModelPackagingNonceSource()
) {
    fun packageFile(
        request: ProtectedModelPackagingRequest
    ): ProtectedModelPackagingResult {
        val source = canonicalSource(request.source)
            ?: return rejected(ProtectedModelPackagingFailure.SOURCE_REJECTED)
        val destination = canonicalDestination(request.destination)
            ?: return rejected(ProtectedModelPackagingFailure.DESTINATION_REJECTED)

        if (source == destination || destination.exists()) {
            return rejected(ProtectedModelPackagingFailure.DESTINATION_REJECTED)
        }

        val sourceLength = try {
            source.length()
        } catch (_: SecurityException) {
            return rejected(ProtectedModelPackagingFailure.SOURCE_REJECTED)
        }
        val sourceModified = try {
            source.lastModified()
        } catch (_: SecurityException) {
            return rejected(ProtectedModelPackagingFailure.SOURCE_REJECTED)
        }
        if (
            sourceLength <= 0L ||
            sourceLength > request.resourceBudgets.maxTotalPlaintextBytes ||
            request.segmentPlaintextBytes.toLong() >
                request.resourceBudgets.maxSegmentPlaintextBytes ||
            request.segmentPlaintextBytes.toLong() >
                request.resourceBudgets.maxSegmentCiphertextBodyBytes ||
            request.modelProfileId.value.length >
                request.packageBudgets.maxModelProfileIdChars ||
            request.signerId.value.length >
                request.packageBudgets.maxSignerIdChars
        ) {
            return rejected(ProtectedModelPackagingFailure.RESOURCE_LIMIT_REJECTED)
        }

        val segmentSizes = segmentSizes(
            sourceLength = sourceLength,
            segmentPlaintextBytes = request.segmentPlaintextBytes,
            maxSegments = request.resourceBudgets.maxSegmentCount,
            minNonFinalBytes = request.resourceBudgets.minNonFinalSegmentPlaintextBytes
        ) ?: return rejected(ProtectedModelPackagingFailure.RESOURCE_LIMIT_REJECTED)

        val expectedSourceDigest = digestFile(source)
            ?: return rejected(ProtectedModelPackagingFailure.IO_FAILED)

        val nonces = ArrayList<ByteArray>(segmentSizes.size)
        val digests = ArrayList<ByteArray>(segmentSizes.size)
        var temp: File? = null
        var dekBytes: ByteArray? = null
        var preliminaryCanonical: ByteArray? = null
        var finalCanonical: ByteArray? = null
        var signatureInput: ByteArray? = null
        var signatureBytes: ByteArray? = null

        try {
            repeat(segmentSizes.size) { index ->
                val nonce = try {
                    nonceSource.next(index)
                } catch (_: Exception) {
                    return rejected(ProtectedModelPackagingFailure.NONCE_REJECTED)
                }
                if (nonce.size != SEGMENT_NONCE_SIZE_BYTES) {
                    nonce.fill(0)
                    return rejected(ProtectedModelPackagingFailure.NONCE_REJECTED)
                }
                nonces += nonce.copyOf()
                nonce.fill(0)
            }
            if (!uniqueNonces(nonces)) {
                return rejected(ProtectedModelPackagingFailure.NONCE_REJECTED)
            }

            val preliminaryManifest = buildManifest(
                request = request,
                segmentSizes = segmentSizes,
                nonces = nonces,
                digests = List(segmentSizes.size) {
                    ByteArray(SEGMENT_PROTECTED_PAYLOAD_DIGEST_SIZE_BYTES)
                }
            ) ?: return rejected(ProtectedModelPackagingFailure.MANIFEST_REJECTED)

            val preliminarySigned = signedManifest(request, preliminaryManifest)
            val exactPreliminaryCanonical =
                LargeProtectedModelPackagingPrimitives.canonicalSignedManifest(preliminarySigned)
            preliminaryCanonical = exactPreliminaryCanonical
            if (
                exactPreliminaryCanonical.isEmpty() ||
                exactPreliminaryCanonical.size.toLong() >
                    request.packageBudgets.maxCanonicalSignedManifestBytes
            ) {
                return rejected(ProtectedModelPackagingFailure.RESOURCE_LIMIT_REJECTED)
            }

            val predictedContainerBytes = predictedContainerBytes(
                canonicalManifestBytes = exactPreliminaryCanonical.size,
                segmentSizes = segmentSizes
            ) ?: return rejected(ProtectedModelPackagingFailure.RESOURCE_LIMIT_REJECTED)
            if (predictedContainerBytes > request.containerBudgets.maxContainerBytes) {
                return rejected(ProtectedModelPackagingFailure.RESOURCE_LIMIT_REJECTED)
            }

            val parent = destination.parentFile
                ?: return rejected(ProtectedModelPackagingFailure.DESTINATION_REJECTED)
            if (!parent.exists() || !parent.isDirectory) {
                return rejected(ProtectedModelPackagingFailure.DESTINATION_REJECTED)
            }
            temp = File.createTempFile(
                ".${destination.name}.",
                ".lpm.tmp",
                parent
            )

            dekBytes = request.modelDekMaterial.copyBytes()
            if (dekBytes.size != 32) {
                return rejected(ProtectedModelPackagingFailure.ENCRYPTION_FAILED)
            }
            val key = SecretKeySpec(dekBytes, AES_ALGORITHM)

            var manifestOffset = 0L
            var signatureOffset = 0L
            RandomAccessFile(temp, "rw").use { output ->
                output.setLength(0L)
                output.writeInt(CONTAINER_MAGIC)
                output.writeInt(CONTAINER_VERSION)
                output.writeInt(preliminaryCanonical.size)
                manifestOffset = output.filePointer
                output.write(exactPreliminaryCanonical)
                output.writeInt(ED25519_SIGNATURE_BYTES)
                signatureOffset = output.filePointer
                output.write(ByteArray(ED25519_SIGNATURE_BYTES))
                output.writeInt(segmentSizes.size)

                FileInputStream(source).use { input ->
                    val observedSourceDigest = MessageDigest.getInstance(SHA_256)
                    segmentSizes.forEachIndexed { index, segmentSize ->
                        val plaintext = ByteArray(segmentSize)
                        var protectedBytes: ByteArray? = null
                        var aad: ByteArray? = null
                        try {
                            if (!readExact(input, plaintext)) {
                                return rejected(ProtectedModelPackagingFailure.SOURCE_REJECTED)
                            }
                            observedSourceDigest.update(plaintext)

                            val segment = preliminaryManifest.segments()[index]
                            val exactAad =
                                LargeProtectedModelPackagingPrimitives.segmentAad(
                                    preliminarySigned,
                                    segment
                                )
                            aad = exactAad
                            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                            cipher.init(
                                Cipher.ENCRYPT_MODE,
                                key,
                                GCMParameterSpec(
                                    GCM_TAG_BITS,
                                    nonces[index]
                                )
                            )
                            cipher.updateAAD(exactAad)
                            val exactProtected = cipher.doFinal(plaintext)
                            protectedBytes = exactProtected
                            val bodyBytes =
                                exactProtected.size - SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES
                            if (
                                bodyBytes != segmentSize ||
                                exactProtected.size !=
                                segmentSize + SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES
                            ) {
                                return rejected(
                                    ProtectedModelPackagingFailure.ENCRYPTION_FAILED
                                )
                            }

                            val digest = MessageDigest.getInstance(SHA_256)
                                .digest(exactProtected)
                            digests += digest

                            output.writeInt(index)
                            output.writeLong(bodyBytes.toLong())
                            output.writeInt(SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES)
                            output.write(exactProtected, 0, bodyBytes)
                            output.write(
                                exactProtected,
                                bodyBytes,
                                SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES
                            )
                        } catch (_: Exception) {
                            return rejected(ProtectedModelPackagingFailure.ENCRYPTION_FAILED)
                        } finally {
                            plaintext.fill(0)
                            aad?.fill(0)
                            protectedBytes?.fill(0)
                        }
                    }
                    if (input.read() != -1) {
                        return rejected(ProtectedModelPackagingFailure.SOURCE_REJECTED)
                    }

                    val observedDigest = observedSourceDigest.digest()
                    try {
                        if (!MessageDigest.isEqual(expectedSourceDigest, observedDigest)) {
                            return rejected(ProtectedModelPackagingFailure.SOURCE_REJECTED)
                        }
                    } finally {
                        observedDigest.fill(0)
                    }
                }

                if (!stableSource(source, sourceLength, sourceModified)) {
                    return rejected(ProtectedModelPackagingFailure.SOURCE_REJECTED)
                }

                val finalManifest = buildManifest(
                    request = request,
                    segmentSizes = segmentSizes,
                    nonces = nonces,
                    digests = digests
                ) ?: return rejected(ProtectedModelPackagingFailure.MANIFEST_REJECTED)

                val finalSigned = signedManifest(request, finalManifest)
                val exactFinalCanonical =
                    LargeProtectedModelPackagingPrimitives.canonicalSignedManifest(finalSigned)
                finalCanonical = exactFinalCanonical
                val exactPreliminaryCanonical = requireNotNull(preliminaryCanonical)
                if (
                    exactFinalCanonical.size != exactPreliminaryCanonical.size ||
                    exactFinalCanonical.size.toLong() >
                        request.packageBudgets.maxCanonicalSignedManifestBytes
                ) {
                    return rejected(ProtectedModelPackagingFailure.RESOURCE_LIMIT_REJECTED)
                }

                val exactSignatureInput =
                    LargeProtectedModelPackagingPrimitives.signatureInput(finalSigned)
                signatureInput = exactSignatureInput
                if (
                    exactSignatureInput.isEmpty() ||
                    exactSignatureInput.size.toLong() >
                        request.packageBudgets.maxCanonicalSignedManifestBytes
                ) {
                    return rejected(ProtectedModelPackagingFailure.RESOURCE_LIMIT_REJECTED)
                }

                val signed = when (
                    val signResult = try {
                        request.signer.sign(exactSignatureInput)
                    } catch (_: Exception) {
                        return rejected(ProtectedModelPackagingFailure.SIGNING_FAILED)
                    }
                ) {
                    is ProtectedModelPackagingSignResult.Signed -> signResult.signature
                    ProtectedModelPackagingSignResult.Rejected ->
                        return rejected(ProtectedModelPackagingFailure.SIGNING_REJECTED)
                    is ProtectedModelPackagingSignResult.Failed ->
                        return rejected(ProtectedModelPackagingFailure.SIGNING_FAILED)
                }

                val exactSignatureBytes = signed.copyBytes()
                signatureBytes = exactSignatureBytes
                if (
                    exactSignatureBytes.size != ED25519_SIGNATURE_BYTES ||
                    exactSignatureBytes.size > request.containerBudgets.maxSignatureBytes
                ) {
                    return rejected(ProtectedModelPackagingFailure.SIGNATURE_REJECTED)
                }

                output.seek(manifestOffset)
                output.write(exactFinalCanonical)
                output.seek(signatureOffset)
                output.write(exactSignatureBytes)
                output.fd.sync()
            }

            val packageBytes = temp.length()
            if (
                packageBytes <= 0L ||
                packageBytes > request.containerBudgets.maxContainerBytes
            ) {
                return rejected(ProtectedModelPackagingFailure.RESOURCE_LIMIT_REJECTED)
            }

            val packageDigest = digestFile(temp)
                ?: return rejected(ProtectedModelPackagingFailure.IO_FAILED)

            try {
                Files.move(
                    temp.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                packageDigest.fill(0)
                return rejected(
                    ProtectedModelPackagingFailure.PUBLICATION_UNAVAILABLE
                )
            } catch (_: FileAlreadyExistsException) {
                packageDigest.fill(0)
                return rejected(ProtectedModelPackagingFailure.DESTINATION_REJECTED)
            } catch (_: IOException) {
                packageDigest.fill(0)
                return rejected(ProtectedModelPackagingFailure.PUBLICATION_FAILED)
            }

            temp = null
            return ProtectedModelPackagingResult.Packaged(
                destination = destination,
                model = request.model,
                modelDek = request.modelDek,
                signerId = request.signerId,
                segmentCount = segmentSizes.size,
                plaintextBytes = sourceLength,
                packageBytes = packageBytes,
                packageSha256 = ProtectedModelPackageDigest(packageDigest)
            ).also {
                packageDigest.fill(0)
            }
        } catch (_: IOException) {
            return rejected(ProtectedModelPackagingFailure.IO_FAILED)
        } catch (_: Exception) {
            return rejected(ProtectedModelPackagingFailure.INTERNAL_FAILURE)
        } finally {
            nonces.forEach { it.fill(0) }
            digests.forEach { it.fill(0) }
            dekBytes?.fill(0)
            preliminaryCanonical?.fill(0)
            finalCanonical?.fill(0)
            signatureInput?.fill(0)
            signatureBytes?.fill(0)
            expectedSourceDigest.fill(0)
            temp?.delete()
        }
    }

    private fun buildManifest(
        request: ProtectedModelPackagingRequest,
        segmentSizes: List<Int>,
        nonces: List<ByteArray>,
        digests: List<ByteArray>
    ): LargeProtectedModelManifest? {
        if (
            segmentSizes.size != nonces.size ||
            segmentSizes.size != digests.size
        ) {
            return null
        }

        val drafts = segmentSizes.indices.map { index ->
            LargeProtectedModelSegmentDraft(
                index = index,
                plaintextSizeBytes = segmentSizes[index].toLong(),
                ciphertextBodySizeBytes = segmentSizes[index].toLong(),
                nonce = nonces[index],
                protectedPayloadDigest = digests[index]
            )
        }

        val totalPlaintext = segmentSizes.fold(0L) { sum, value ->
            try {
                Math.addExact(sum, value.toLong())
            } catch (_: ArithmeticException) {
                return null
            }
        }
        val totalProtected = try {
            Math.addExact(
                totalPlaintext,
                Math.multiplyExact(
                    segmentSizes.size.toLong(),
                    SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES.toLong()
                )
            )
        } catch (_: ArithmeticException) {
            return null
        }

        return when (
            val result = LargeProtectedModelManifestFactory.create(
                LargeProtectedModelManifestRequest(
                    profile =
                        LargeProtectedModelPayloadProfile
                            .SEGMENTED_AES_256_GCM_SHA256_V1,
                    model = request.model,
                    modelDek = request.modelDek,
                    totalPlaintextSizeBytes = totalPlaintext,
                    totalCiphertextBodySizeBytes = totalPlaintext,
                    totalProtectedPayloadSizeBytes = totalProtected,
                    declaredSegmentCount = segmentSizes.size,
                    segments = drafts
                ),
                request.resourceBudgets
            )
        ) {
            is LargeProtectedModelManifestResult.Accepted -> result.manifest
            is LargeProtectedModelManifestResult.Rejected -> null
        }
    }

    private fun signedManifest(
        request: ProtectedModelPackagingRequest,
        manifest: LargeProtectedModelManifest
    ): LargeProtectedModelSignedManifest =
        LargeProtectedModelSignedManifest(
            formatVersion = ProtectedModelFormatVersion(1),
            modelProfileId = request.modelProfileId,
            payload = manifest,
            encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
            signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
            signerId = request.signerId
        )

    private fun segmentSizes(
        sourceLength: Long,
        segmentPlaintextBytes: Int,
        maxSegments: Int,
        minNonFinalBytes: Long
    ): List<Int>? {
        if (
            sourceLength <= 0L ||
            segmentPlaintextBytes <= 0 ||
            segmentPlaintextBytes.toLong() < minNonFinalBytes
        ) {
            return null
        }

        val count = try {
            Math.floorDiv(
                Math.addExact(sourceLength, segmentPlaintextBytes.toLong() - 1L),
                segmentPlaintextBytes.toLong()
            )
        } catch (_: ArithmeticException) {
            return null
        }
        if (count <= 0L || count > maxSegments.toLong() || count > Int.MAX_VALUE) {
            return null
        }

        val result = ArrayList<Int>(count.toInt())
        var remaining = sourceLength
        repeat(count.toInt()) {
            val size = minOf(remaining, segmentPlaintextBytes.toLong())
            if (size <= 0L || size > Int.MAX_VALUE.toLong()) return null
            result += size.toInt()
            remaining -= size
        }
        return result.takeIf { remaining == 0L }
    }

    private fun predictedContainerBytes(
        canonicalManifestBytes: Int,
        segmentSizes: List<Int>
    ): Long? {
        var total = 0L
        fun add(value: Long): Boolean = try {
            total = Math.addExact(total, value)
            true
        } catch (_: ArithmeticException) {
            false
        }

        if (!add(Int.SIZE_BYTES.toLong())) return null // magic
        if (!add(Int.SIZE_BYTES.toLong())) return null // version
        if (!add(Int.SIZE_BYTES.toLong())) return null // manifest length
        if (!add(canonicalManifestBytes.toLong())) return null
        if (!add(Int.SIZE_BYTES.toLong())) return null // signature length
        if (!add(ED25519_SIGNATURE_BYTES.toLong())) return null
        if (!add(Int.SIZE_BYTES.toLong())) return null // segment count

        for (segmentSize in segmentSizes) {
            if (!add(Int.SIZE_BYTES.toLong())) return null // index
            if (!add(Long.SIZE_BYTES.toLong())) return null // body length
            if (!add(Int.SIZE_BYTES.toLong())) return null // tag length
            if (!add(segmentSize.toLong())) return null
            if (!add(SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES.toLong())) return null
        }

        return total
    }

    private fun uniqueNonces(nonces: List<ByteArray>): Boolean {
        for (index in nonces.indices) {
            for (other in 0 until index) {
                if (MessageDigest.isEqual(nonces[index], nonces[other])) {
                    return false
                }
            }
        }
        return true
    }

    private fun readExact(
        input: FileInputStream,
        target: ByteArray
    ): Boolean {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) return false
            if (count == 0) continue
            offset += count
        }
        return true
    }

    private fun canonicalSource(file: File): File? = try {
        file.canonicalFile.takeIf { it.isFile }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun canonicalDestination(file: File): File? = try {
        file.canonicalFile
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun stableSource(
        source: File,
        expectedLength: Long,
        expectedModified: Long
    ): Boolean = try {
        source.isFile &&
            source.length() == expectedLength &&
            source.lastModified() == expectedModified
    } catch (_: SecurityException) {
        false
    }

    private fun digestFile(file: File): ByteArray? = try {
        val digest = MessageDigest.getInstance(SHA_256)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DIGEST_BUFFER_BYTES)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            } finally {
                buffer.fill(0)
            }
        }
        digest.digest()
    } catch (_: IOException) {
        null
    }

    private fun rejected(
        reason: ProtectedModelPackagingFailure
    ): ProtectedModelPackagingResult.Rejected =
        ProtectedModelPackagingResult.Rejected(reason)

    private class SecureRandomProtectedModelPackagingNonceSource(
        private val random: SecureRandom = SecureRandom()
    ) : ProtectedModelPackagingNonceSource {
        override fun next(segmentIndex: Int): ByteArray =
            ByteArray(SEGMENT_NONCE_SIZE_BYTES).also(random::nextBytes)
    }

    private companion object {
        const val CONTAINER_MAGIC = 0x4C504D31 // LPM1
        const val CONTAINER_VERSION = 1
        const val ED25519_SIGNATURE_BYTES = 64
        const val AES_ALGORITHM = "AES"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SHA_256 = "SHA-256"
        const val DIGEST_BUFFER_BYTES = 64 * 1024
    }
}

private const val ED25519_SIGNATURE_BYTES = 64
