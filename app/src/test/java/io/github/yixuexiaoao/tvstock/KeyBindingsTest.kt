package io.github.yixuexiaoao.tvstock

import io.github.yixuexiaoao.tvstock.model.KeyActions
import io.github.yixuexiaoao.tvstock.model.KeyBindings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBindingsTest {
    @Test
    fun redKeyDefaultsToNoneToPreventAccidentalMute() {
        assertEquals(KeyActions.NONE, KeyBindings.resolve(emptyMap(), KeyBindings.KEY_PROG_RED))
        assertEquals(KeyActions.TOGGLE_CLOUD_MARKET, KeyBindings.resolve(emptyMap(), KeyBindings.KEY_STAR))
        assertEquals(KeyActions.TOGGLE_CLOUD_MARKET, KeyBindings.resolve(emptyMap(), KeyBindings.KEY_0))
    }

    @Test
    fun protectedKeysAreRejectedAndJsonRoundTrips() {
        assertFalse(KeyBindings.isAllowed(4))  // BACK
        assertFalse(KeyBindings.isAllowed(19)) // DPAD_UP
        assertFalse(KeyBindings.isAllowed(23)) // DPAD_CENTER
        assertTrue(KeyBindings.isAllowed(KeyBindings.KEY_PROG_RED))
        assertTrue(KeyBindings.isAllowed(200))

        val parsed = KeyBindings.fromJson(
            JSONObject("""{"4":"TOGGLE_MUTE","183":"TOGGLE_MUTE","200":"OPEN_CLOUD","bad":"NONE","99":"UNKNOWN"}"""),
        )
        assertEquals(mapOf(183 to KeyActions.TOGGLE_MUTE, 200 to KeyActions.OPEN_CLOUD), parsed)
        assertEquals(parsed, KeyBindings.fromJson(KeyBindings.toJson(parsed)))
    }

    @Test
    fun hintReflectsCustomKeyBindings() {
        assertEquals("*/0", KeyBindings.hintFor(emptyMap(), KeyActions.TOGGLE_CLOUD_MARKET))
        val custom = mapOf(
            KeyBindings.KEY_STAR to KeyActions.NONE,
            KeyBindings.KEY_0 to KeyActions.NONE,
            KeyBindings.KEY_MEDIA_PLAY_PAUSE to KeyActions.TOGGLE_CLOUD_MARKET,
        )
        assertEquals("播放", KeyBindings.hintFor(custom, KeyActions.TOGGLE_CLOUD_MARKET))
    }
}
