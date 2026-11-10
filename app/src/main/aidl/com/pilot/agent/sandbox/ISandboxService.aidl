package com.pilot.agent.sandbox;

import com.pilot.agent.sandbox.ResultCallback;
import com.pilot.agent.sandbox.IHostBridge;

/**
 * 沙箱跨进程接口（主进程 ⇄ :sandbox）。
 *
 * 全部是**快调用**：startCommand 立即返回 jobId，结果经
 * ResultCallback 异步回推——避免 binder 同步事务在长命令（apk、下载）
 * 上撞分钟级超时。
 */
interface ISandboxService {
    long startCommand(String session, String command, long timeoutMs);
    boolean stopCommand(long jobId);
    void registerCallback(ResultCallback cb);
    void attachHost(IHostBridge host);
    String status();
}
