package com.pilot.agent.sandbox;

import android.os.Bundle;

/**
 * 命令结束回推。Bundle 约定键：
 *   out:String  stdout+stderr 合并
 *   exit:Int     退出码
 *   ms:Long      耗时
 * oneway：服务端广播不等待，避免慢 client 卡住沙箱 binder 线程。
 */
interface ResultCallback {
    oneway void onResult(long jobId, in Bundle data);
}
