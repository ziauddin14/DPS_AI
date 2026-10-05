package com.softwaremine.dps.domain.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementMatcherTest {

    private class FakeNode(
        override val resourceId: String? = null,
        override val contentDescription: String? = null,
        override val text: String? = null,
        override val isPassword: Boolean = false,
        override val children: List<AutomationNode> = emptyList(),
        /** What the app itself would answer right now — deliberately separate from the cached [children]. */
        private val live: List<AutomationNode> = emptyList(),
    ) : AutomationNode {
        var liveLookups = 0
            private set

        override fun findByResourceId(resourceId: String): List<AutomationNode> {
            liveLookups++
            return live.filter { it.resourceId == resourceId }
        }
    }

    @Test
    fun `finds a unique node by resource id`() {
        val target = FakeNode(resourceId = "app:id/button", text = "Tap me")
        val root = FakeNode(children = listOf(FakeNode(text = "unrelated"), target))

        val match = ElementMatcher.find(root, ElementDescriptor(resourceId = "app:id/button"))

        assertTrue(match is ElementMatch.Found)
        assertEquals(target, (match as ElementMatch.Found).node)
    }

    @Test
    fun `finds a unique node by content description when no resource id is given`() {
        val target = FakeNode(contentDescription = "Submit")
        val root = FakeNode(children = listOf(FakeNode(contentDescription = "Cancel"), target))

        val match = ElementMatcher.find(root, ElementDescriptor(contentDescription = "Submit"))

        assertEquals(ElementMatch.Found(target), match)
    }

    @Test
    fun `finds a unique node by exact text as a last resort`() {
        val target = FakeNode(text = "Tap me")
        val root = FakeNode(children = listOf(FakeNode(text = "Other"), target))

        val match = ElementMatcher.find(root, ElementDescriptor(text = "Tap me"))

        assertEquals(ElementMatch.Found(target), match)
    }

    @Test
    fun `resource id takes priority over content description and text when present`() {
        // Only the resourceId signal is consulted when it is present on the
        // descriptor — a node whose text happens to match a different value
        // must never be found instead.
        val target = FakeNode(resourceId = "app:id/button", text = "Tap me")
        val decoy = FakeNode(text = "Something else entirely")
        val root = FakeNode(children = listOf(decoy, target))

        val match = ElementMatcher.find(root, ElementDescriptor(resourceId = "app:id/button", text = "Something else entirely"))

        assertEquals(ElementMatch.Found(target), match)
    }

    @Test
    fun `zero matches is NotFound, never a guess`() {
        val root = FakeNode(children = listOf(FakeNode(text = "unrelated")))

        val match = ElementMatcher.find(root, ElementDescriptor(resourceId = "app:id/missing"))

        assertEquals(ElementMatch.NotFound, match)
    }

    @Test
    fun `duplicate matches are Ambiguous, never auto-picked`() {
        val root = FakeNode(
            children = listOf(
                FakeNode(text = "Send"),
                FakeNode(text = "Send"),
            ),
        )

        val match = ElementMatcher.find(root, ElementDescriptor(text = "Send"))

        assertEquals(ElementMatch.Ambiguous(2), match)
    }

    @Test
    fun `an empty descriptor with no signals is NotFound`() {
        val root = FakeNode(children = listOf(FakeNode(text = "anything")))

        val match = ElementMatcher.find(root, ElementDescriptor())

        assertEquals(ElementMatch.NotFound, match)
    }

    @Test
    fun `the search walks the whole tree, not just direct children`() {
        val target = FakeNode(resourceId = "app:id/deep")
        val root = FakeNode(children = listOf(FakeNode(children = listOf(FakeNode(children = listOf(target))))))

        val match = ElementMatcher.find(root, ElementDescriptor(resourceId = "app:id/deep"))

        assertEquals(ElementMatch.Found(target), match)
    }

    // -----------------------------------------------------------------
    // findFresh — the verification read
    // -----------------------------------------------------------------

    private val buttonId = ElementDescriptor(resourceId = "app:id/button")

    /** The device state right after a real tap: the cache still holds the pre-tap node, the app already shows the new text. */
    private fun rootWithStaleCache(live: List<AutomationNode>) = FakeNode(
        children = listOf(FakeNode(resourceId = "app:id/button", text = "Tap me")),
        live = live,
    )

    @Test
    fun `findFresh reads the app's current state, not the pre-tap snapshot the cached tree still holds`() {
        val current = FakeNode(resourceId = "app:id/button", text = "Tapped")
        val root = rootWithStaleCache(live = listOf(current))

        // The defect, pinned: the cached walk still answers with the old text.
        assertEquals("Tap me", (ElementMatcher.find(root, buttonId) as ElementMatch.Found).node.text)

        val fresh = ElementMatcher.findFresh(root, buttonId)

        assertEquals(ElementMatch.Found(current), fresh)
        assertEquals("Tapped", (fresh as ElementMatch.Found).node.text)
    }

    @Test
    fun `findFresh asks the app exactly once - no second attempt, no polling`() {
        val root = rootWithStaleCache(live = listOf(FakeNode(resourceId = "app:id/button", text = "Tap me")))

        ElementMatcher.findFresh(root, buttonId)

        assertEquals(1, root.liveLookups)
    }

    @Test
    fun `findFresh is NotFound when the element is really gone, even though the cached tree still lists it`() {
        val root = rootWithStaleCache(live = emptyList())

        assertEquals(ElementMatch.NotFound, ElementMatcher.findFresh(root, buttonId))
    }

    @Test
    fun `findFresh keeps the unique-match rule - two live matches are Ambiguous, never auto-picked`() {
        val root = rootWithStaleCache(
            live = listOf(
                FakeNode(resourceId = "app:id/button", text = "Tapped"),
                FakeNode(resourceId = "app:id/button", text = "Tapped"),
            ),
        )

        assertEquals(ElementMatch.Ambiguous(2), ElementMatcher.findFresh(root, buttonId))
    }

    @Test
    fun `findFresh matches the resource id exactly`() {
        val root = rootWithStaleCache(live = listOf(FakeNode(resourceId = "app:id/other_button", text = "Tapped")))

        assertEquals(ElementMatch.NotFound, ElementMatcher.findFresh(root, buttonId))
    }

    @Test
    fun `findFresh falls back to the tree walk for a descriptor with no resource id`() {
        val target = FakeNode(contentDescription = "Submit")
        val root = FakeNode(children = listOf(target))

        val match = ElementMatcher.findFresh(root, ElementDescriptor(contentDescription = "Submit"))

        assertEquals(ElementMatch.Found(target), match)
        assertEquals("No live lookup exists for a content description", 0, root.liveLookups)
    }
}
