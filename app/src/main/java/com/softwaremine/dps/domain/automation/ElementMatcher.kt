package com.softwaremine.dps.domain.automation

/**
 * A minimal, pure abstraction over one accessibility node — just enough
 * for [ElementMatcher] to search, with none of the real, Android-only
 * `AccessibilityNodeInfo`'s lifecycle/staleness concerns (M9).
 *
 * ## Why this exists, rather than matching against the real platform type
 * `AccessibilityNodeInfo` is `final`, Android-only, and this project's own
 * established policy (ADR-009) is physical-device-only testing — no
 * Robolectric. Wrapping the real type behind this small interface lets
 * [ElementMatcher]'s own matching *logic* be exhaustively JVM-tested
 * against a hand-built fake tree, while the real, on-device implementation
 * (`data/android/automation/AndroidAutomationEngine`) adapts a genuine
 * `AccessibilityNodeInfo` tree to this shape only at the one seam that
 * needs it.
 */
interface AutomationNode {
    val resourceId: String?
    val contentDescription: String?
    val text: String?

    /**
     * The platform's own `AccessibilityNodeInfo.isPassword` flag — checked
     * by [com.softwaremine.dps.domain.automation.AutomationSecurityGuard]
     * before any write-shaped action (M9). A password-input field is
     * refused outright, never merely confirmation-gated.
     */
    val isPassword: Boolean

    /**
     * This node's children as last *cached* — on a real device these come
     * from the platform's accessibility node cache, which lags a UI change
     * until the matching content-changed event is delivered. Good enough
     * to locate a node before acting on it; not a statement about what the
     * app shows right now. See [findByResourceId].
     */
    val children: List<AutomationNode>

    /**
     * Every node under this one currently carrying [resourceId], read
     * live from the app itself on each call — never from the cache
     * [children] is served from.
     */
    fun findByResourceId(resourceId: String): List<AutomationNode>
}

/** The bounded outcome of searching a node tree for one [ElementDescriptor]. */
sealed interface ElementMatch {
    data class Found(val node: AutomationNode) : ElementMatch
    data object NotFound : ElementMatch
    data class Ambiguous(val count: Int) : ElementMatch
}

/**
 * Deterministic, priority-ordered node matching (M9).
 *
 * ## Priority — locked, not a heuristic
 * Resource id first, then content description, then exact visible text —
 * only the signals actually present on [descriptor] are checked, in that
 * order, and the search stops at the first signal that is present. No
 * signal is combined with another via scoring; this is exact,
 * single-signal-at-a-time matching. A match must be unique within the
 * searched tree — zero or multiple matches are both bounded failure
 * outcomes ([ElementMatch.NotFound]/[ElementMatch.Ambiguous]), never
 * resolved by picking one, mirroring
 * [com.softwaremine.dps.domain.contact.ContactMatch.Ambiguous]'s own
 * established "never guess" precedent.
 *
 * ## Dependencies
 * None. Pure Kotlin — no Android, no coroutines, no I/O.
 */
object ElementMatcher {

    fun find(root: AutomationNode, descriptor: ElementDescriptor): ElementMatch {
        val predicate: (AutomationNode) -> Boolean = when {
            descriptor.resourceId != null -> { node -> node.resourceId == descriptor.resourceId }
            descriptor.contentDescription != null -> { node -> node.contentDescription == descriptor.contentDescription }
            descriptor.text != null -> { node -> node.text == descriptor.text }
            else -> return ElementMatch.NotFound
        }

        val matches = mutableListOf<AutomationNode>()
        collect(root, predicate, matches)

        return unique(matches)
    }

    /**
     * [find], for a read that must reflect what the app shows *now* — the
     * verification read after an action.
     *
     * ## Why [find] is not enough for that read
     * [find] walks [AutomationNode.children], which a real device serves
     * from a node cache. Locating the element just before a tap fills that
     * cache with its pre-tap state, so walking it again straight after the
     * tap returns the same pre-tap text until a content-changed event
     * catches the cache up — a correct tap then verifies as a mismatch.
     * Observed on device as an intermittent false mismatch, with the app's
     * real state already correct at that instant.
     *
     * ## What this does instead
     * A resource-id descriptor is resolved through
     * [AutomationNode.findByResourceId], which asks the app directly.
     * Same exact-id rule, same unique-match rule as [find]; only the
     * source of the nodes differs. No waiting and no second attempt: the
     * tap's own listener has already run by the time the tap call returns,
     * so one live read is enough.
     *
     * A descriptor without a resource id still goes through [find] — no
     * live lookup exists for the other two signals, and no supported
     * target uses them yet.
     */
    fun findFresh(root: AutomationNode, descriptor: ElementDescriptor): ElementMatch {
        val resourceId = descriptor.resourceId ?: return find(root, descriptor)
        return unique(root.findByResourceId(resourceId))
    }

    private fun unique(matches: List<AutomationNode>): ElementMatch = when (matches.size) {
        0 -> ElementMatch.NotFound
        1 -> ElementMatch.Found(matches.single())
        else -> ElementMatch.Ambiguous(matches.size)
    }

    private fun collect(node: AutomationNode, predicate: (AutomationNode) -> Boolean, into: MutableList<AutomationNode>) {
        if (predicate(node)) into += node
        node.children.forEach { collect(it, predicate, into) }
    }
}
