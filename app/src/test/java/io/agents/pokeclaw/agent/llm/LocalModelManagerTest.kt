package io.agents.pokeclaw.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `model directory uses external app storage when it can be created`() {
        val externalRoot = temporaryFolder.newFolder("external")
        val internalRoot = temporaryFolder.newFolder("internal")

        val dir = LocalModelManager.resolveUsableModelDir(externalRoot, internalRoot)

        assertEquals(externalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `model directory falls back to internal storage when external path is unusable`() {
        val externalRoot = temporaryFolder.newFolder("external")
        externalRoot.resolve("models").writeText("blocking file")
        val internalRoot = temporaryFolder.newFolder("internal")

        val dir = LocalModelManager.resolveUsableModelDir(externalRoot, internalRoot)

        assertEquals(internalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `model directory falls back to internal storage when external path is not writable`() {
        val externalRoot = temporaryFolder.newFolder("external")
        val internalRoot = temporaryFolder.newFolder("internal")
        val externalModelDir = externalRoot.resolve("models")

        val dir = LocalModelManager.resolveUsableModelDir(
            externalRoot = externalRoot,
            internalRoot = internalRoot,
            canWriteDirectory = { candidate -> candidate != externalModelDir },
        )

        assertEquals(internalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `model directory falls back to internal storage when external root is missing`() {
        val internalRoot = temporaryFolder.newFolder("internal")

        val dir = LocalModelManager.resolveUsableModelDir(null, internalRoot)

        assertEquals(internalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }
}
