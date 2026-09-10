package com.mrtien.mbbankguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Đồng bộ màu icon sau khi cập nhật app hoặc khởi động lại điện thoại. */
public class LauncherIconSyncReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        LauncherIconController.sync(context);
    }
}
