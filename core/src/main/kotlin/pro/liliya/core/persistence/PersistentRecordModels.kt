package pro.liliya.core.persistence

import java.time.Instant

@JvmInline
value class PersistentStoreId(val value: String) {
    init { require(value.isNotBlank()) { "persistent store id must not be blank" } }
}

@JvmInline
value class PersistentEntityId(val value: String) {
    init { require(value.isNotBlank()) { "persistent entity id must not be blank" } }
}

@JvmInline
value class PersistentSchemaId(val value: String) {
    init { require(value.isNotBlank()) { "persistent schema id must not be blank" } }
}

@JvmInline
value class PersistentSchemaVersion(val value: Int) {
    init { require(value > 0) { "persistent schema version must be positive" } }
}

@JvmInline
value class PersistentGeneration(val value: Long) {
    init { require(value > 0) { "persistent generation must be positive" } }
}

class PersistentPayload(bytes: ByteArray) {
    private val value = bytes.copyOf()

    fun copyBytes(): ByteArray = value.copyOf()
    val size: Int get() = value.size

    override fun equals(other: Any?): Boolean =
        other is PersistentPayload && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "PersistentPayload(<redacted:${value.size} bytes>)"
}

data class PersistentRecord(
    val id: PersistentEntityId,
    val schemaId: PersistentSchemaId,
    val schemaVersion: PersistentSchemaVersion,
    val payload: PersistentPayload,
    val createdAt: Instant
) {
    override fun toString(): String =
        "PersistentRecord(id=$id, schemaId=$schemaId, schemaVersion=$schemaVersion, payload=<redacted:${payload.size} bytes>, createdAt=$createdAt)"
}

data class PersistentRecordSnapshot(
    val record: PersistentRecord,
    val generation: PersistentGeneration
)

interface PersistentRecordOwnership {
    val record: PersistentRecord
    val generation: PersistentGeneration
    fun remove(): PersistentMutationResult
}

sealed interface PersistentInstallResult {
    data class Installed(val ownership: PersistentRecordOwnership) : PersistentInstallResult
    data class Rejected(val reason: String) : PersistentInstallResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentInstallResult
}

sealed interface PersistentMutationResult {
    data object Committed : PersistentMutationResult
    data class Rejected(val reason: String) : PersistentMutationResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentMutationResult
}

sealed interface PersistentStoreOpenResult {
    data class Opened(val store: PersistentRecordStore) : PersistentStoreOpenResult
    data object Corrupt : PersistentStoreOpenResult
    data class Incompatible(val reason: String) : PersistentStoreOpenResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentStoreOpenResult
}
