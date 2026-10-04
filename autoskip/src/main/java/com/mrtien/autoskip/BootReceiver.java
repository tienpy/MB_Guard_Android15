package com.mrtien.autoskip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        boolean wanted = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_DNS_BLOCK_WANTED, false);
        if (!wanted) return;

        try {
            if (VpnService.prepare(context) == null) {
                Intent service = new Intent(context, DnsAdBlockVpnService.class)
                        .setAction(DnsAdBlockVpnService.ACTION_START);
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(service);
                } else {
                    context.startService(service);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
