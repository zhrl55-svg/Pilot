package com.pilot.agent.sandbox;

import android.os.Bundle;
import com.pilot.agent.sandbox.IHostCallback;

/**
 * 主进程反向通道：:sandbox 里的 offload handler 借它回宿主。
 *
 * call      —— 只用于**快调用**（如 "idleRecycle"），主进程 binder 线程上立即返回。
 * callAsync —— 屏幕/启动类操作（如 "open:微信"）。oneway，主进程立即返回，
 *              在协程里经 Guard 判定 + 审计后执行，带超时，结果经 IHostCallback 回推。
 *              绝不在 binder 线程上阻塞等待 UI 操作。
 * 返回 Bundle 键：ok:Boolean / unknown:Boolean / err:String。
 */
interface IHostBridge {
    Bundle call(String req);
    oneway void callAsync(String req, IHostCallback cb);
}
