package dev.cline.termux.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Test

/** CA §0.4 docked children order and the C1.4 step 6 height-compact IME rule. */
class DockedItemsTest {

    @Test fun bothChildrenInSection04Order() {
        assertEquals(
            listOf(DockedItem.SuggestionPanel, DockedItem.QueueStrip),
            dockedItems(hasSuggestionPanel = true, hasQueueStrip = true, heightCompact = false, imeOpen = true),
        )
    }

    @Test fun heightCompactWithImeOpenDropsOnlyTheQueueStrip() {
        assertEquals(
            listOf(DockedItem.SuggestionPanel),
            dockedItems(hasSuggestionPanel = true, hasQueueStrip = true, heightCompact = true, imeOpen = true),
        )
        assertEquals(
            emptyList<DockedItem>(),
            dockedItems(hasSuggestionPanel = false, hasQueueStrip = true, heightCompact = true, imeOpen = true),
        )
    }

    @Test fun queueStripReturnsWhenTheImeCloses() {
        assertEquals(
            listOf(DockedItem.SuggestionPanel, DockedItem.QueueStrip),
            dockedItems(hasSuggestionPanel = true, hasQueueStrip = true, heightCompact = true, imeOpen = false),
        )
    }

    @Test fun absentChildrenAreNotComposed() {
        assertEquals(
            listOf(DockedItem.QueueStrip),
            dockedItems(hasSuggestionPanel = false, hasQueueStrip = true, heightCompact = false, imeOpen = false),
        )
        assertEquals(
            emptyList<DockedItem>(),
            dockedItems(hasSuggestionPanel = false, hasQueueStrip = false, heightCompact = true, imeOpen = true),
        )
    }
}
