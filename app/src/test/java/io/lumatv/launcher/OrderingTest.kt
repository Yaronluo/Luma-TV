package io.lumatv.launcher

import org.junit.Assert.*
import org.junit.Test

class OrderingTest {
    @Test fun removedFocusKeepsNearestSlot() {
        assertEquals("c", fallbackFocus(listOf("a", "b", "c"), "b", listOf("a", "c")))
        assertEquals("b", fallbackFocus(listOf("a", "b", "c"), "c", listOf("a", "b")))
        assertEquals("", fallbackFocus(listOf("a"), "a", emptyList()))
    }
    @Test fun installDoesNotDisplaceExistingFocus() {
        assertEquals("b", fallbackFocus(listOf("a", "b"), "b", listOf("new", "a", "b")))
    }
    @Test fun movingIsBoundedAndPreservesOtherOrder() {
        assertEquals(listOf("a", "c", "d", "b"), moveItem(listOf("a", "b", "c", "d"), "b", 6))
        assertEquals(listOf("a", "b"), moveItem(listOf("a", "b"), "a", -1))
    }
    @Test fun newInstallAppendsToUserOrder() {
        val apps = listOf(AppEntry("a", "a", "Alpha", true), AppEntry("b", "b", "Beta", true), AppEntry("c", "c", "C", true))
        assertEquals(listOf("b", "a", "c"), orderedApps(apps, listOf("b", "a")).map { it.id })
    }
}
