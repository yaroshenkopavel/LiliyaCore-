package pro.liliya.core.license

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.time.Instant

sealed interface LicenseEntitlementDecodeResult {
    data class Decoded(val entitlement: LicenseEntitlement) : LicenseEntitlementDecodeResult
    data object Corrupt : LicenseEntitlementDecodeResult
}

object LicenseEntitlementCanonicalCodec {
    private const val MAGIC = 0x4C494331

    fun encode(entitlement: LicenseEntitlement): LicenseCanonicalPayload =
        LicenseCanonicalPayload.of(
            ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(MAGIC)
                    data.writeString(entitlement.id.value)
                    data.writeString(entitlement.subject.value)
                    data.writeString(entitlement.productId.value)
                    val features = entitlement.features.sortedBy { it.value }
                    data.writeInt(features.size)
                    features.forEach { data.writeString(it.value) }
                    data.writeLong(entitlement.version.value)
                    data.writeString(entitlement.signingKeyId.value)
                    data.writeInstant(entitlement.issuedAt)
                    data.writeInstant(entitlement.notBefore)
                    data.writeNullableInstant(entitlement.expiresAt)
                    data.writeNullableInstant(entitlement.offlineLeaseUntil)
                    data.writeLong(entitlement.revocationEpoch.value)
                    data.writeBoolean(entitlement.replaySequence != null)
                    entitlement.replaySequence?.let { data.writeLong(it.value) }
                }
                output.toByteArray()
            }
        )

    fun decode(payload: LicenseCanonicalPayload): LicenseEntitlementDecodeResult = try {
        val input = ByteArrayInputStream(payload.copyBytes())
        val data = DataInputStream(input)
        if (data.readInt() != MAGIC) return LicenseEntitlementDecodeResult.Corrupt
        val id = LicenseId(data.readString(input))
        val subject = LicenseSubject(data.readString(input))
        val productId = LicenseProductId(data.readString(input))
        val featureCount = data.readInt()
        if (featureCount <= 0 || featureCount > input.available()) {
            return LicenseEntitlementDecodeResult.Corrupt
        }
        val features = LinkedHashSet<LicenseFeature>()
        repeat(featureCount) {
            if (!features.add(LicenseFeature(data.readString(input)))) {
                return LicenseEntitlementDecodeResult.Corrupt
            }
        }
        val entitlement = LicenseEntitlement(
            id = id,
            subject = subject,
            productId = productId,
            features = features,
            version = LicenseVersion(data.readLong()),
            signingKeyId = LicenseKeyId(data.readString(input)),
            issuedAt = data.readInstant(),
            notBefore = data.readInstant(),
            expiresAt = data.readNullableInstant(),
            offlineLeaseUntil = data.readNullableInstant(),
            revocationEpoch = LicenseRevocationEpoch(data.readLong()),
            replaySequence = if (data.readBoolean()) {
                LicenseReplaySequence(data.readLong())
            } else {
                null
            }
        )
        if (input.available() != 0) return LicenseEntitlementDecodeResult.Corrupt
        LicenseEntitlementDecodeResult.Decoded(entitlement)
    } catch (_: EOFException) {
        LicenseEntitlementDecodeResult.Corrupt
    } catch (_: IllegalArgumentException) {
        LicenseEntitlementDecodeResult.Corrupt
    } catch (_: RuntimeException) {
        LicenseEntitlementDecodeResult.Corrupt
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

    private fun DataOutputStream.writeNullableInstant(value: Instant?) {
        writeBoolean(value != null)
        if (value != null) writeInstant(value)
    }

    private fun DataInputStream.readNullableInstant(): Instant? =
        if (readBoolean()) readInstant() else null
}
