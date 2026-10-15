package io.layertrace.core

import io.layertrace.core.model.CharFormat
import io.layertrace.core.model.Paragraph
import io.layertrace.core.model.Run
import io.layertrace.core.model.Story
import io.layertrace.core.model.TextAttrs
import io.layertrace.core.text.StoryEditing
import org.junit.Assert.assertEquals
import org.junit.Test

class StoryEditingTest {
    private val bold = CharFormat("CharacterStyle/Bold")
    private val plain = CharFormat()

    private fun story() = Story(
        "s", listOf(
            Paragraph("P/A", runs = listOf(Run("Hello ", plain), Run("bold", bold), Run(" world", plain))),
            Paragraph("P/B", runs = listOf(Run("Second", plain))),
        )
    )

    @Test
    fun textAndLength() {
        val s = story()
        assertEquals("Hello bold world\nSecond", s.text)
        assertEquals(s.text.length, s.length)
    }

    @Test
    fun insertInheritsPrecedingFormat() {
        val s = StoryEditing.insert(story(), 10, "er")
        assertEquals("Hello bolder world\nSecond", s.text)
        assertEquals(listOf("Hello ", "bolder", " world"), s.paragraphs[0].runs.map { it.text })
        assertEquals(bold, s.paragraphs[0].runs[1].format)
    }

    @Test
    fun newlineSplitsParagraphKeepingStyle() {
        val s = StoryEditing.insert(story(), 5, "\n")
        assertEquals(3, s.paragraphs.size)
        assertEquals("Hello", s.paragraphs[0].text)
        assertEquals(" bold world", s.paragraphs[1].text)
        assertEquals("P/A", s.paragraphs[1].styleId)
        assertEquals(bold, s.paragraphs[1].runs[1].format)
    }

    @Test
    fun deleteAcrossParagraphsMerges() {
        val s = StoryEditing.delete(story(), 11, 18)
        assertEquals("Hello bold econd", s.text)
        assertEquals(1, s.paragraphs.size)
        assertEquals("P/A", s.paragraphs[0].styleId)
    }

    @Test
    fun deleteEverythingLeavesOneEmptyParagraph() {
        val s0 = story()
        val s = StoryEditing.delete(s0, 0, s0.length)
        assertEquals("", s.text)
        assertEquals(1, s.paragraphs.size)
        assertEquals(1, s.paragraphs[0].runs.size)
    }

    @Test
    fun applyCharacterOverrideSplitsRuns() {
        val s = StoryEditing.overrideCharAttrs(story(), 2, 8, TextAttrs(pointSize = 20.0))
        val runs = s.paragraphs[0].runs
        assertEquals(listOf("He", "llo ", "bo", "ld", " world"), runs.map { it.text })
        assertEquals(20.0, runs[1].format.overrides.pointSize)
        assertEquals(null, runs[3].format.overrides.pointSize)
    }

    @Test
    fun applyParagraphStyleToRange() {
        val s = StoryEditing.applyParagraphStyle(story(), 3, 20, "P/C")
        assertEquals(listOf("P/C", "P/C"), s.paragraphs.map { it.styleId })
    }

    @Test
    fun formatAtStartOfParagraph() {
        assertEquals(plain, StoryEditing.formatAt(story(), 0))
        assertEquals(bold, StoryEditing.formatAt(story(), 9))
        assertEquals(plain, StoryEditing.formatAt(story(), 17))
    }

    @Test
    fun wordNavigation() {
        val t = "Hello, brave new world"
        assertEquals(7, StoryEditing.wordBoundaryLeft(t, 10))
        assertEquals(12, StoryEditing.wordBoundaryRight(t, 7))
        assertEquals(7 until 12, StoryEditing.wordAt(t, 9))
    }
}
