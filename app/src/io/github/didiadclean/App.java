package io.github.didiadclean;

import android.app.Application;
import android.content.SharedPreferences;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** 设置页写 LSPosed RemotePreferences（同名 group），被 hook 进程只读。 */
public final class App extends Application implements XposedServiceHelper.OnServiceListener {
    private static volatile XposedService service;

    static SharedPreferences preferences() {
        XposedService bound = service;
        if (bound == null) return null;
        try {
            return bound.getRemotePreferences(Config.GROUP);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean write(String key, boolean value) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return false;
        try {
            return preferences.edit().putBoolean(key, value).commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean reset() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return false;
        try {
            return preferences.edit().clear().commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            XposedServiceHelper.registerListener(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onServiceBind(XposedService bound) {
        service = bound;
    }

    @Override
    public void onServiceDied(XposedService bound) {
        if (service == bound) service = null;
    }
}
