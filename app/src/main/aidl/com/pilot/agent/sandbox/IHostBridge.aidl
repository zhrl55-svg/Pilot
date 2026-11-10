package com.pilot.agent.sandbox;

import android.os.Bundle;

/**
 * 主进程反向通道：:sandbox 里的 offload handler 借它回宿主
 * （开 App 这类必须用主进程的无障碍上下文）。
 * req 形如 "open:微信"，返回 Bundle（键 ok:Boolean / err:String）。
 */
interface IHostBridge {
    Bundle call(String req);
}
