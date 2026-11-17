package com.naiwa.pet;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

/**
 * 入口：启动后直接运行桌宠并退出，不显示介绍页。
 * 无悬浮窗权限时跳转授权设置。
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 通知权限（Android 13+ 才需要）
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }

        if (canOverlay(this)) {
            startPetService();
            finish();
        } else {
            // Android 6.0+ 需要手动授权悬浮窗
            if (Build.VERSION.SDK_INT >= 23) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else {
                // Android 5.x 及以下：悬浮窗权限安装时自动授予
                startPetService();
            }
            finish();
        }
    }

    /** Android 6.0（API 23）以下没有 Settings.canDrawOverlays */
    private static boolean canOverlay(Activity a) {
        if (Build.VERSION.SDK_INT >= 23) {
            return Settings.canDrawOverlays(a);
        }
        return true;
    }

    /** Android 8.0（API 26）以下没有 startForegroundService */
    private void startPetService() {
        Intent i = new Intent(this, PetService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }
}
