package pro.liliya.app

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

class ProductionAndroidLocalModelSelectionContractTest {
    private val roots = mutableListOf<File>()

    @After
    fun cleanup() {
        ProductionAndroidLocalModelSelection.clearForTests()
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun exact_user_selected_bytes_are_imported_and_owned() {
        val root = Files.createTempDirectory("liliya-model-selection").toFile()
            .also(roots::add)
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)

        val result = ProductionAndroidLocalModelSelection.importSelected(root, maxImportBytes = 1024) {
            ByteArrayInputStream(bytes)
        }

        val selected = assertIs<ProductionAndroidLocalModelSelectionResult.Selected>(result)
        assertTrue(selected.file.parentFile == root)
        assertTrue(selected.file.name.startsWith("model-"))
        assertTrue(selected.file.name.endsWith(".bin"))
        assertContentEquals(bytes, selected.file.readBytes())
        assertTrue(ProductionAndroidLocalModelSelection.current() === selected.file)
    }

    @Test
    fun zero_progress_bulk_reads_fall_back_to_single_byte_progress_without_stalling() {
        val root = Files.createTempDirectory("liliya-model-selection-zero-progress").toFile()
            .also(roots::add)
        val bytes = byteArrayOf(11, 22, 33, 44, 55)
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }

        val result = ProductionAndroidLocalModelSelection.importSelected(
            directory = root,
            maxImportBytes = 1024
        ) {
            input
        }

        val selected = assertIs<ProductionAndroidLocalModelSelectionResult.Selected>(result)
        assertContentEquals(bytes, selected.file.readBytes())
    }

    @Test
    fun restore_cleans_only_stale_owned_temp_files_after_interrupted_publication() {
        val root = Files.createTempDirectory("liliya-model-selection-stale-temp").toFile()
            .also(roots::add)
        val selected = assertIs<ProductionAndroidLocalModelSelectionResult.Selected>(
            ProductionAndroidLocalModelSelection.importSelected(root, maxImportBytes = 1024) {
                ByteArrayInputStream(byteArrayOf(1, 3, 5, 7))
            }
        ).file
        val staleImport = File(root, "model-import-interrupted.tmp").apply {
            writeBytes(byteArrayOf(9))
        }
        val staleReceipt = File(root, "selected-model-interrupted.tmp").apply {
            writeBytes(byteArrayOf(8))
        }
        val unrelated = File(root, "unrelated.tmp").apply {
            writeBytes(byteArrayOf(7))
        }
        ProductionAndroidLocalModelSelection.clearForTests()

        val restored = ProductionAndroidLocalModelSelection.restore(root)

        assertEquals(selected.canonicalFile, restored)
        assertTrue(!staleImport.exists())
        assertTrue(!staleReceipt.exists())
        assertTrue(unrelated.isFile)
        assertTrue(selected.isFile)
    }

    @Test
    fun exact_user_selection_is_restored_after_process_local_state_is_lost() {
        val root = Files.createTempDirectory("liliya-model-selection-restart").toFile()
            .also(roots::add)
        val bytes = byteArrayOf(9, 8, 7, 6, 5, 4)

        val selected = assertIs<ProductionAndroidLocalModelSelectionResult.Selected>(
            ProductionAndroidLocalModelSelection.importSelected(root, maxImportBytes = 1024) {
                ByteArrayInputStream(bytes)
            }
        ).file

        ProductionAndroidLocalModelSelection.clearForTests()
        assertNull(ProductionAndroidLocalModelSelection.current())

        val restored = ProductionAndroidLocalModelSelection.restore(root)

        assertEquals(selected.canonicalFile, restored)
        assertEquals(selected.canonicalFile, ProductionAndroidLocalModelSelection.current())
        assertContentEquals(bytes, restored?.readBytes())
    }

    @Test
    fun legacy_v1_pointer_is_hash_verified_once_and_upgraded_to_v2_receipt() {
        val root = Files.createTempDirectory("liliya-model-selection-legacy").toFile()
            .also(roots::add)
        val fileName = "model-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad.bin"
        val model = File(root, fileName).apply {
            writeBytes("abc".encodeToByteArray())
        }
        val legacyPointer = File(root, "selected-model-v1").apply {
            writeText(fileName)
        }

        val restored = ProductionAndroidLocalModelSelection.restore(root)

        assertEquals(model.canonicalFile, restored)
        assertTrue(File(root, "selected-model-v2").isFile)
        assertTrue(!legacyPointer.exists())

        ProductionAndroidLocalModelSelection.clearForTests()
        assertEquals(model.canonicalFile, ProductionAndroidLocalModelSelection.restore(root))
    }

    @Test
    fun restore_rejects_v2_receipt_when_model_metadata_changes_without_reselecting_it() {
        val root = Files.createTempDirectory("liliya-model-selection-corrupt").toFile()
            .also(roots::add)
        val selected = assertIs<ProductionAndroidLocalModelSelectionResult.Selected>(
            ProductionAndroidLocalModelSelection.importSelected(root, maxImportBytes = 1024) {
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            }
        ).file
        val changedTimestamp = selected.lastModified() + 60_000L
        assertTrue(selected.setLastModified(changedTimestamp))
        ProductionAndroidLocalModelSelection.clearForTests()

        assertNull(ProductionAndroidLocalModelSelection.restore(root))
        assertNull(ProductionAndroidLocalModelSelection.current())
    }

    @Test
    fun legacy_v1_pointer_rejects_same_size_hash_mismatch_before_receipt_upgrade() {
        val root = Files.createTempDirectory("liliya-model-selection-legacy-mismatch").toFile()
            .also(roots::add)
        val fileName =
            "model-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad.bin"
        File(root, fileName).writeBytes("abd".encodeToByteArray())
        File(root, "selected-model-v1").writeText(fileName)

        assertNull(ProductionAndroidLocalModelSelection.restore(root))
        assertNull(ProductionAndroidLocalModelSelection.current())
        assertTrue(!File(root, "selected-model-v2").exists())
    }

    @Test
    fun allocatable_storage_budget_preserves_fixed_safety_reserve() {
        val reserve = 512L * 1024L * 1024L
        assertNull(ProductionAndroidLocalModelSelection.importBudgetForAllocatableBytes(reserve))
        assertNull(ProductionAndroidLocalModelSelection.importBudgetForAllocatableBytes(reserve - 1L))
        assertEquals(
            1234L,
            ProductionAndroidLocalModelSelection.importBudgetForAllocatableBytes(reserve + 1234L)
        )
    }

    @Test
    fun import_stops_at_explicit_resource_budget_without_model_ownership() {
        val root = Files.createTempDirectory("liliya-model-selection-budget").toFile()
            .also(roots::add)

        val result = ProductionAndroidLocalModelSelection.importSelected(
            directory = root,
            maxImportBytes = 3
        ) {
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        }

        assertIs<ProductionAndroidLocalModelSelectionResult.ResourceLimitRejected>(result)
        assertNull(ProductionAndroidLocalModelSelection.current())
        assertEquals(emptyList(), root.listFiles()?.toList().orEmpty())
    }

    @Test
    fun malformed_or_traversing_durable_pointer_fails_closed_without_model_discovery() {
        val root = Files.createTempDirectory("liliya-model-selection-malformed").toFile()
            .also(roots::add)
        val undiscovered = File(root, "model-${"a".repeat(64)}.bin").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        File(root, "selected-model-v1").writeText("../${undiscovered.name}")

        val restored = ProductionAndroidLocalModelSelection.restore(root)

        assertNull(restored)
        assertNull(ProductionAndroidLocalModelSelection.current())
        assertTrue(undiscovered.isFile)
    }

    @Test
    fun stale_durable_pointer_fails_closed_without_selecting_another_imported_model() {
        val root = Files.createTempDirectory("liliya-model-selection-stale").toFile()
            .also(roots::add)
        val staleName = "model-${"b".repeat(64)}.bin"
        File(root, "selected-model-v1").writeText(staleName)
        File(root, "model-${"c".repeat(64)}.bin").writeBytes(byteArrayOf(4, 5, 6))

        val restored = ProductionAndroidLocalModelSelection.restore(root)

        assertNull(restored)
        assertNull(ProductionAndroidLocalModelSelection.current())
    }

    @Test
    fun empty_document_is_rejected_without_model_ownership() {
        val root = Files.createTempDirectory("liliya-model-selection-empty").toFile()
            .also(roots::add)

        val result = ProductionAndroidLocalModelSelection.importSelected(root, maxImportBytes = 1024) {
            ByteArrayInputStream(byteArrayOf())
        }

        assertIs<ProductionAndroidLocalModelSelectionResult.EmptyDocument>(result)
        assertNull(ProductionAndroidLocalModelSelection.current())
        assertEquals(emptyList(), root.listFiles()?.toList().orEmpty())
    }

    @Test
    fun input_failure_is_bounded_without_model_ownership() {
        val root = Files.createTempDirectory("liliya-model-selection-failure").toFile()
            .also(roots::add)

        val result = ProductionAndroidLocalModelSelection.importSelected(root, maxImportBytes = 1024) {
            error("private document provider failure")
        }

        assertIs<ProductionAndroidLocalModelSelectionResult.Failed>(result)
        assertNull(ProductionAndroidLocalModelSelection.current())
        assertEquals(emptyList(), root.listFiles()?.toList().orEmpty())
    }
}
