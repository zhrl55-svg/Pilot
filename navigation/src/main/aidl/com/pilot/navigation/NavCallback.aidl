package com.pilot.navigation;

import android.os.Bundle;

/**
 * 导航状态回推。
 *
 * ## 关键：播报要并进统一 TTS 队列
 * 导航语音是**打断式**的——「前方 200 米右转」必须立刻说出来，
 * 不能等普通播报说完。所以回调里带一个 priority 字段，
 * Pilot 侧的 TTS 队列按它决定抢占还是排队。
 *
 * Bundle 约定键：
 *   event:String   事件类型（state / announce / arrive / error）
 *   text:String    要播报的话（announce 时有）
 *   priority:Int   0=普通 1=导航播报（抢占）2=紧急（立刻打断一切）
 *   state:String   导航状态机快照
 */
interface NavCallback {
    oneway void onEvent(long jobId, in Bundle data);
}
