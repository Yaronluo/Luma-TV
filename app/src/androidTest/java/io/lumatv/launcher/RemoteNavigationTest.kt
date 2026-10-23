package io.lumatv.launcher

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.roundToInt

/** Injects remote key events; never clicks coordinates or changes the system Home role. */
class RemoteNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var original: LumaConfig
    private val appNode = SemanticsMatcher("is application card") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("app:") == true
    }

    @Before fun ready() {
        original = runBlocking { ConfigStore(compose.activity).config.first() }
        runBlocking { ConfigStore(compose.activity).update { it.copy(route = "home", favorites = emptyList(), order = emptyList(),
            hidden = emptySet(), home = Bookmark(), all = Bookmark()) } }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000) { compose.onAllNodes(appNode).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        // The app deliberately restores its prior route. Reach Home using the remote,
        // rather than assuming the developer left the app on a particular page.
        var remaining = 4
        while (compose.onAllNodesWithTag("page:home").fetchSemanticsNodes().isEmpty() && remaining-- > 0) {
            device.pressBack(); compose.waitForIdle()
        }
        compose.onNodeWithTag("page:home").assertExists()
    }

    @After fun restore() {
        runBlocking { ConfigStore(compose.activity).update { original } }
    }

    private fun focusedTag(): String {
        val dialogFocus = compose.onAllNodes(isFocused() and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
        return (dialogFocus.singleOrNull() ?: compose.onNode(isFocused()).fetchSemanticsNode()).config[SemanticsProperties.TestTag]
    }
    private fun press(code: Int) { device.pressKeyCode(code); compose.waitForIdle() }
    private fun focusTag(tag: String) {
        if (compose.onAllNodesWithTag("page:home").fetchSemanticsNodes().isNotEmpty()) {
            val config = runBlocking { ConfigStore(compose.activity).config.first() }
            val visible = compose.activity.model.apps.value!!.filter { it.id !in config.hidden }
            val tv = orderedApps(visible.filter { it.television }, config.order)
            val favorites = orderedApps(visible.filter { it.id in config.favorites }, config.favorites)
                .ifEmpty { tv.sortedBy { it.variant.isNotEmpty() }.take(5) }
            val dock = favorites.take(5).map { "app:${it.id}" } + "footer-primary"
            val body = (favorites.drop(5) + tv).distinctBy { it.id }.filter { "app:${it.id}" !in dock }
                .map { "app:${it.id}" } + listOf("settings", "manage")
            if (focusedTag().startsWith("hero:")) press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            repeat(body.size / 6 + 2) { if (focusedTag() !in dock) press(android.view.KeyEvent.KEYCODE_DPAD_UP) }
            repeat(6) { press(android.view.KeyEvent.KEYCODE_DPAD_LEFT) }
            if (tag in dock) repeat(dock.indexOf(tag)) { press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }
            else {
                val index = body.indexOf(tag)
                require(index >= 0)
                press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                repeat(index / 6) { press(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
                repeat(index % 6) { press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }
            }
            assertEquals(tag, focusedTag())
            return
        }
        // Navigate by directions only. These test-only focus requests are deliberately avoided.
        repeat(18) {
            if (focusedTag() == tag) return
            press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        }
        error("Could not reach $tag with remote")
    }

    @Test fun initialFocusAndSpatialMovement() {
        val before = focusedTag()
        assertTrue(before.startsWith("app:"))
        press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        val after = focusedTag()
        assertTrue(after.startsWith("app:")); assertNotEquals(before, after)
        press(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(before, focusedTag())
        val bounds = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left > 0 && bounds.top > 0)
    }

    @Test fun allApplicationsSettingsAndMenuRestore() {
        focusTag("footer-primary")
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("page:all").assertExists()
        val app = focusedTag()
        assertTrue(app.startsWith("app:"))
        press(android.view.KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("settings", focusedTag())
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("page:settings").assertExists()
        press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("appearance-background", focusedTag())
        press(android.view.KeyEvent.KEYCODE_BACK)
        assertEquals("tab:外观", focusedTag())
        press(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("page:all").assertExists()
        assertEquals("settings", focusedTag())
        press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        device.executeShellCommand("input keyevent --longpress KEYCODE_DPAD_CENTER")
        compose.waitForIdle()
        compose.onNodeWithTag("favorite-action").assertIsFocused()
        press(android.view.KeyEvent.KEYCODE_BACK)
        assertTrue(focusedTag().startsWith("app:"))
    }

    @Test fun quickDirectionsRemainFocusedAndVisible() {
        val initial = focusedTag()
        device.executeShellCommand("input keyevent --longpress KEYCODE_DPAD_RIGHT")
        compose.waitForIdle()
        assertNotEquals(initial, focusedTag())
        device.executeShellCommand("input keyevent --longpress KEYCODE_DPAD_LEFT")
        compose.waitForIdle()
        assertEquals(initial, focusedTag())
        device.executeShellCommand("input keyevent KEYCODE_DPAD_DOWN KEYCODE_DPAD_UP KEYCODE_DPAD_RIGHT")
        compose.waitForIdle()
        assertTrue(focusedTag().startsWith("app:"))
        press(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(initial, focusedTag())
        repeat(10) {
            device.pressDPadRight(); device.pressDPadDown(); device.pressDPadLeft(); device.pressDPadUp()
        }
        compose.waitForIdle()
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        assertTrue(node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0)
        assertTrue(node.boundsInRoot.top >= 0)
        compose.onNodeWithTag("page:home").assertExists()
    }

    @Test fun focusedCardKeepsShapeDuringLibraryReveal() {
        runBlocking { ConfigStore(compose.activity).update {
            it.copy(reducedMotion = false, background = "ambient", brightness = 1, backgroundBlur = true)
        } }
        compose.waitUntil(5000) { compose.activity.model.config.value?.reducedMotion == false }
        val dock = listOf("com.bili.tv", "com.google.android.youtube.tv", "com.google.android.youtube.tvmusic", "com.android.tv", "com.android.vending")
            .map { pkg -> compose.activity.model.apps.value!!.first { it.packageName == pkg }.id }
        runBlocking { ConfigStore(compose.activity).update { it.copy(favorites = dock) } }
        compose.waitUntil(5000) { compose.activity.model.config.value?.favorites == dock }
        compose.waitForIdle()
        val app = compose.activity.model.apps.value!!.first { it.packageName == "com.android.tv.settings" }
        val tag = "app:${app.id}"
        focusTag(tag)
        press(android.view.KeyEvent.KEYCODE_DPAD_UP)
        compose.mainClock.autoAdvance = false
        try {
            device.pressDPadDown()
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            compose.mainClock.advanceTimeBy(256)
            compose.waitForIdle()
            assertEquals(tag, focusedTag())
            compose.onNodeWithTag(tag).assertIsDisplayed()
            // Probe the grey Settings artwork just outside its unscaled cell. An implicit
            // offscreen clip loses this strip even though semantics still report focus.
            val config = compose.activity.model.config.value!!
            val tv = orderedApps(compose.activity.model.apps.value!!.filter { it.television }, config.order)
            val body = tv.filter { it.id !in dock }
            val col = body.indexOfFirst { it.id == app.id } % 6
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            val edge = (root.width - (root.width - config.safeInset * density * 2) * .85f) / 2
            val gap = 23f * density
            val width = (root.width - edge * 2 - gap * 5) / 6
            val x = (edge + (width + gap) * col - 2f * density).roundToInt()
            val y = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center.y.roundToInt()
            device.waitForIdle(1000)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                java.io.File(compose.activity.getExternalFilesDir(null), "focused-reveal.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                assertTrue("Focused artwork was clipped at its cell edge",
                    android.graphics.Color.red(screenshot.getPixel(x, y)) > 50)
            } finally { screenshot.recycle() }
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            assertEquals(tag, focusedTag())
            device.waitForIdle(1000)
            device.takeScreenshot(java.io.File(compose.activity.getExternalFilesDir(null), "focused-settled.png"))
        } finally {
            compose.mainClock.autoAdvance = true
        }
        press(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("app-grid").assertIsNotDisplayed()
        compose.onNodeWithTag("home-dock-glass").assertIsDisplayed()
    }

    @Test fun favoritesHiddenAndSettingsSurviveRecreate() {
        focusTag("manage")
        assertEquals("manage", focusedTag())
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("page:manage").assertExists()
        val selected = focusedTag().removePrefix("app:")
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("favorite-action").assertIsFocused()
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) { runBlocking { ConfigStore(compose.activity).config.first().favorites.contains(selected) } }
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("sort-action", focusedTag())
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) { runBlocking { ConfigStore(compose.activity).config.first().order.indexOf(selected) == 1 } }
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(3) { press(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("hide-action", focusedTag())
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) { runBlocking { selected in ConfigStore(compose.activity).config.first().hidden } }
        assertNotEquals("app:$selected", focusedTag())
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodes(appNode).fetchSemanticsNodes().isNotEmpty() }
        val saved = runBlocking { ConfigStore(compose.activity).config.first() }
        assertTrue(selected in saved.favorites); assertTrue(selected in saved.hidden)
        assertEquals(1, saved.order.indexOf(selected))
    }

    @Test fun packageLifecycleAndLongGrid() {
        val packageName = "io.lumatv.fixture.eight"
        val initial = focusedTag()
        val install = device.executeShellCommand("pm install -r /data/local/tmp/luma-fixture-eight.apk")
        assertTrue(install, install.contains("Success"))
        compose.waitUntil(10000) { compose.activity.model.apps.value?.any { it.packageName == packageName } == true }
        compose.waitForIdle()
        assertEquals(initial, focusedTag())
        focusTag("footer-primary"); press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        val entries = orderedApps(compose.activity.model.apps.value!!.filter { it.television }, emptyList())
        val index = entries.indexOfFirst { it.packageName == packageName }
        assertTrue(index >= 12) // At least the third row: requires scrolling in this viewport.
        repeat(index / 6) { press(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(index % 6) { press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }
        val fixtureTag = "app:${entries[index].id}"
        assertEquals(fixtureTag, focusedTag())
        compose.onNodeWithTag(fixtureTag).assertIsDisplayed()
        device.takeScreenshot(java.io.File(compose.activity.getExternalFilesDir(null), "long-grid.png"))
        // Replacement installation while focused must not move the focus or reorder the list.
        val beforeUpdate = compose.activity.model.iconRevision.value
        val update = device.executeShellCommand("pm install -r /data/local/tmp/luma-fixture-eight-update.apk")
        assertTrue(update, update.contains("Success"))
        compose.waitUntil(10000) { compose.activity.model.iconRevision.value > beforeUpdate }
        compose.waitForIdle()
        assertEquals(fixtureTag, focusedTag())
        val removed = device.executeShellCommand("pm uninstall $packageName")
        assertTrue(removed, removed.contains("Success"))
        compose.waitUntil(10000) { compose.activity.model.apps.value?.none { it.packageName == packageName } == true }
        compose.waitForIdle()
        assertNotEquals(fixtureTag, focusedTag())
        compose.onNode(isFocused()).assertIsDisplayed()
        assertTrue(focusedTag().startsWith("app:"))
    }

    @Test fun cancelSortAndUnfavoriteAndRestoreHidden() {
        focusTag("manage")
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        val selected = focusedTag().removePrefix("app:")
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER) // Favorite.
        compose.waitUntil(5000) { selected in runBlocking { ConfigStore(compose.activity).config.first().favorites } }
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER) // Sort.
        press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        press(android.view.KeyEvent.KEYCODE_BACK) // Cancel.
        assertTrue(runBlocking { ConfigStore(compose.activity).config.first().order }.isEmpty())
        assertEquals("app:$selected", focusedTag())
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER) // Unfavorite.
        compose.waitUntil(5000) { selected !in runBlocking { ConfigStore(compose.activity).config.first().favorites } }
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(2) { press(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("hide-action", focusedTag())
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) { selected in runBlocking { ConfigStore(compose.activity).config.first().hidden } }
        press(android.view.KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("settings", focusedTag())
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("tab:应用管理", focusedTag())
        press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("page:hidden").assertExists()
        assertEquals("app:$selected", focusedTag())
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) { selected !in runBlocking { ConfigStore(compose.activity).config.first().hidden } }
        compose.onNodeWithTag("footer-primary").assertIsFocused()
        press(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("page:settings").assertExists()
        compose.onNodeWithTag("hidden-apps").assertIsFocused()
    }

    @Test fun quickSettingsCategoryTraversal() {
        focusTag("settings")
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("page:settings").assertExists()
        device.executeShellCommand("input keyevent KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_RIGHT")
        compose.waitForIdle()
        assertEquals("request-home", focusedTag())
        press(android.view.KeyEvent.KEYCODE_BACK)
        repeat(8) { device.pressDPadUp(); device.pressDPadDown() }
        compose.waitForIdle()
        assertTrue(focusedTag().startsWith("tab:"))
        compose.onNode(isFocused()).assertIsDisplayed()
    }

    @Test fun appearancePickerReturnsToRowAndPersists() {
        focusTag("settings")
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("appearance-background", focusedTag())
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(focusedTag().startsWith("background:"))
        // Return to the first choice, then pick the actual ocean background.
        repeat(4) { press(android.view.KeyEvent.KEYCODE_DPAD_UP) }
        press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("background:ocean", focusedTag())
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("appearance-background", focusedTag())
        compose.waitUntil(5000) { runBlocking { ConfigStore(compose.activity).config.first().background == "ocean" } }
        press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(focusedTag().startsWith("brightness:"))
        press(android.view.KeyEvent.KEYCODE_BACK)
        assertEquals("appearance-brightness", focusedTag())
        press(android.view.KeyEvent.KEYCODE_BACK); press(android.view.KeyEvent.KEYCODE_BACK)
        assertEquals("settings", focusedTag())
    }

    @Test fun quickConfirmationFollowsLatestTarget() {
        device.executeShellCommand("input keyevent KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER")
        compose.waitForIdle()
        compose.onNodeWithTag("page:all").assertExists()
        device.executeShellCommand("input keyevent KEYCODE_DPAD_UP KEYCODE_DPAD_CENTER")
        compose.waitForIdle()
        compose.onNodeWithTag("page:settings").assertExists()
        press(android.view.KeyEvent.KEYCODE_BACK)
        press(android.view.KeyEvent.KEYCODE_BACK)
        assertEquals("footer-primary", focusedTag())
    }

    @Test fun longFavoritesTrayAndUtilityFocusRestore() {
        val ids = orderedApps(compose.activity.model.apps.value!!.filter { it.television }, emptyList()).map { it.id }
        assertTrue(ids.size > 10)
        runBlocking { ConfigStore(compose.activity).update { it.copy(favorites = ids, home = Bookmark()) } }
        compose.waitUntil(5000) { compose.activity.model.config.value?.favorites == ids }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000) { compose.onAllNodes(appNode).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        focusTag("app:${ids.first()}")
        assertEquals("app:${ids.first()}", focusedTag())
        focusTag("settings")
        val firstRow = compose.onNodeWithTag("home-first-row").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("app-grid").fetchSemanticsNode().boundsInRoot
        compose.onNode(isFocused()).assertIsDisplayed()
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("page:settings").assertExists()
        press(android.view.KeyEvent.KEYCODE_BACK)
        assertEquals("settings", focusedTag())
        compose.onNode(isFocused()).assertIsDisplayed()
        assertEquals(firstRow, compose.onNodeWithTag("home-first-row").fetchSemanticsNode().boundsInRoot)
        assertEquals(viewport, compose.onNodeWithTag("app-grid").fetchSemanticsNode().boundsInRoot)
        press(android.view.KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("app:${ids[ids.size - 6]}", focusedTag())
        val bounds = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.top >= 0 && bounds.bottom <= root.bottom)
    }

    @Test fun shelfGridAndBlurPreference() {
        runBlocking { ConfigStore(compose.activity).update { it.copy(reducedMotion = false, backgroundBlur = true) } }
        compose.waitUntil(5000) { compose.activity.model.config.value?.let { !it.reducedMotion && it.backgroundBlur } == true }
        compose.waitForIdle()
        val initial = focusedTag()
        val firstRow = compose.onNodeWithTag("home-first-row").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(24f * density, root.bottom - firstRow.bottom, 1f)
        fun assertCollapsedLibrary() {
            assertEquals(firstRow, compose.onNodeWithTag("home-first-row").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("home-dock-glass").assertIsDisplayed()
            val glass = compose.onNodeWithTag("home-dock-glass").fetchSemanticsNode().boundsInRoot
            assertTrue(glass.top < firstRow.top && glass.bottom > firstRow.bottom && glass.bottom <= root.bottom)
            assertTrue(glass.left >= root.left && glass.right <= root.right)
            compose.onNodeWithTag("app-grid").assertIsNotDisplayed()
            compose.onNode(isFocused()).assertIsDisplayed()
        }
        assertCollapsedLibrary()
        press(android.view.KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(initial, focusedTag()) // Wallpaper has no invisible focus targets.
        compose.onNodeWithTag("dock-caption").assertIsDisplayed()
        val caption = compose.activity.model.apps.value!!.first { "app:${it.id}" == initial }.displayName
        compose.onNodeWithTag("dock-caption").assert(hasAnyDescendant(hasText(caption)))
        val captionBounds = compose.onNodeWithTag("dock-caption").fetchSemanticsNode().boundsInRoot
        val expectedWidth = (root.width - 2f * compose.activity.model.config.value!!.safeInset * density) * .85f
        assertEquals(expectedWidth, firstRow.width, 1f)
        assertEquals(firstRow.left, captionBounds.left, 1f)
        press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(captionBounds, compose.onNodeWithTag("dock-caption").fetchSemanticsNode().boundsInRoot)
        press(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(initial, focusedTag())
        assertCollapsedLibrary()
        // Drive the animation deterministically while injecting actual remote events.
        compose.mainClock.autoAdvance = false
        try {
            device.pressDPadDown()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(96)
            compose.waitForIdle()
            val movingRow = compose.onNodeWithTag("home-first-row").fetchSemanticsNode().boundsInRoot
            val movingGrid = compose.onNodeWithTag("app-grid").fetchSemanticsNode().boundsInRoot
            assertTrue(movingRow.top > 52f * density && movingRow.top < firstRow.top)
            assertEquals(6f * density, movingGrid.top - movingRow.bottom, 1f)
            assertEquals(firstRow.width, movingRow.width, 1f)
            assertEquals(firstRow.height, movingRow.height, 1f)
            compose.onNodeWithTag("home-dock-glass").assertExists()
            device.takeScreenshot(java.io.File(compose.activity.getExternalFilesDir(null), "expansion-middle.png"))
            // Reverse before completion: both rows must remain together, then settle at the bottom.
            device.pressDPadUp()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val reversingRow = compose.onNodeWithTag("home-first-row").fetchSemanticsNode().boundsInRoot
            assertTrue(reversingRow.top > 52f * density && reversingRow.top < firstRow.top)
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            assertEquals(initial, focusedTag())
            assertCollapsedLibrary()
            device.pressDPadDown()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            assertEquals(52f * density,
                compose.onNodeWithTag("home-first-row").fetchSemanticsNode().boundsInRoot.top, 1f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        val bodyCard = focusedTag()
        assertNotEquals(initial, bodyCard)
        compose.onNode(isFocused()).assertIsDisplayed()
        compose.onNodeWithTag("app-grid").assertIsDisplayed()
        compose.onNodeWithTag("home-dock-glass").assertDoesNotExist()
        val bounds = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.top > 200f && bounds.bottom < 900f)
        press(android.view.KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(initial, focusedTag())
        assertCollapsedLibrary()
        press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(bodyCard, focusedTag())
        press(android.view.KeyEvent.KEYCODE_BACK)
        assertEquals(initial, focusedTag())
        assertCollapsedLibrary()
        focusTag("settings"); press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(2) { press(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("background-blur", focusedTag())
        val before = runBlocking { ConfigStore(compose.activity).config.first() }
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) { runBlocking { ConfigStore(compose.activity).config.first().backgroundBlur != before.backgroundBlur } }
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        assertEquals(!before.backgroundBlur, runBlocking { ConfigStore(compose.activity).config.first().backgroundBlur })
        assertEquals(before.reducedMotion, runBlocking { ConfigStore(compose.activity).config.first().reducedMotion })
        // Reduced motion removes the added background, caption and library transitions together.
        // Recreate retains the settings route through rememberSaveable. Return with the remote
        // before checking Home rather than attempting to override the saved navigation state.
        press(android.view.KeyEvent.KEYCODE_BACK)
        press(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("page:home").assertExists()
        focusTag(initial)
        runBlocking { ConfigStore(compose.activity).update { it.copy(reducedMotion = true) } }
        compose.waitUntil(5000) { compose.activity.model.config.value?.reducedMotion == true }
        compose.waitForIdle()
        assertCollapsedLibrary()
        compose.mainClock.autoAdvance = false
        try {
            device.pressDPadDown()
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
            assertEquals(52f * density,
                compose.onNodeWithTag("home-first-row").fetchSemanticsNode().boundsInRoot.top, 1f)
            device.pressBack()
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
            assertCollapsedLibrary()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun quickConfirmationWhileCollapsingShelf() {
        val tv = orderedApps(compose.activity.model.apps.value!!.filter { it.television }, emptyList())
        val keep = tv.sortedBy { it.variant.isNotEmpty() }.take(5).map { it.id }
        runBlocking { ConfigStore(compose.activity).update { it.copy(hidden = tv.map { app -> app.id }.toSet() - keep.toSet(), home = Bookmark()) } }
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        compose.waitUntil(10000) { compose.onAllNodes(appNode).fetchSemanticsNodes().isNotEmpty() }
        val initial = focusedTag()
        device.executeShellCommand("input keyevent KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER")
        compose.waitForIdle()
        compose.onNodeWithTag("page:settings").assertExists()
        press(android.view.KeyEvent.KEYCODE_BACK)
        assertEquals("settings", focusedTag())
        compose.onNode(isFocused()).assertIsDisplayed()
        press(android.view.KeyEvent.KEYCODE_BACK)
        assertEquals(initial, focusedTag())
        device.executeShellCommand("input keyevent KEYCODE_DPAD_DOWN KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER")
        compose.waitForIdle()
        compose.onNodeWithTag("page:manage").assertExists()
        press(android.view.KeyEvent.KEYCODE_BACK)
        assertEquals("manage", focusedTag())
        compose.onNode(isFocused()).assertIsDisplayed()
    }

    @Test fun quickEntryIntoSettingsContentPreservesKeys() {
        focusTag("settings"); press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        val before = runBlocking { ConfigStore(compose.activity).config.first().backgroundBlur }
        device.executeShellCommand("input keyevent KEYCODE_DPAD_RIGHT KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER")
        compose.waitForIdle()
        assertEquals("background-blur", focusedTag())
        compose.waitUntil(5000) { runBlocking { ConfigStore(compose.activity).config.first().backgroundBlur != before } }
        press(android.view.KeyEvent.KEYCODE_BACK)
        assertEquals("tab:外观", focusedTag())
    }
}
