package pro.liliya.core.license

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

class LicenseServiceSecurityScope(
    val productId: LicenseProductId,
    val subject: LicenseSubject
) {
    override fun equals(other: Any?): Boolean =
        other is LicenseServiceSecurityScope &&
            productId == other.productId &&
            subject == other.subject

    override fun hashCode(): Int = 31 * productId.hashCode() + subject.hashCode()

    override fun toString(): String =
        "LicenseServiceSecurityScope(productId=$productId, subject=<redacted>)"
}

class LicenseServiceSecurityState(
    val scope: LicenseServiceSecurityScope,
    val revocationEpoch: LicenseRevocationEpoch? = null,
    val replaySequence: LicenseReplaySequence? = null,
    val serverTime: Instant? = null
) {
    init {
        require(
            revocationEpoch != null || replaySequence != null || serverTime != null
        ) {
            "license service security state must contain at least one security signal"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is LicenseServiceSecurityState &&
            scope == other.scope &&
            revocationEpoch == other.revocationEpoch &&
            replaySequence == other.replaySequence &&
            serverTime == other.serverTime

    override fun hashCode(): Int {
        var result = scope.hashCode()
        result = 31 * result + (revocationEpoch?.hashCode() ?: 0)
        result = 31 * result + (replaySequence?.hashCode() ?: 0)
        result = 31 * result + (serverTime?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "LicenseServiceSecurityState(scope=$scope, revocationEpoch=$revocationEpoch, " +
            "replaySequence=$replaySequence, serverTimePresent=${serverTime != null})"
}

sealed interface LicenseServiceSecurityStateDecodeResult {
    data class Decoded(val state: LicenseServiceSecurityState) :
        LicenseServiceSecurityStateDecodeResult

    data object Corrupt : LicenseServiceSecurityStateDecodeResult
}

object LicenseServiceSecurityStateCanonicalCodec {
    private const val MAGIC = 0x4C535331

    fun encode(state: LicenseServiceSecurityState): LicenseServiceOpaquePayload =
        LicenseServiceOpaquePayload.of(
            ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(MAGIC)
                    data.writeString(state.scope.productId.value)
                    data.writeString(state.scope.subject.value)
                    data.writeBoolean(state.revocationEpoch != null)
                    state.revocationEpoch?.let { data.writeLong(it.value) }
                    data.writeBoolean(state.replaySequence != null)
                    state.replaySequence?.let { data.writeLong(it.value) }
                    data.writeBoolean(state.serverTime != null)
                    state.serverTime?.let { data.writeInstant(it) }
                }
                output.toByteArray()
            }
        )

    fun decode(payload: LicenseServiceOpaquePayload): LicenseServiceSecurityStateDecodeResult {
        return try {
            val input = ByteArrayInputStream(payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return LicenseServiceSecurityStateDecodeResult.Corrupt

            val state = LicenseServiceSecurityState(
                scope = LicenseServiceSecurityScope(
                    productId = LicenseProductId(data.readString(input)),
                    subject = LicenseSubject(data.readString(input))
                ),
                revocationEpoch = if (data.readBoolean()) {
                    LicenseRevocationEpoch(data.readLong())
                } else {
                    null
                },
                replaySequence = if (data.readBoolean()) {
                    LicenseReplaySequence(data.readLong())
                } else {
                    null
                },
                serverTime = if (data.readBoolean()) data.readInstant() else null
            )

            if (input.available() != 0) return LicenseServiceSecurityStateDecodeResult.Corrupt
            LicenseServiceSecurityStateDecodeResult.Decoded(state)
        } catch (_: EOFException) {
            LicenseServiceSecurityStateDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            LicenseServiceSecurityStateDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            LicenseServiceSecurityStateDecodeResult.Corrupt
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(input: ByteArrayInputStream): String {
        val length = readInt()
        if (length <= 0 || length > input.available()) throw EOFException()
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataOutputStream.writeInstant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataInputStream.readInstant(): Instant =
        Instant.ofEpochSecond(readLong(), readInt().toLong())
}

class LicenseServiceAuthenticationTranscript private constructor(
    private val bytes: ByteArray
) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is LicenseServiceAuthenticationTranscript && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String =
        "LicenseServiceAuthenticationTranscript(size=${bytes.size}, content=<redacted>)"

    companion object {
        internal fun from(envelope: LicenseServiceStateEnvelope): LicenseServiceAuthenticationTranscript =
            LicenseServiceAuthenticationTranscript(
                ByteArrayOutputStream().use { output ->
                    DataOutputStream(output).use { data ->
                        data.writeInt(0x4C535354)
                        data.writeLong(envelope.protocolVersion.value)
                        data.writeString(envelope.purpose.name)
                        data.writeString(envelope.profile.value)
                        data.writeString(envelope.signingKeyId.value)
                        val payload = envelope.payload.copyBytes()
                        data.writeInt(payload.size)
                        data.write(payload)
                    }
                    output.toByteArray()
                }
            )

        private fun DataOutputStream.writeString(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }
    }
}

class LicenseServiceTrustedVerificationKey private constructor(
    val keyId: LicenseKeyId,
    val profile: LicenseServiceEvidenceProfile,
    private val material: ByteArray
) {
    fun copyMaterial(): ByteArray = material.copyOf()

    override fun toString(): String =
        "LicenseServiceTrustedVerificationKey(keyId=$keyId, profile=$profile, material=<redacted>)"

    companion object {
        fun of(
            keyId: LicenseKeyId,
            profile: LicenseServiceEvidenceProfile,
            material: ByteArray
        ): LicenseServiceTrustedVerificationKey {
            require(material.isNotEmpty()) {
                "license service verification key material must not be empty"
            }
            return LicenseServiceTrustedVerificationKey(
                keyId = keyId,
                profile = profile,
                material = material.copyOf()
            )
        }
    }
}

fun interface LicenseServiceTrustedKeyResolver {
    fun resolve(
        keyId: LicenseKeyId,
        profile: LicenseServiceEvidenceProfile
    ): LicenseServiceTrustedVerificationKey?
}

fun interface LicenseServiceProofVerifier {
    fun verify(
        profile: LicenseServiceEvidenceProfile,
        key: LicenseServiceTrustedVerificationKey,
        transcript: LicenseServiceAuthenticationTranscript,
        proof: LicenseServiceAuthenticationProof
    ): Boolean
}

sealed interface LicenseServiceStateVerificationResult {
    class Verified internal constructor(
        val state: LicenseServiceSecurityState,
        val envelope: LicenseServiceStateEnvelope
    ) : LicenseServiceStateVerificationResult {
        override fun toString(): String =
            "LicenseServiceStateVerificationResult.Verified(scope=${state.scope}, " +
                "profile=${envelope.profile}, signingKeyId=${envelope.signingKeyId})"
    }

    data class Rejected(val reason: LicenseServiceStateVerificationRejection) :
        LicenseServiceStateVerificationResult
}

enum class LicenseServiceStateVerificationRejection {
    UNSUPPORTED_PROTOCOL_VERSION,
    UNSUPPORTED_PURPOSE,
    UNSUPPORTED_PROFILE,
    UNKNOWN_KEY_ID,
    TRUSTED_KEY_MISMATCH,
    INVALID_PROOF,
    INVALID_CANONICAL_PAYLOAD
}

class LicenseServiceStateVerifier(
    private val supportedProtocolVersion: LicenseServiceProtocolVersion,
    supportedPurposes: Set<LicenseServiceEvidencePurpose>,
    supportedProfiles: Set<LicenseServiceEvidenceProfile>,
    private val trustedKeys: LicenseServiceTrustedKeyResolver,
    private val proofVerifier: LicenseServiceProofVerifier
) {
    private val supportedPurposes = supportedPurposes.toSet()
    private val supportedProfiles = supportedProfiles.toSet()

    init {
        require(this.supportedPurposes.isNotEmpty()) {
            "license service verifier must support at least one evidence purpose"
        }
        require(this.supportedProfiles.isNotEmpty()) {
            "license service verifier must support at least one evidence profile"
        }
    }

    fun verify(envelope: LicenseServiceStateEnvelope): LicenseServiceStateVerificationResult {
        if (envelope.protocolVersion != supportedProtocolVersion) {
            return rejected(LicenseServiceStateVerificationRejection.UNSUPPORTED_PROTOCOL_VERSION)
        }
        if (envelope.purpose !in supportedPurposes) {
            return rejected(LicenseServiceStateVerificationRejection.UNSUPPORTED_PURPOSE)
        }
        if (envelope.profile !in supportedProfiles) {
            return rejected(LicenseServiceStateVerificationRejection.UNSUPPORTED_PROFILE)
        }

        val key = trustedKeys.resolve(envelope.signingKeyId, envelope.profile)
            ?: return rejected(LicenseServiceStateVerificationRejection.UNKNOWN_KEY_ID)
        if (key.keyId != envelope.signingKeyId || key.profile != envelope.profile) {
            return rejected(LicenseServiceStateVerificationRejection.TRUSTED_KEY_MISMATCH)
        }

        val transcript = LicenseServiceAuthenticationTranscript.from(envelope)
        if (!proofVerifier.verify(envelope.profile, key, transcript, envelope.proof)) {
            return rejected(LicenseServiceStateVerificationRejection.INVALID_PROOF)
        }

        val state = when (val decoded = LicenseServiceSecurityStateCanonicalCodec.decode(envelope.payload)) {
            is LicenseServiceSecurityStateDecodeResult.Decoded -> decoded.state
            LicenseServiceSecurityStateDecodeResult.Corrupt ->
                return rejected(LicenseServiceStateVerificationRejection.INVALID_CANONICAL_PAYLOAD)
        }

        return LicenseServiceStateVerificationResult.Verified(state, envelope)
    }

    private fun rejected(reason: LicenseServiceStateVerificationRejection) =
        LicenseServiceStateVerificationResult.Rejected(reason)
}

/** Deterministic test/dev proof profile only; never a production cryptographic primitive. */
object LicenseServiceDigestTestProofVerifier : LicenseServiceProofVerifier {
    private val testProfile = LicenseServiceEvidenceProfile("TEST-SERVICE-SHA256")

    override fun verify(
        profile: LicenseServiceEvidenceProfile,
        key: LicenseServiceTrustedVerificationKey,
        transcript: LicenseServiceAuthenticationTranscript,
        proof: LicenseServiceAuthenticationProof
    ): Boolean {
        if (profile != testProfile || key.profile != testProfile) return false
        val expected = digest(key, transcript)
        return MessageDigest.isEqual(expected, proof.copyBytes())
    }

    fun signForTest(
        key: LicenseServiceTrustedVerificationKey,
        envelopeWithoutProof: LicenseServiceStateEnvelope
    ): LicenseServiceAuthenticationProof {
        require(key.keyId == envelopeWithoutProof.signingKeyId) {
            "test service key id must match envelope key id"
        }
        require(key.profile == envelopeWithoutProof.profile) {
            "test service key profile must match envelope profile"
        }
        return LicenseServiceAuthenticationProof.of(
            digest(key, LicenseServiceAuthenticationTranscript.from(envelopeWithoutProof))
        )
    }

    private fun digest(
        key: LicenseServiceTrustedVerificationKey,
        transcript: LicenseServiceAuthenticationTranscript
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(key.copyMaterial())
        digest.update(byteArrayOf(0))
        digest.update(transcript.copyBytes())
        return digest.digest()
    }
}
