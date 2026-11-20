package io.github.yixuexiaoao.tvstock.model

import org.json.JSONObject

/** 可自定义绑定到遥控器实体按键的快捷动作。 */
object KeyActions {
    const val NONE = "NONE"
    const val TOGGLE_CLOUD_MARKET = "TOGGLE_CLOUD_MARKET"
    const val OPEN_CLOUD = "OPEN_CLOUD"
    const val SWITCH_WATCH_MARKET = "SWITCH_WATCH_MARKET"
    const val TOGGLE_WATCH_HEATMAP = "TOGGLE_WATCH_HEATMAP"
    const val CYCLE_CLOUD_SCOPE = "CYCLE_CLOUD_SCOPE"
    const val CYCLE_HEATMAP_WEIGHT = "CYCLE_HEATMAP_WEIGHT"
    const val CYCLE_LAYOUT = "CYCLE_LAYOUT"
    const val CYCLE_DISPLAY_MODE = "CYCLE_DISPLAY_MODE"
    const val CYCLE_SORT = "CYCLE_SORT"
    const val TOGGLE_PRIVACY = "TOGGLE_PRIVACY"
    const val TOGGLE_MUTE = "TOGGLE_MUTE"
    const val SHOW_QR = "SHOW_QR"
    const val OPEN_SETTINGS = "OPEN_SETTINGS"

    val ALL = listOf(
        NONE,
        TOGGLE_CLOUD_MARKET,
        OPEN_CLOUD,
        SWITCH_WATCH_MARKET,
        TOGGLE_WATCH_HEATMAP,
        CYCLE_CLOUD_SCOPE,
        CYCLE_HEATMAP_WEIGHT,
        CYCLE_LAYOUT,
        CYCLE_DISPLAY_MODE,
        CYCLE_SORT,
        TOGGLE_PRIVACY,
        TOGGLE_MUTE,
        SHOW_QR,
        OPEN_SETTINGS,
    )

    fun label(action: String) = when (action) {
        NONE -> "无操作（禁用）"
        TOGGLE_CLOUD_MARKET -> "切换云图 A股/美股"
        OPEN_CLOUD -> "打开大盘云图"
        SWITCH_WATCH_MARKET -> "切换自选 A股/美股"
        TOGGLE_WATCH_HEATMAP -> "切换自选热力图/宫格"
        CYCLE_CLOUD_SCOPE -> "切换云图范围"
        CYCLE_HEATMAP_WEIGHT -> "切换热力图面积"
        CYCLE_LAYOUT -> "切换宫格布局"
        CYCLE_DISPLAY_MODE -> "切换图文方式"
        CYCLE_SORT -> "切换排序"
        TOGGLE_PRIVACY -> "隐私模式开关"
        TOGGLE_MUTE -> "提示音静音/取消"
        SHOW_QR -> "后台二维码"
        OPEN_SETTINGS -> "打开设置"
        else -> action
    }
}

/**
 * 遥控器按键自定义映射（纯 JVM，不依赖 Android KeyEvent 类）。
 * keyCode 数值与 android.view.KeyEvent 常量完全一致。
 */
object KeyBindings {
    // 常用物理按键码（与 android.view.KeyEvent 常量一致）
    const val KEY_0 = 7
    const val KEY_STAR = 17
    const val KEY_POUND = 18
    const val KEY_MENU = 82
    const val KEY_MEDIA_PLAY_PAUSE = 85
    const val KEY_INFO = 165
    const val KEY_GUIDE = 172
    const val KEY_SETTINGS = 176
    const val KEY_PROG_RED = 183
    const val KEY_PROG_GREEN = 184
    const val KEY_PROG_YELLOW = 185
    const val KEY_PROG_BLUE = 186

    /** 不可覆盖的核心系统/导航键，防止误绑后无法操作或退出。 */
    val PROTECTED_KEYS = setOf(
        3,   // HOME
        4,   // BACK
        19,  // DPAD_UP
        20,  // DPAD_DOWN
        21,  // DPAD_LEFT
        22,  // DPAD_RIGHT
        23,  // DPAD_CENTER
        24,  // VOLUME_UP
        25,  // VOLUME_DOWN
        26,  // POWER
        66,  // ENTER
        160, // NUMPAD_ENTER
        164, // VOLUME_MUTE
    )

    /**
     * 默认按键映射。
     * 注意：红键（183）默认设为 NONE，避免部分国产电视遥控器将常用功能键映射到 183 导致反复误触发静音。
     */
    val DEFAULTS: Map<Int, String> = linkedMapOf(
        KEY_STAR to KeyActions.TOGGLE_CLOUD_MARKET,
        KEY_0 to KeyActions.TOGGLE_CLOUD_MARKET,
        KEY_PROG_RED to KeyActions.NONE,
        KEY_PROG_GREEN to KeyActions.TOGGLE_PRIVACY,
        KEY_PROG_YELLOW to KeyActions.CYCLE_SORT,
        KEY_PROG_BLUE to KeyActions.CYCLE_LAYOUT,
        KEY_INFO to KeyActions.SHOW_QR,
        KEY_GUIDE to KeyActions.SHOW_QR,
        KEY_MENU to KeyActions.OPEN_SETTINGS,
        KEY_SETTINGS to KeyActions.OPEN_SETTINGS,
    )

    /** 供电视设置页和 Web 后台默认展示的常用键列表。 */
    val PRESET_KEYS = listOf(
        KEY_STAR,
        KEY_0,
        KEY_POUND,
        KEY_PROG_RED,
        KEY_PROG_GREEN,
        KEY_PROG_YELLOW,
        KEY_PROG_BLUE,
        KEY_INFO,
        KEY_MENU,
        KEY_MEDIA_PLAY_PAUSE,
    )

    fun isAllowed(keyCode: Int): Boolean = keyCode > 0 && keyCode !in PROTECTED_KEYS

    /** 返回某个键实际生效的动作：用户覆盖优先，否则取默认值，未配置返回 null。 */
    fun resolve(overrides: Map<Int, String>, keyCode: Int): String? =
        overrides[keyCode] ?: DEFAULTS[keyCode]

    /** 合并完整生效映射（预设键 + 用户自定义键）。 */
    fun effectiveMap(overrides: Map<Int, String>): Map<Int, String> {
        val out = LinkedHashMap<Int, String>(DEFAULTS)
        overrides.forEach { (k, v) -> if (isAllowed(k) && v in KeyActions.ALL) out[k] = v }
        return out
    }

    /** 找出绑定了指定动作的简短按键提示（如 * 或 0）。 */
    fun hintFor(overrides: Map<Int, String>, action: String): String {
        val map = effectiveMap(overrides)
        val keys = map.entries.filter { it.value == action }.map { shortKeyLabel(it.key) }.distinct()
        return when {
            keys.isEmpty() -> "菜单"
            keys.size <= 2 -> keys.joinToString("/")
            else -> keys.take(2).joinToString("/")
        }
    }

    fun shortKeyLabel(keyCode: Int): String = when (keyCode) {
        KEY_STAR -> "*"
        KEY_0 -> "0"
        KEY_POUND -> "#"
        KEY_PROG_RED -> "红键"
        KEY_PROG_GREEN -> "绿键"
        KEY_PROG_YELLOW -> "黄键"
        KEY_PROG_BLUE -> "蓝键"
        KEY_INFO -> "信息"
        KEY_GUIDE -> "指南"
        KEY_MENU -> "菜单"
        KEY_SETTINGS -> "设置"
        KEY_MEDIA_PLAY_PAUSE -> "播放"
        in 8..16 -> "数字${keyCode - 7}"
        else -> "键$keyCode"
    }

    fun keyLabel(keyCode: Int): String = when (keyCode) {
        KEY_STAR -> "* 键"
        KEY_0 -> "数字 0 键"
        KEY_POUND -> "# 键"
        KEY_PROG_RED -> "红键"
        KEY_PROG_GREEN -> "绿键"
        KEY_PROG_YELLOW -> "黄键"
        KEY_PROG_BLUE -> "蓝键"
        KEY_INFO -> "信息键"
        KEY_GUIDE -> "指南键"
        KEY_MENU -> "菜单键"
        KEY_SETTINGS -> "设置键"
        KEY_MEDIA_PLAY_PAUSE -> "播放/暂停键"
        in 8..16 -> "数字 ${keyCode - 7} 键"
        else -> "自定义键 #$keyCode"
    }

    fun toJson(map: Map<Int, String>): JSONObject = JSONObject().apply {
        map.forEach { (k, v) ->
            if (isAllowed(k) && v in KeyActions.ALL) put(k.toString(), v)
        }
    }

    fun fromJson(json: JSONObject?): Map<Int, String> {
        if (json == null) return emptyMap()
        val out = LinkedHashMap<Int, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val raw = keys.next()
            val code = raw.toIntOrNull() ?: continue
            val action = json.optString(raw, "")
            if (isAllowed(code) && action in KeyActions.ALL) {
                out[code] = action
            }
        }
        return out
    }
}
