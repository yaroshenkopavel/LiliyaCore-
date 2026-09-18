package pro.liliya.android.persistence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.core.persistence.IndexedPersistentRecordReadBackend
import pro.liliya.core.persistence.PersistentBackendCommitResult
import pro.liliya.core.persistence.PersistentBackendEntry
import pro.liliya.core.persistence.PersistentBackendEntryLoadResult
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentBackendMetadata
import pro.liliya.core.persistence.PersistentBackendMetadataLoadResult
import pro.liliya.core.persistence.PersistentBackendPage
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentBackendPageLoadResult
import pro.liliya.core.persistence.PersistentBackendPageOrder
import pro.liliya.core.persistence.PersistentBackendPageRequest
import pro.liliya.core.persistence.PersistentBackendState
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordSnapshot
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId

/**
 * Indexed Android persistence backend.
 *
 * Unlike legacy LDP1 this backend does not encode every store into one bounded snapshot file.
 * Records are kept as independently addressable SQLite rows and commits update only rows whose
 * authenticated persistent representation changed. The frozen PersistentRecordBackend contract is
 * intentionally preserved for Stage 1; lazy Core reads are a separate follow-up seam.
 *
 * Existing LDP1 files are imported transactionally on first access and are left untouched as
 * recovery evidence. Once an indexed store row exists, the database is authoritative and the
 * legacy snapshot is never replayed over newer state.
 */
class AndroidIndexedPersistentRecordBackend private constructor(
    private val root: File
) : IndexedPersistentRecordReadBackend {

    override fun load(storeId: PersistentStoreId): PersistentBackendLoadResult =
        synchronized(this) {
            try {
                openDatabase().use { db ->
                    when (val imported = importLegacyIfRequired(db, storeId)) {
                        LegacyImportResult.Ready -> Unit
                        LegacyImportResult.Missing -> return@synchronized PersistentBackendLoadResult.Missing
                        LegacyImportResult.Corrupt -> return@synchronized PersistentBackendLoadResult.Corrupt
                        LegacyImportResult.Incompatible ->
                            return@synchronized PersistentBackendLoadResult.Incompatible(
                                "unsupported durable persistence format"
                            )
                        is LegacyImportResult.Failed ->
                            return@synchronized PersistentBackendLoadResult.Failed(
                                "indexed durable persistence legacy import failed",
                                imported.throwable
                            )
                    }

                    val header = readHeader(db, storeId)
                        ?: return@synchronized PersistentBackendLoadResult.Missing
                    val entries = LinkedHashMap<PersistentEntityId, PersistentBackendEntry>()
                    db.rawQuery(
                        "SELECT entity_id,generation,schema_id,schema_version,created_epoch,created_nano,payload,record_hash " +
                            "FROM records WHERE store_id=? ORDER BY entity_id",
                        arrayOf(storeId.value)
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val entityId = PersistentEntityId(cursor.getString(0))
                            if (entries.containsKey(entityId)) {
                                return@synchronized PersistentBackendLoadResult.Corrupt
                            }
                            val generation = PersistentGeneration(cursor.getLong(1))
                            val schemaId = PersistentSchemaId(cursor.getString(2))
                            val schemaVersion = PersistentSchemaVersion(cursor.getInt(3))
                            val createdAt = try {
                                Instant.ofEpochSecond(cursor.getLong(4), cursor.getInt(5).toLong())
                            } catch (_: RuntimeException) {
                                return@synchronized PersistentBackendLoadResult.Corrupt
                            }
                            val payload = cursor.getBlob(6)
                            val record = PersistentRecord(
                                id = entityId,
                                schemaId = schemaId,
                                schemaVersion = schemaVersion,
                                payload = PersistentPayload(payload),
                                createdAt = createdAt
                            )
                            val backendEntry = PersistentBackendEntry(generation, record)
                            val expectedHash = cursor.getString(7)
                            val actualHash = recordHash(entityId, backendEntry, payload)
                            payload.fill(0)
                            if (expectedHash != actualHash) {
                                return@synchronized PersistentBackendLoadResult.Corrupt
                            }
                            entries[entityId] = backendEntry
                        }
                    }
                    if (entries.size != header.entryCount) {
                        return@synchronized PersistentBackendLoadResult.Corrupt
                    }
                    if (entries.values.any { it.generation.value > header.highWatermark }) {
                        return@synchronized PersistentBackendLoadResult.Corrupt
                    }
                    if (entries.values.map { it.generation }.toSet().size != entries.size) {
                        return@synchronized PersistentBackendLoadResult.Corrupt
                    }
                    PersistentBackendLoadResult.Loaded(
                        revision = header.revision,
                        state = PersistentBackendState(
                            storeId = storeId,
                            highWatermark = header.highWatermark,
                            entries = entries
                        )
                    )
                }
            } catch (_: IndexedDatabaseIncompatibleException) {
                PersistentBackendLoadResult.Incompatible("unsupported indexed durable persistence format")
            } catch (_: IllegalArgumentException) {
                PersistentBackendLoadResult.Corrupt
            } catch (e: SQLiteException) {
                PersistentBackendLoadResult.Corrupt
            } catch (e: IOException) {
                PersistentBackendLoadResult.Failed("indexed durable persistence load failed", e)
            } catch (e: RuntimeException) {
                PersistentBackendLoadResult.Failed("indexed durable persistence load failed", e)
            }
        }

    override fun loadMetadata(
        storeId: PersistentStoreId
    ): PersistentBackendMetadataLoadResult = synchronized(this) {
        try {
            openDatabase().use { db ->
                when (val imported = importLegacyIfRequired(db, storeId)) {
                    LegacyImportResult.Ready -> Unit
                    LegacyImportResult.Missing ->
                        return@synchronized PersistentBackendMetadataLoadResult.Missing
                    LegacyImportResult.Corrupt ->
                        return@synchronized PersistentBackendMetadataLoadResult.Corrupt
                    LegacyImportResult.Incompatible ->
                        return@synchronized PersistentBackendMetadataLoadResult.Incompatible(
                            "unsupported durable persistence format"
                        )
                    is LegacyImportResult.Failed ->
                        return@synchronized PersistentBackendMetadataLoadResult.Failed(
                            "indexed durable persistence legacy import failed",
                            imported.throwable
                        )
                }
                val header = readHeader(db, storeId)
                    ?: return@synchronized PersistentBackendMetadataLoadResult.Missing
                PersistentBackendMetadataLoadResult.Loaded(
                    PersistentBackendMetadata(
                        revision = header.revision,
                        highWatermark = header.highWatermark,
                        entryCount = header.entryCount.toLong()
                    )
                )
            }
        } catch (_: IndexedDatabaseIncompatibleException) {
            PersistentBackendMetadataLoadResult.Incompatible(
                "unsupported indexed durable persistence format"
            )
        } catch (_: IllegalArgumentException) {
            PersistentBackendMetadataLoadResult.Corrupt
        } catch (_: SQLiteException) {
            PersistentBackendMetadataLoadResult.Corrupt
        } catch (e: IOException) {
            PersistentBackendMetadataLoadResult.Failed(
                "indexed durable persistence metadata load failed",
                e
            )
        } catch (e: RuntimeException) {
            PersistentBackendMetadataLoadResult.Failed(
                "indexed durable persistence metadata load failed",
                e
            )
        }
    }

    override fun loadEntry(
        storeId: PersistentStoreId,
        entityId: PersistentEntityId
    ): PersistentBackendEntryLoadResult = synchronized(this) {
        try {
            openDatabase().use { db ->
                when (val imported = importLegacyIfRequired(db, storeId)) {
                    LegacyImportResult.Ready -> Unit
                    LegacyImportResult.Missing ->
                        return@synchronized PersistentBackendEntryLoadResult.Missing
                    LegacyImportResult.Corrupt ->
                        return@synchronized PersistentBackendEntryLoadResult.Corrupt
                    LegacyImportResult.Incompatible ->
                        return@synchronized PersistentBackendEntryLoadResult.Incompatible(
                            "unsupported durable persistence format"
                        )
                    is LegacyImportResult.Failed ->
                        return@synchronized PersistentBackendEntryLoadResult.Failed(
                            "indexed durable persistence legacy import failed",
                            imported.throwable
                        )
                }
                val header = readHeader(db, storeId)
                    ?: return@synchronized PersistentBackendEntryLoadResult.Missing
                db.rawQuery(
                    "SELECT entity_id,generation,schema_id,schema_version,created_epoch,created_nano,payload,record_hash " +
                        "FROM records WHERE store_id=? AND entity_id=? LIMIT 1",
                    arrayOf(storeId.value, entityId.value)
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        return@synchronized PersistentBackendEntryLoadResult.Missing
                    }
                    val snapshot = decodeSnapshot(cursor, header.highWatermark)
                    PersistentBackendEntryLoadResult.Loaded(snapshot)
                }
            }
        } catch (_: IndexedDatabaseIncompatibleException) {
            PersistentBackendEntryLoadResult.Incompatible(
                "unsupported indexed durable persistence format"
            )
        } catch (_: IllegalArgumentException) {
            PersistentBackendEntryLoadResult.Corrupt
        } catch (_: SQLiteException) {
            PersistentBackendEntryLoadResult.Corrupt
        } catch (e: IOException) {
            PersistentBackendEntryLoadResult.Failed(
                "indexed durable persistence entry load failed",
                e
            )
        } catch (e: RuntimeException) {
            PersistentBackendEntryLoadResult.Failed(
                "indexed durable persistence entry load failed",
                e
            )
        }
    }

    override fun loadPage(
        storeId: PersistentStoreId,
        request: PersistentBackendPageRequest
    ): PersistentBackendPageLoadResult = synchronized(this) {
        try {
            openDatabase().use { db ->
                when (val imported = importLegacyIfRequired(db, storeId)) {
                    LegacyImportResult.Ready -> Unit
                    LegacyImportResult.Missing ->
                        return@synchronized PersistentBackendPageLoadResult.Missing
                    LegacyImportResult.Corrupt ->
                        return@synchronized PersistentBackendPageLoadResult.Corrupt
                    LegacyImportResult.Incompatible ->
                        return@synchronized PersistentBackendPageLoadResult.Incompatible(
                            "unsupported durable persistence format"
                        )
                    is LegacyImportResult.Failed ->
                        return@synchronized PersistentBackendPageLoadResult.Failed(
                            "indexed durable persistence legacy import failed",
                            imported.throwable
                        )
                }
                val header = readHeader(db, storeId)
                    ?: return@synchronized PersistentBackendPageLoadResult.Missing

                val ascending = request.order == PersistentBackendPageOrder.OLDEST_FIRST
                val where = ArrayList<String>()
                val args = ArrayList<String>()
                where += "store_id=?"
                args += storeId.value
                request.schemaId?.let {
                    where += "schema_id=?"
                    args += it.value
                }
                request.cursorExclusive?.let { cursor ->
                    val op = if (ascending) ">" else "<"
                    where +=
                        "(created_epoch $op ? OR (created_epoch=? AND " +
                        "(created_nano $op ? OR (created_nano=? AND entity_id $op ?))))"
                    args += cursor.createdAt.epochSecond.toString()
                    args += cursor.createdAt.epochSecond.toString()
                    args += cursor.createdAt.nano.toString()
                    args += cursor.createdAt.nano.toString()
                    args += cursor.entityId.value
                }
                val direction = if (ascending) "ASC" else "DESC"
                val query =
                    "SELECT entity_id,generation,schema_id,schema_version,created_epoch,created_nano,payload,record_hash " +
                        "FROM records WHERE " + where.joinToString(" AND ") +
                        " ORDER BY created_epoch $direction,created_nano $direction,entity_id $direction LIMIT ?"
                args += (request.limit + 1).toString()

                val loaded = ArrayList<PersistentRecordSnapshot>(request.limit + 1)
                db.rawQuery(query, args.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        loaded += decodeSnapshot(cursor, header.highWatermark)
                    }
                }
                val hasMore = loaded.size > request.limit
                if (hasMore) loaded.removeAt(loaded.lastIndex)
                val nextCursor = if (hasMore && loaded.isNotEmpty()) {
                    val last = loaded.last().record
                    PersistentBackendPageCursor(last.createdAt, last.id)
                } else {
                    null
                }
                PersistentBackendPageLoadResult.Loaded(
                    PersistentBackendPage(loaded, nextCursor)
                )
            }
        } catch (_: IndexedDatabaseIncompatibleException) {
            PersistentBackendPageLoadResult.Incompatible(
                "unsupported indexed durable persistence format"
            )
        } catch (_: IllegalArgumentException) {
            PersistentBackendPageLoadResult.Corrupt
        } catch (_: SQLiteException) {
            PersistentBackendPageLoadResult.Corrupt
        } catch (e: IOException) {
            PersistentBackendPageLoadResult.Failed(
                "indexed durable persistence page load failed",
                e
            )
        } catch (e: RuntimeException) {
            PersistentBackendPageLoadResult.Failed(
                "indexed durable persistence page load failed",
                e
            )
        }
    }

    override fun commit(
        storeId: PersistentStoreId,
        expectedRevision: Long,
        state: PersistentBackendState
    ): PersistentBackendCommitResult = synchronized(this) {
        if (expectedRevision < 0L || state.storeId != storeId || !validState(state)) {
            return@synchronized PersistentBackendCommitResult.Failed(
                "indexed durable persistence commit rejected"
            )
        }

        try {
            openDatabase().use { db ->
                db.beginTransaction()
                try {
                    when (val imported = importLegacyIfRequired(db, storeId)) {
                        LegacyImportResult.Ready,
                        LegacyImportResult.Missing -> Unit
                        LegacyImportResult.Corrupt,
                        LegacyImportResult.Incompatible ->
                            return@synchronized PersistentBackendCommitResult.Failed(
                                "indexed durable persistence current state is not writable"
                            )
                        is LegacyImportResult.Failed ->
                            return@synchronized PersistentBackendCommitResult.Failed(
                                "indexed durable persistence legacy import failed",
                                imported.throwable
                            )
                    }

                    val current = readHeader(db, storeId)
                    if (current != null && !validateCurrentStore(db, storeId, current)) {
                        return@synchronized PersistentBackendCommitResult.Failed(
                            "indexed durable persistence current state is corrupt"
                        )
                    }
                    val currentRevision = current?.revision ?: 0L
                    if (currentRevision != expectedRevision) {
                        return@synchronized PersistentBackendCommitResult.Conflict
                    }
                    if (currentRevision == Long.MAX_VALUE) {
                        return@synchronized PersistentBackendCommitResult.Failed(
                            "indexed durable persistence revision overflow"
                        )
                    }

                    val currentRows = LinkedHashMap<String, ExistingRow>()
                    db.rawQuery(
                        "SELECT entity_id,generation,record_hash FROM records WHERE store_id=?",
                        arrayOf(storeId.value)
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            currentRows[cursor.getString(0)] =
                                ExistingRow(cursor.getLong(1), cursor.getString(2))
                        }
                    }

                    val desiredIds = state.entries.keys.map { it.value }.toHashSet()
                    for (entityId in currentRows.keys) {
                        if (entityId !in desiredIds) {
                            db.delete(
                                "records",
                                "store_id=? AND entity_id=?",
                                arrayOf(storeId.value, entityId)
                            )
                        }
                    }

                    for ((entityId, entry) in state.entries) {
                        val payload = entry.record.payload.copyBytes()
                        try {
                            require(payload.size <= MAX_RECORD_PAYLOAD_BYTES) {
                                "indexed durable persistence payload exceeds per-entry limit"
                            }
                            val hash = recordHash(entityId, entry, payload)
                            val existing = currentRows[entityId.value]
                            if (existing != ExistingRow(entry.generation.value, hash)) {
                                db.delete(
                                    "records",
                                    "store_id=? AND entity_id=?",
                                    arrayOf(storeId.value, entityId.value)
                                )
                                val values = ContentValues().apply {
                                    put("store_id", storeId.value)
                                    put("entity_id", entityId.value)
                                    put("generation", entry.generation.value)
                                    put("schema_id", entry.record.schemaId.value)
                                    put("schema_version", entry.record.schemaVersion.value)
                                    put("created_epoch", entry.record.createdAt.epochSecond)
                                    put("created_nano", entry.record.createdAt.nano)
                                    put("payload", payload)
                                    put("record_hash", hash)
                                }
                                if (db.insertOrThrow("records", null, values) == -1L) {
                                    throw SQLiteException("indexed record insert failed")
                                }
                            }
                        } finally {
                            payload.fill(0)
                        }
                    }

                    val nextRevision = currentRevision + 1L
                    val storeValues = ContentValues().apply {
                        put("store_id", storeId.value)
                        put("revision", nextRevision)
                        put("high_watermark", state.highWatermark)
                        put("entry_count", state.entries.size)
                        put("header_hash", headerHash(storeId, nextRevision, state.highWatermark, state.entries.size))
                    }
                    db.insertWithOnConflict(
                        "stores",
                        null,
                        storeValues,
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                    db.setTransactionSuccessful()
                    PersistentBackendCommitResult.Committed(nextRevision)
                } finally {
                    db.endTransaction()
                }
            }
        } catch (e: IndexedDatabaseIncompatibleException) {
            PersistentBackendCommitResult.Failed("indexed durable persistence format is incompatible", e)
        } catch (e: SQLiteException) {
            PersistentBackendCommitResult.Failed("indexed durable persistence commit failed", e)
        } catch (e: RuntimeException) {
            PersistentBackendCommitResult.Failed("indexed durable persistence commit failed", e)
        }
    }

    private fun openDatabase(): SQLiteDatabase {
        val database = SQLiteDatabase.openOrCreateDatabase(File(root, DATABASE_FILE), null)
        database.execSQL("PRAGMA synchronous=FULL")
        val existingVersion = database.version
        if (existingVersion != 0 && existingVersion != DATABASE_VERSION) {
            database.close()
            throw IndexedDatabaseIncompatibleException(existingVersion)
        }
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS stores(" +
                "store_id TEXT PRIMARY KEY NOT NULL," +
                "revision INTEGER NOT NULL," +
                "high_watermark INTEGER NOT NULL," +
                "entry_count INTEGER NOT NULL," +
                "header_hash TEXT NOT NULL)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS records(" +
                "store_id TEXT NOT NULL," +
                "entity_id TEXT NOT NULL," +
                "generation INTEGER NOT NULL," +
                "schema_id TEXT NOT NULL," +
                "schema_version INTEGER NOT NULL," +
                "created_epoch INTEGER NOT NULL," +
                "created_nano INTEGER NOT NULL," +
                "payload BLOB NOT NULL," +
                "record_hash TEXT NOT NULL," +
                "PRIMARY KEY(store_id,entity_id)," +
                "UNIQUE(store_id,generation))"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS records_store_created " +
                "ON records(store_id,created_epoch,created_nano,entity_id)"
        )
        if (existingVersion == 0) database.version = DATABASE_VERSION
        return database
    }

    private fun importLegacyIfRequired(
        db: SQLiteDatabase,
        storeId: PersistentStoreId
    ): LegacyImportResult {
        if (readHeader(db, storeId) != null) return LegacyImportResult.Ready
        val legacy = legacyFile(storeId)
        if (!legacy.exists()) {
            return if (hasRecordRows(db, storeId)) {
                LegacyImportResult.Corrupt
            } else {
                LegacyImportResult.Missing
            }
        }
        if (!legacy.isFile || legacy.length() <= 0L) return LegacyImportResult.Corrupt

        val decoded = try {
            AndroidPersistentStateCodec.decode(legacy.readBytes())
        } catch (e: IOException) {
            return LegacyImportResult.Failed(e)
        }
        val ready = when (decoded) {
            is AndroidPersistentStateCodec.DecodeResult.Decoded -> decoded
            AndroidPersistentStateCodec.DecodeResult.Corrupt -> return LegacyImportResult.Corrupt
            AndroidPersistentStateCodec.DecodeResult.Incompatible ->
                return LegacyImportResult.Incompatible
        }
        if (ready.state.storeId != storeId || !validState(ready.state)) {
            return LegacyImportResult.Corrupt
        }

        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        return try {
            if (readHeader(db, storeId) == null) {
                writeImportedState(db, ready.revision, ready.state)
            }
            if (ownsTransaction) db.setTransactionSuccessful()
            LegacyImportResult.Ready
        } catch (e: SQLiteException) {
            LegacyImportResult.Failed(e)
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    private fun hasRecordRows(db: SQLiteDatabase, storeId: PersistentStoreId): Boolean =
        db.rawQuery(
            "SELECT 1 FROM records WHERE store_id=? LIMIT 1",
            arrayOf(storeId.value)
        ).use { cursor -> cursor.moveToFirst() }

    private fun writeImportedState(
        db: SQLiteDatabase,
        revision: Long,
        state: PersistentBackendState
    ) {
        require(revision > 0L)
        for ((entityId, entry) in state.entries) {
            val payload = entry.record.payload.copyBytes()
            try {
                val values = ContentValues().apply {
                    put("store_id", state.storeId.value)
                    put("entity_id", entityId.value)
                    put("generation", entry.generation.value)
                    put("schema_id", entry.record.schemaId.value)
                    put("schema_version", entry.record.schemaVersion.value)
                    put("created_epoch", entry.record.createdAt.epochSecond)
                    put("created_nano", entry.record.createdAt.nano)
                    put("payload", payload)
                    put("record_hash", recordHash(entityId, entry, payload))
                }
                db.insertOrThrow("records", null, values)
            } finally {
                payload.fill(0)
            }
        }
        val values = ContentValues().apply {
            put("store_id", state.storeId.value)
            put("revision", revision)
            put("high_watermark", state.highWatermark)
            put("entry_count", state.entries.size)
            put("header_hash", headerHash(state.storeId, revision, state.highWatermark, state.entries.size))
        }
        db.insertOrThrow("stores", null, values)
    }

    private fun readHeader(db: SQLiteDatabase, storeId: PersistentStoreId): Header? =
        db.rawQuery(
            "SELECT revision,high_watermark,entry_count,header_hash FROM stores WHERE store_id=?",
            arrayOf(storeId.value)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else {
                val revision = cursor.getLong(0)
                val highWatermark = cursor.getLong(1)
                val entryCount = cursor.getInt(2)
                val expectedHash = cursor.getString(3)
                if (revision <= 0L || highWatermark < 0L || entryCount < 0 ||
                    expectedHash != headerHash(storeId, revision, highWatermark, entryCount)
                ) {
                    throw SQLiteException("indexed durable persistence header integrity failed")
                }
                Header(revision, highWatermark, entryCount)
            }
        }

    private fun decodeSnapshot(
        cursor: android.database.Cursor,
        highWatermark: Long
    ): PersistentRecordSnapshot {
        val entityId = PersistentEntityId(cursor.getString(0))
        val generation = PersistentGeneration(cursor.getLong(1))
        require(generation.value <= highWatermark) {
            "indexed durable persistence generation exceeds high watermark"
        }
        val schemaId = PersistentSchemaId(cursor.getString(2))
        val schemaVersion = PersistentSchemaVersion(cursor.getInt(3))
        val createdAt = Instant.ofEpochSecond(cursor.getLong(4), cursor.getInt(5).toLong())
        val payload = cursor.getBlob(6)
        try {
            val record = PersistentRecord(
                id = entityId,
                schemaId = schemaId,
                schemaVersion = schemaVersion,
                payload = PersistentPayload(payload),
                createdAt = createdAt
            )
            val entry = PersistentBackendEntry(generation, record)
            require(cursor.getString(7) == recordHash(entityId, entry, payload)) {
                "indexed durable persistence record integrity failed"
            }
            return PersistentRecordSnapshot(record, generation)
        } finally {
            payload.fill(0)
        }
    }

    private fun validateCurrentStore(
        db: SQLiteDatabase,
        storeId: PersistentStoreId,
        header: Header
    ): Boolean {
        var count = 0
        val generations = HashSet<Long>()
        return try {
            db.rawQuery(
                "SELECT entity_id,generation,schema_id,schema_version,created_epoch,created_nano,payload,record_hash " +
                    "FROM records WHERE store_id=?",
                arrayOf(storeId.value)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val entityId = PersistentEntityId(cursor.getString(0))
                    val generation = PersistentGeneration(cursor.getLong(1))
                    if (!generations.add(generation.value) ||
                        generation.value > header.highWatermark
                    ) return false
                    val schemaId = PersistentSchemaId(cursor.getString(2))
                    val schemaVersion = PersistentSchemaVersion(cursor.getInt(3))
                    val createdAt = Instant.ofEpochSecond(cursor.getLong(4), cursor.getInt(5).toLong())
                    val payload = cursor.getBlob(6)
                    try {
                        val entry = PersistentBackendEntry(
                            generation,
                            PersistentRecord(
                                id = entityId,
                                schemaId = schemaId,
                                schemaVersion = schemaVersion,
                                payload = PersistentPayload(payload),
                                createdAt = createdAt
                            )
                        )
                        if (cursor.getString(7) != recordHash(entityId, entry, payload)) return false
                    } finally {
                        payload.fill(0)
                    }
                    count += 1
                }
            }
            count == header.entryCount
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun validState(state: PersistentBackendState): Boolean {
        if (state.highWatermark < 0L) return false
        if (state.entries.any { (id, entry) -> id != entry.record.id }) return false
        if (state.entries.values.any {
                it.generation.value <= 0L || it.generation.value > state.highWatermark
            }
        ) return false
        if (state.entries.values.map { it.generation }.toSet().size != state.entries.size) {
            return false
        }
        return state.entries.all { (id, entry) ->
            identifierValid(id.value) &&
                identifierValid(entry.record.schemaId.value) &&
                entry.record.schemaVersion.value > 0
        }
    }

    private fun identifierValid(value: String): Boolean {
        val size = value.toByteArray(StandardCharsets.UTF_8).size
        return value.isNotBlank() && size in 1..MAX_IDENTIFIER_BYTES
    }

    private fun headerHash(
        storeId: PersistentStoreId,
        revision: Long,
        highWatermark: Long,
        entryCount: Int
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateString(digest, storeId.value)
        updateLong(digest, revision)
        updateLong(digest, highWatermark)
        updateInt(digest, entryCount)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun recordHash(
        entityId: PersistentEntityId,
        entry: PersistentBackendEntry,
        payload: ByteArray
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateString(digest, entityId.value)
        updateLong(digest, entry.generation.value)
        updateString(digest, entry.record.schemaId.value)
        updateInt(digest, entry.record.schemaVersion.value)
        updateLong(digest, entry.record.createdAt.epochSecond)
        updateInt(digest, entry.record.createdAt.nano)
        updateInt(digest, payload.size)
        digest.update(payload)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateString(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        updateInt(digest, bytes.size)
        digest.update(bytes)
    }

    private fun updateLong(digest: MessageDigest, value: Long) {
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
    }

    private fun updateInt(digest: MessageDigest, value: Int) {
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
    }

    private fun legacyFile(storeId: PersistentStoreId): File =
        File(root, namespaceDigest(storeId.value) + LEGACY_FILE_SUFFIX)

    private fun namespaceDigest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private class IndexedDatabaseIncompatibleException(version: Int) :
        IllegalStateException("unsupported indexed durable persistence version: " + version)

    private data class Header(val revision: Long, val highWatermark: Long, val entryCount: Int)
    private data class ExistingRow(val generation: Long, val hash: String)

    private sealed interface LegacyImportResult {
        data object Ready : LegacyImportResult
        data object Missing : LegacyImportResult
        data object Corrupt : LegacyImportResult
        data object Incompatible : LegacyImportResult
        data class Failed(val throwable: Throwable) : LegacyImportResult
    }

    companion object {
        private const val DEFAULT_DIRECTORY = "liliya-durable-persistence-v1"
        private const val DATABASE_FILE = "liliya-indexed-v2.sqlite3"
        private const val DATABASE_VERSION = 2
        private const val LEGACY_FILE_SUFFIX = ".lpr"
        private const val MAX_IDENTIFIER_BYTES = 1_024
        private const val MAX_RECORD_PAYLOAD_BYTES = 1 * 1024 * 1024

        fun create(
            context: Context,
            directoryName: String = DEFAULT_DIRECTORY
        ): AndroidIndexedPersistentRecordBackend {
            require(directoryName.isNotBlank()) {
                "indexed durable persistence directory name must not be blank"
            }
            require(!directoryName.contains('/') && !directoryName.contains('\\')) {
                "indexed durable persistence directory name must be a single app-private segment"
            }
            val appRoot = context.applicationContext.filesDir.canonicalFile
            val root = File(appRoot, directoryName).canonicalFile
            require(root.parentFile == appRoot) {
                "indexed durable persistence root must be directly inside app-private files"
            }
            if (!root.exists()) check(root.mkdirs()) {
                "indexed durable persistence app-private root could not be created"
            }
            check(root.isDirectory) {
                "indexed durable persistence app-private root is not a directory"
            }
            return AndroidIndexedPersistentRecordBackend(root)
        }
    }
}
