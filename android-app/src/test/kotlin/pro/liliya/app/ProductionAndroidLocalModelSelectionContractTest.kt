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
