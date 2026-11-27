package com.pilot.agent.sandbox;

import android.os.Bundle;

/**
 * 宿主异步调用的结果回推（主进程 -> :sandbox）。
 * Bundle 约定键：ok:Boolean / unknown:Boolean（结果未知）/ err:String。
 * oneway：主进程回推不等待沙箱处理。
 */
interface IHostCallback {
    oneway void onHostResult(in Bundle data);
}
