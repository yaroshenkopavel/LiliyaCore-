package pro.liliya.core.license

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.time.Instant

@JvmInline
value class LicenseServiceDurableStateGeneration(val value: Long) {
    init {
        require(value > 0L) { "license service durable state generation must be positive" }
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class LicenseServiceDurableBackendRevision(val value: Long) {
    init {
        require(value > 0L) { "license service durable backend revision must be positive" }
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class LicenseServiceDurableStateSchemaVersion(val value: Int) {
    init {
        require(value > 0) { "license service durable state schema version must be positive" }
    }

    override fun toString(): String = value.toString()
}

class LicenseServiceDurableStateSnapshot(
    states: List<LicenseServiceSecurityState>,
    val generation: LicenseServiceDurableStateGeneration,
    val backendRevision: LicenseServiceDurableBackendRevision,
    val schemaVersion: LicenseServiceDurableStateSchemaVersion = LicenseServiceDurableStateSchemaVersion(1)
) {
    val states: List<LicenseServiceSecurityState> = states.sortedWith(
        compareBy(
            { it.scope.productId.value },
            { it.scope.subject.value }
        )
    )

    init {
        require(this.states.isNotEmpty()) { "license service durable state must not be empty" }
        require(this.states.map { it.scope }.toSet().size == this.states.size) {
            "license service durable state scopes must be unique"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is LicenseServiceDurableStateSnapshot &&
            states == other.states &&
            generation == other.generation &&
            backendRevision == other.backendRevision &&
            schemaVersion == other.schemaVersion

    override fun hashCode(): Int {
        var result = states.hashCode()
        result = 31 * result + generation.hashCode()
        result = 31 * result + backendRevision.hashCode()
        result = 31 * result + schemaVersion.hashCode()
        return result
    }

    override fun toString(): String =
        "LicenseServiceDurableStateSnapshot(scopeCount=${states.size}, schemaVersion=$schemaVersion, " +
            "generation=$generation, backendRevision=$backendRevision, scopes=<redacted>)"
}

class LicenseServiceDurableStatePayload private constructor(
    private val bytes: ByteArray
) {
    val size: Int get() = bytes.size

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is LicenseServiceDurableStatePayload && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String =
        "LicenseServiceDurableStatePayload(size=${bytes.size}, content=<redacted>)"

    companion object {
        fun of(bytes: ByteArray): LicenseServiceDurableStatePayload {
            require(bytes.isNotEmpty()) { "license service durable state payload must not be empty" }
            return LicenseServiceDurableStatePayload(bytes.copyOf())
        }
    }
}

enum class LicenseServiceDurableStateCodecRejection {
    BOUNDS_EXCEEDED,
    MALFORMED,
    UNSUPPORTED_VERSION,
    NON_CANONICAL,
    DUPLICATE_SCOPE
}

sealed interface LicenseServiceDurableStateEncodeResult {
    data class Encoded(val payload: LicenseServiceDurableStatePayload) :
        LicenseServiceDurableStateEncodeResult

    data class Rejected(val reason: LicenseServiceDurableStateCodecRejection) :
        LicenseServiceDurableStateEncodeResult
}

sealed interface LicenseServiceDurableStateDecodeResult {
    data class Decoded(val snapshot: LicenseServiceDurableStateSnapshot) :
        LicenseServiceDurableStateDecodeResult

    data class Rejected(val reason: LicenseServiceDurableStateCodecRejection) :
        LicenseServiceDurableStateDecodeResult
}

/**
 * Canonical Android-free codec for authenticated Licensing Service security-state persistence.
 *
 * The bytes produced here are plaintext canonical state. They are not durable, authentic, encrypted,
 * entitled or authorized merely because this codec accepted them. Slice 4B/4C must bind these bytes,
 * the exact schema version, durable generation and backend revision into the dedicated licensing-state
 * authenticated-encryption domain before a durable commit may be published in memory.
 */
object LicenseServiceDurableStateCanonicalCodec {
    private const val MAGIC = 0x4C534434 // LSD4
    private const val VERSION = 1
    private const val PURPOSE = "LICENSE_SERVICE_SECURITY_STATE"

    internal const val MAX_SCOPE_COUNT = 1_024
    internal const val MAX_TEXT_BYTES = 4_096
    internal const val MAX_PAYLOAD_BYTES = 1_048_576

    private const val FLAG_REVOCATION = 1
    private const val FLAG_REPLAY = 1 shl 1
    private const val FLAG_SERVER_TIME = 1 shl 2
    private const val KNOWN_FLAGS = FLAG_REVOCATION or FLAG_REPLAY or FLAG_SERVER_TIME

    fun encode(snapshot: LicenseServiceDurableStateSnapshot): LicenseServiceDurableStateEncodeResult {
        if (snapshot.schemaVersion.value != VERSION) {
            return rejectedEncode(LicenseServiceDurableStateCodecRejection.UNSUPPORTED_VERSION)
        }
        if (snapshot.states.size > MAX_SCOPE_COUNT) {
            return rejectedEncode(LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED)
        }

        val budget = EncodedBudget(MAX_PAYLOAD_BYTES)
        if (!budget.add(Int.SIZE_BYTES * 3 + Long.SIZE_BYTES * 2)) {
            return rejectedEncode(LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED)
        }
        val purposeBytes = boundedUtf8Length(PURPOSE) ?: return rejectedEncode(
            LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED
        )
        if (!budget.add(Int.SIZE_BYTES + purposeBytes)) {
            return rejectedEncode(LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED)
        }

        for (state in snapshot.states) {
            val productBytes = boundedUtf8Length(state.scope.productId.value)
                ?: return rejectedEncode(LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED)
            val subjectBytes = boundedUtf8Length(state.scope.subject.value)
                ?: return rejectedEncode(LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED)
            var entryBytes = Int.SIZE_BYTES + productBytes + Int.SIZE_BYTES + subjectBytes + Int.SIZE_BYTES
            if (state.revocationEpoch != null) entryBytes += Long.SIZE_BYTES
            if (state.replaySequence != null) entryBytes += Long.SIZE_BYTES
            if (state.serverTime != null) entryBytes += Long.SIZE_BYTES + Int.SIZE_BYTES
            if (!budget.add(entryBytes)) {
                return rejectedEncode(LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED)
            }
        }

        val output = ByteArrayOutputStream(budget.used)
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(snapshot.schemaVersion.value)
            data.writeBoundedString(PURPOSE)
            data.writeLong(snapshot.generation.value)
            data.writeLong(snapshot.backendRevision.value)
            data.writeInt(snapshot.states.size)
            snapshot.states.forEach { state ->
                data.writeBoundedString(state.scope.productId.value)
                data.writeBoundedString(state.scope.subject.value)
                var flags = 0
                if (state.revocationEpoch != null) flags = flags or FLAG_REVOCATION
                if (state.replaySequence != null) flags = flags or FLAG_REPLAY
                if (state.serverTime != null) flags = flags or FLAG_SERVER_TIME
                data.writeInt(flags)
                state.revocationEpoch?.let { data.writeLong(it.value) }
                state.replaySequence?.let { data.writeLong(it.value) }
                state.serverTime?.let {
                    data.writeLong(it.epochSecond)
                    data.writeInt(it.nano)
                }
            }
        }
        val bytes = output.toByteArray()
        if (bytes.size != budget.used || bytes.size > MAX_PAYLOAD_BYTES) {
            return rejectedEncode(LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED)
        }
        return LicenseServiceDurableStateEncodeResult.Encoded(
            LicenseServiceDurableStatePayload.of(bytes)
        )
    }

    fun decode(payload: LicenseServiceDurableStatePayload): LicenseServiceDurableStateDecodeResult {
        if (payload.size > MAX_PAYLOAD_BYTES) {
            return rejectedDecode(LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED)
        }

        val original = payload.copyBytes()
        return try {
            val input = ByteArrayInputStream(original)
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) {
                return rejectedDecode(LicenseServiceDurableStateCodecRejection.MALFORMED)
            }
            val schemaVersion = LicenseServiceDurableStateSchemaVersion(data.readInt())
            if (schemaVersion.value != VERSION) {
                return rejectedDecode(LicenseServiceDurableStateCodecRejection.UNSUPPORTED_VERSION)
            }
            if (data.readBoundedString(input) != PURPOSE) {
                return rejectedDecode(LicenseServiceDurableStateCodecRejection.MALFORMED)
            }

            val generation = LicenseServiceDurableStateGeneration(data.readLong())
            val backendRevision = LicenseServiceDurableBackendRevision(data.readLong())
            val count = data.readInt()
            if (count !in 1..MAX_SCOPE_COUNT) {
                return rejectedDecode(LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED)
            }

            val states = ArrayList<LicenseServiceSecurityState>(count)
            val scopes = HashSet<LicenseServiceSecurityScope>(count)
            repeat(count) {
                val scope = LicenseServiceSecurityScope(
                    productId = LicenseProductId(data.readBoundedString(input)),
                    subject = LicenseSubject(data.readBoundedString(input))
                )
                if (!scopes.add(scope)) {
                    return rejectedDecode(LicenseServiceDurableStateCodecRejection.DUPLICATE_SCOPE)
                }

                val flags = data.readInt()
                if (flags == 0 || flags and KNOWN_FLAGS != flags) {
                    return rejectedDecode(LicenseServiceDurableStateCodecRejection.MALFORMED)
                }
                val revocation = if (flags and FLAG_REVOCATION != 0) {
                    LicenseRevocationEpoch(data.readLong())
                } else {
                    null
                }
                val replay = if (flags and FLAG_REPLAY != 0) {
                    LicenseReplaySequence(data.readLong())
                } else {
                    null
                }
                val serverTime = if (flags and FLAG_SERVER_TIME != 0) {
                    val epochSecond = data.readLong()
                    val nano = data.readInt()
                    if (nano !in 0..999_999_999) {
                        return rejectedDecode(LicenseServiceDurableStateCodecRejection.MALFORMED)
                    }
                    Instant.ofEpochSecond(epochSecond, nano.toLong())
                } else {
                    null
                }
                states += LicenseServiceSecurityState(scope, revocation, replay, serverTime)
            }
            if (input.available() != 0) {
                return rejectedDecode(LicenseServiceDurableStateCodecRejection.NON_CANONICAL)
            }

            val snapshot = LicenseServiceDurableStateSnapshot(
                states = states,
                generation = generation,
                backendRevision = backendRevision,
                schemaVersion = schemaVersion
            )
            val reencoded = when (val encoded = encode(snapshot)) {
                is LicenseServiceDurableStateEncodeResult.Encoded -> encoded.payload.copyBytes()
                is LicenseServiceDurableStateEncodeResult.Rejected -> {
                    return rejectedDecode(encoded.reason)
                }
            }
            if (!reencoded.contentEquals(original)) {
                return rejectedDecode(LicenseServiceDurableStateCodecRejection.NON_CANONICAL)
            }
            LicenseServiceDurableStateDecodeResult.Decoded(snapshot)
        } catch (_: EOFException) {
            rejectedDecode(LicenseServiceDurableStateCodecRejection.MALFORMED)
        } catch (_: IllegalArgumentException) {
            rejectedDecode(LicenseServiceDurableStateCodecRejection.MALFORMED)
        } catch (_: RuntimeException) {
            rejectedDecode(LicenseServiceDurableStateCodecRejection.MALFORMED)
        }
    }

    private fun DataOutputStream.writeBoundedString(value: String) {
        val expectedSize = boundedUtf8Length(value)
            ?: throw IllegalArgumentException("license service durable state text exceeds codec bounds")
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size == expectedSize) { "license service durable state text encoding mismatch" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedString(input: ByteArrayInputStream): String {
        val length = readInt()
        if (length !in 1..MAX_TEXT_BYTES || length > input.available()) throw EOFException()
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    /** Returns UTF-8 byte length without allocating the encoded byte array. */
    private fun boundedUtf8Length(value: String): Int? {
        if (value.isEmpty()) return null
        var size = 0
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            val added = when {
                ch.code <= 0x7F -> 1
                ch.code <= 0x7FF -> 2
                Character.isHighSurrogate(ch) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        return null
                    }
                    index += 1
                    4
                }
                Character.isLowSurrogate(ch) -> return null
                else -> 3
            }
            if (size > MAX_TEXT_BYTES - added) return null
            size += added
            index += 1
        }
        return size
    }

    private fun rejectedEncode(
        reason: LicenseServiceDurableStateCodecRejection
    ): LicenseServiceDurableStateEncodeResult = LicenseServiceDurableStateEncodeResult.Rejected(reason)

    private fun rejectedDecode(
        reason: LicenseServiceDurableStateCodecRejection
    ): LicenseServiceDurableStateDecodeResult = LicenseServiceDurableStateDecodeResult.Rejected(reason)

    private class EncodedBudget(private val maximum: Int) {
        var used: Int = 0
            private set

        fun add(bytes: Int): Boolean {
            if (bytes < 0 || used > maximum - bytes) return false
            used += bytes
            return true
        }
    }
}
