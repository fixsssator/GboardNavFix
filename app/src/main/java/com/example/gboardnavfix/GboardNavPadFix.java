package com.example.gboardnavfix;

import android.app.Application;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.ContentProvider;
import android.content.pm.PackageInfo;
import android.view.View;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Gboard 18.2.4 beta / Android 17.
 *
 * On every fresh start of Gboard's main process, remove ONLY the cached
 * Phenotype / Jetpack flag state that controls experiments.  User data such
 * as the personal dictionary, history and emoji data is not touched.
 *
 * The deletion is performed from Application.attach(), before Application
 * onCreate(), so the cache is gone before normal Gboard initialization.
 *
 * We also spoof only Gboard's versionCode/versionName when it asks the
 * PackageManager for its own PackageInfo.  Signature spoofing and the global
 * Signature.equals() hook are deliberately NOT used.
 *
 * A narrow InputView.setPadding fallback remains as a visual safety net.
 */
public final class GboardNavPadFix implements IXposedHookLoadPackage {

    private static final String TAG = "GboardNavFix";
    private static final String GBOARD = "com.google.android.inputmethod.latin";

    private static final String CONFIG_BUILDER = "Laeuk";
    private static final String CONFIG_VALUE = "Laeov";
    private static final String LARZO = "Larzo";

    private static final String INPUT_VIEW =
            "com.google.android.libraries.inputmethod.inputview.InputView";

    private static final int SPOOF_VERSION_CODE = 999999;
    private static final String SPOOF_VERSION_NAME = "999.0.0-spoof";

    private static boolean cacheCleanupInstalled;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!GBOARD.equals(lpparam.packageName)) {
            return;
        }

        log(">>> PROCESS START <<< " + lpparam.processName);
        log("module build: CACHE_CLEANUP_DIAG_2");

        // Do this first: Application.attach() happens before Application.onCreate().
        installEarlyCacheCleanup();
        installProviderCacheCleanup(lpparam.classLoader);
        installVersionSpoof(lpparam.classLoader);

        // Keep the exact config hook as a fallback/diagnostic. It does not
        // modify any stored data and only affects keyboardPaddingEnabled.
        hookKeyboardPaddingConfig(lpparam.classLoader);
        hookInputViewPadding();
    }

    /**
     * Deletes only:
     *   files/phenotype/
     *   files/phenotype_storage_info/
     *   files/datastore/flags_jetpack_data_store.pb
     *
     * No recursive deletion of the whole files/ or datastore/ directory.
     */
    /**
     * Provider fallback: some Gboard flag/Phenotype components may initialize
     * through a ContentProvider before Application.attach().  Hook both
     * ContentProvider.attachInfo overloads and clean the exact same three
     * paths from the provider context.
     */
    private static void installProviderCacheCleanup(ClassLoader cl) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Context context = (Context) param.args[0];
                    if (context == null) return;
                    log(">>> PROVIDER attachInfo: " + param.thisObject.getClass().getName());
                    cleanupCaches(context, "provider");
                } catch (Throwable t) {
                    log("provider cache cleanup failed: " + t);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(ContentProvider.class, "attachInfo",
                    Context.class, ProviderInfo.class, hook);
            log("hooked ContentProvider.attachInfo(Context,ProviderInfo)");
        } catch (Throwable t) {
            log("ContentProvider.attachInfo hook failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(ContentProvider.class, "attachInfo",
                    Context.class, ProviderInfo.class, boolean.class, hook);
            log("hooked ContentProvider.attachInfo(Context,ProviderInfo,boolean)");
        } catch (Throwable t) {
            log("ContentProvider.attachInfo(3) unavailable: " + t);
        }
    }

    private static void cleanupCaches(Context context, String source) {
        File files = context.getFilesDir();
        if (files == null) {
            log(source + ": filesDir is null");
            return;
        }
        log(source + ": filesDir=" + files.getAbsolutePath());
        deleteExact(new File(files, "phenotype"));
        deleteExact(new File(files, "phenotype_storage_info"));
        deleteExact(new File(new File(files, "datastore"),
                "flags_jetpack_data_store.pb"));
    }

    private static synchronized void installEarlyCacheCleanup() {
        if (cacheCleanupInstalled) {
            return;
        }
        cacheCleanupInstalled = true;

        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "attach",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Context context = (Context) param.args[0];
                                if (context == null) {
                                    return;
                                }

                                cleanupCaches(context, "Application.attach");
                            } catch (Throwable t) {
                                log("early cache cleanup failed: " + t);
                            }
                        }
                    }
            );
            log("hooked Application.attach() for early flag-cache cleanup");
        } catch (Throwable t) {
            log("Application.attach hook failed: " + t);
        }
    }

    private static void deleteExact(File target) {
        try {
            if (!target.exists()) {
                log("cache not present: " + target.getAbsolutePath());
                return;
            }

            boolean ok = deleteRecursively(target);
            log((ok ? "deleted cache: " : "FAILED to delete cache: ")
                    + target.getAbsolutePath());
        } catch (Throwable t) {
            log("delete failed for " + target + ": " + t);
        }
    }

    private static boolean deleteRecursively(File file) {
        boolean ok = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        ok = false;
                    }
                }
            }
        }
        return file.delete() && ok;
    }

    /** Spoof only self PackageInfo; do not touch signatures. */
    private static void installVersionSpoof(ClassLoader cl) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    String pkg = (String) param.args[0];
                    if (!GBOARD.equals(pkg)) {
                        return;
                    }

                    Object result = param.getResult();
                    if (!(result instanceof PackageInfo)) {
                        return;
                    }

                    PackageInfo info = (PackageInfo) result;
                    info.versionCode = SPOOF_VERSION_CODE;
                    info.versionName = SPOOF_VERSION_NAME;

                    try {
                        info.getClass().getField("longVersionCode")
                                .setLong(info, SPOOF_VERSION_CODE);
                    } catch (Throwable ignored) {
                        // API/hidden-field differences; versionCode is enough.
                    }
                } catch (Throwable t) {
                    log("version spoof failed: " + t);
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.ApplicationPackageManager", cl,
                    "getPackageInfo", String.class, int.class, hook);
            log("hooked PackageManager.getPackageInfo(String,int)");
        } catch (Throwable t) {
            log("legacy PackageManager hook failed: " + t);
        }

        try {
            Class<?> flagsClass = Class.forName(
                    "android.content.pm.PackageManager$PackageInfoFlags", false, cl);
            XposedHelpers.findAndHookMethod(
                    "android.app.ApplicationPackageManager", cl,
                    "getPackageInfo", String.class, flagsClass, hook);
            log("hooked PackageManager.getPackageInfo(String,PackageInfoFlags)");
        } catch (Throwable t) {
            log("PackageInfoFlags hook unavailable: " + t);
        }
    }

    private static void hookKeyboardPaddingConfig(ClassLoader cl) {
        try {
            final Class<?> builder = XposedHelpers.findClass(CONFIG_BUILDER, cl);
            final Class<?> larzo = XposedHelpers.findClass(LARZO, cl);

            XposedHelpers.findAndHookMethod(
                    builder,
                    "a",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (result == null
                                    || !CONFIG_VALUE.equals(result.getClass().getName())) {
                                return;
                            }

                            Object disabled = createFalseLarzo(larzo, cl);
                            if (disabled == null) {
                                return;
                            }

                            try {
                                XposedHelpers.setObjectField(result, "u", disabled);
                                log("forced keyboardPaddingEnabled=false in Laeov.u");
                            } catch (Throwable t) {
                                log("failed to replace Laeov.u: " + t);
                            }
                        }
                    }
            );

            log("hooked " + CONFIG_BUILDER + ".a() -> " + CONFIG_VALUE + ".u");
        } catch (Throwable t) {
            log("keyboard padding config hook failed: " + t);
        }
    }

    private static Object createFalseLarzo(final Class<?> larzo, ClassLoader cl) {
        try {
            return Proxy.newProxyInstance(
                    cl,
                    new Class<?>[]{larzo},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("gV".equals(name) && method.getParameterTypes().length == 0) {
                            return Boolean.FALSE;
                        }
                        if ("equals".equals(name)) {
                            return proxy == (args == null ? null : args[0]);
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("toString".equals(name)) {
                            return "GboardNavFix.Larzo(false)";
                        }
                        return defaultValue(method.getReturnType());
                    }
            );
        } catch (Throwable t) {
            log("cannot create Larzo proxy: " + t);
            return null;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }

    private static void hookInputViewPadding() {
        try {
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "setPadding",
                    int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            View view = (View) param.thisObject;
                            if (!INPUT_VIEW.equals(view.getClass().getName())) {
                                return;
                            }

                            int bottom = (Integer) param.args[3];
                            if (bottom > 0) {
                                log("zeroing InputView bottom padding, was=" + bottom);
                                param.args[3] = 0;
                            }
                        }
                    }
            );
            log("hooked InputView.setPadding fallback");
        } catch (Throwable t) {
            log("InputView.setPadding hook failed: " + t);
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
