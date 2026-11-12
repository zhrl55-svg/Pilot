package com.pilot.navigation;

import com.pilot.navigation.NavCallback;

/**
 * Pilot ⇄ :navigation 的导航接口。
 *
 * ## 为什么独立 AIDL
 * 导航是**重资源**功能（高德 so 87MB + 素材 48MB），最终要拆出主包
 * （见 NAV_MODULE_PLAN.md）。用 AIDL 而不是直接依赖，是为了让
 * 「:navigation 在同一个 APK 里」和「:navigation 是独立 APK」两种形态
 * 都能跑——接口不变，只换绑定方式。
 *
 * ## 调用都是快调用
 * startNavi 立即返回（导航是异步的：算路 → 定位 → 引导），
 * 过程状态经 [NavCallback] 异步回推。
 */
interface INavigationService {

    /**
     * 开始导航。
     * @param destination 目的地文本（地名/POI 名），内部做 POI 搜索解析
     * @param strategy 导航策略：0=推荐(驾车) 1=躲避拥堵 2=最短 3=高速优先
     * @return jobId，用于后续 stopNavi
     */
    long startNavi(String destination, int strategy);

    /**
     * 按指定出行方式开始导航。
     * @param travelMode 0=驾车 1=步行
     */
    long startNaviWithMode(String destination, int strategy, int travelMode);

    /** 仅供设备诊断触发模拟导航，目的地解析和路线规划流程与正式导航一致。 */
    long startSimulatedNavi(String destination);

    /** 停止导航。 */
    boolean stopNavi(long jobId);

    /** 当前导航状态（JSON 字符串）：未开始/算路中/导航中/到达/失败 */
    String getNavState();

    /** 注册状态回调（一次性）。 */
    void registerCallback(NavCallback cb);
}
