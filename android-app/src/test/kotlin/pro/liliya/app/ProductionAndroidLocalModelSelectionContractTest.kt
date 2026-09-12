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

        val result = ProductionAndroidLocalModelSelection.importSelected(root) {
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
    fun exact_user_selection_is_restored_after_process_local_state_is_lost() {
        val root = Files.createTempDirectory("liliya-model-selection-restart").toFile()
            .also(roots::add)
        val bytes = byteArrayOf(9, 8, 7, 6, 5, 4)

        val selected = assertIs<ProductionAndroidLocalModelSelectionResult.Selected>(
            ProductionAndroidLocalModelSelection.importSelected(root) {
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

        val result = ProductionAndroidLocalModelSelection.importSelected(root) {
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

        val result = ProductionAndroidLocalModelSelection.importSelected(root) {
            error("private document provider failure")
        }

        assertIs<ProductionAndroidLocalModelSelectionResult.Failed>(result)
        assertNull(ProductionAndroidLocalModelSelection.current())
        assertEquals(emptyList(), root.listFiles()?.toList().orEmpty())
    }
}
