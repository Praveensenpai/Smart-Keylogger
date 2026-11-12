package com.bshu2.androidkeylogger

import com.bshu2.androidkeylogger.data.LineRing
import com.bshu2.androidkeylogger.data.LogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LineRingTest {

    @Test
    fun keepsOnlyNewestLinesAtCapacity() {
        val ring = LineRing(3)
        ring.add((1..5).map { "line$it" })
        assertEquals(listOf("line3", "line4", "line5"), ring.snapshot())
    }

    @Test
    fun blankLinesAreSkipped() {
        val ring = LineRing(10)
        ring.add(listOf("a", "", "b"))
        assertEquals(listOf("a", "b"), ring.snapshot())
    }

    @Test
    fun clearEmptiesTheRing() {
        val ring = LineRing(5)
        ring.add(listOf("a"))
        ring.clear()
        assertTrue(ring.snapshot().isEmpty())
    }
}

class LogRepositoryTest {

    private fun tempFile(content: String): File {
        val f = Files.createTempFile("logtest", ".txt").toFile()
        f.writeText(content)
        return f
    }

    @Test
    fun readsOnlyCompleteLinesFromOffset() {
        val f = tempFile("one\ntwo\nthr")
        val (lines, offset) = LogRepository.readFrom(f, 0L)
        assertEquals(listOf("one", "two"), lines)
        assertEquals("one\ntwo\n".toByteArray().size.toLong(), offset)
        f.delete()
    }

    @Test
    fun resumesFromPreviousOffsetWithoutDuplicates() {
        val f = tempFile("one\ntwo\n")
        val (_, offset) = LogRepository.readFrom(f, 0L)
        f.appendText("three\n")
        val (lines, _) = LogRepository.readFrom(f, offset)
        assertEquals(listOf("three"), lines)
        f.delete()
    }

    @Test
    fun shrunkenFileIsReReadFromZero() {
        val f = tempFile("alpha\nbeta\n")
        val (_, offset) = LogRepository.readFrom(f, 0L)
        f.writeText("gamma\n")
        val (lines, _) = LogRepository.readFrom(f, offset)
        assertEquals(listOf("gamma"), lines)
        f.delete()
    }

    @Test
    fun tailLinesReturnsLastN() {
        val f = tempFile((1..10).joinToString("\n") { "l$it" } + "\n")
        val lines = LogRepository.tailLines(f, maxLines = 3)
        assertEquals(listOf("l8", "l9", "l10"), lines)
        f.delete()
    }

    @Test
    fun missingFileYieldsNoLines() {
        val missing = File("/nonexistent/does-not-exist.txt")
        assertEquals(emptyList<String>(), LogRepository.tailLines(missing, 10))
    }
}
