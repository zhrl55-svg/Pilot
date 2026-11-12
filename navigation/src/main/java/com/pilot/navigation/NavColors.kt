package com.pilot.navigation

import android.graphics.Color

/**
 * 导航界面的配色（与主界面 Compose 主题保持一致）。
 *
 * ## 为什么要单独定义一份
 * 主界面是 Compose（`PilotTheme`，见 app 的 Theme.kt），导航界面是传统 View，
 * 拿不到 `MaterialTheme.colorScheme`。所以把同一套色值抄过来，
 * 两边看起来才是一个 App（老板要求「统一配色」）。
 *
 * 主界面主题（darkColorScheme）：
 *   primary #6FD3C0  background #0E1116  surface #161A21
 *   surfaceVariant #1E242D  onSurface #E7EBF2  onSurfaceVariant #9AA6B5
 *   outline #33404F  error #EF7A6D
 *
 * ## 踩过的坑
 * 之前设置面板用**默认 AlertDialog**，导航界面是全屏黑主题，
 * Dialog 继承黑底 + 系统默认的深色文字 → 一片黑看不清（老板反馈）。
 * 现在显式用深色底（surface）+ **浅色文字**（onSurface），对比度够。
 */
object NavColors {

    /** 强调色（主界面的 primary，青绿）。 */
    val PRIMARY = Color.parseColor("#6FD3C0")

    /** 强调色的深色版（按钮按下/次级强调）。 */
    val PRIMARY_DARK = Color.parseColor("#4FB8A6")

    /** 面板/卡片背景（主界面的 surface）。 */
    val SURFACE = Color.parseColor("#161A21")

    /** 次级背景（输入框、列表卡片，主界面的 surfaceVariant）。 */
    val SURFACE_VARIANT = Color.parseColor("#1E242D")

    /** 主文字（浅色，主界面的 onSurface）。 */
    val ON_SURFACE = Color.parseColor("#E7EBF2")

    /** 次级文字（主界面的 onSurfaceVariant）。 */
    val ON_SURFACE_VARIANT = Color.parseColor("#9AA6B5")

    /** 描边/分隔线（主界面的 outline）。 */
    val OUTLINE = Color.parseColor("#33404F")

    /** 危险/错误（主界面的 error）。 */
    val ERROR = Color.parseColor("#EF7A6D")

    /**
     * 悬浮按钮底色（半透明灰）。
     *
     * 老板要求「四个按钮统一底色和圆边框」：都用这一个 + 同一个圆角半径，
     * 视觉上是一组。半透明是为了不挡住地图。
     *
     * 老板要求「右侧按钮底色灰色、透明度 80%」，随后又要求「再加大一点透明度」：
     * `#B39E9E9E` = 中灰 + 70% 不透明度（alpha 0xB3）。
     * 比 80% 更透，压在地图上不抢路线。
     */
    val FLOATING_BTN = Color.parseColor("#B39E9E9E")

    /** 悬浮按钮圆角半径（dp）。 */
    const val FLOATING_BTN_RADIUS_DP = 24

    /** 悬浮按钮尺寸（dp，正方形 = 圆角矩形看起来是圆/药丸）。 */
    const val FLOATING_BTN_SIZE_DP = 48
}
