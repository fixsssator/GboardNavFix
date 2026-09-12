package com.example.gboardnavfix;

import android.content.res.Resources;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Убирает пустой нижний отступ у Gboard, который система резервирует
 * под жестовую навигацию / nav bar.
 *
 * Идея: Gboard не хранит размер этого отступа в собственных (обфусцированных)
 * ресурсах — он читает стандартные системные dimen-ресурсы фреймворка
 * android:dimen/navigation_bar_height и android:dimen/navigation_bar_frame_height.
 * Хук перехватывает Resources.getDimensionPixelSize(int) и обнуляет результат,
 * если запрашиваемый ресурс — один из этих. Скоуп ограничен пакетом Gboard,
 * поэтому на остальную систему (SystemUI и т.д.) это не влияет.
 */
public class GboardNavPadFix implements IXposedHookLoadPackage {

    private static final String TAG = "GboardNavFix";
    private static final String GBOARD_PKG = "com.google.android.inputmethod.latin";

    // Имена framework dimen-ресурсов, отвечающих за высоту nav bar.
    private static final String[] TARGET_DIMEN_NAMES = {
            "navigation_bar_height",
            "navigation_bar_height_landscape",
            "navigation_bar_frame_height",
            "navigation_bar_width"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!GBOARD_PKG.equals(lpparam.packageName)) {
            return;
        }

        log("hooking into Gboard, process=" + lpparam.processName);

        // --- Основной хук: getDimensionPixelSize(int resId) ---
        XposedHelpers.findAndHookMethod(
                Resources.class,
                "getDimensionPixelSize",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        maybeZeroOut(param);
                    }
                }
        );

        // --- На всякий случай хукаем и getDimension(int resId) -> float ---
        XposedHelpers.findAndHookMethod(
                Resources.class,
                "getDimension",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int resId = (int) param.args[0];
                        Resources res = (Resources) param.thisObject;
                        if (isTargetDimen(res, resId)) {
                            param.setResult(0f);
                        }
                    }
                }
        );

        // --- Фолбэк-хук (по умолчанию выключен, см. USE_VIEW_FALLBACK) ---
        // Если после включения модуля полоса всё ещё видна — значит, Gboard
        // в этой версии не читает системный dimen, а получает высоту
        // через WindowInsets/setPadding напрямую. Раскомментируй блок ниже
        // и включи флаг, чтобы логировать все setPadding с ненулевым низом —
        // так можно быстро вычислить нужное значение эмпирически.
        if (USE_VIEW_FALLBACK_DEBUG_LOGGING) {
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "setPadding",
                    int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int bottom = (int) param.args[3];
                            if (bottom > 0) {
                                View v = (View) param.thisObject;
                                log("setPadding bottom=" + bottom
                                        + " on " + v.getClass().getName());
                            }
                        }
                    }
            );
        }
    }

    // Переключи в true и пересобери, если нужен режим отладки для поиска
    // класса/значения вручную через logcat (adb logcat | grep GboardNavFix).
    private static final boolean USE_VIEW_FALLBACK_DEBUG_LOGGING = false;

    private void maybeZeroOut(XC_MethodHook.MethodHookParam param) {
        int resId = (int) param.args[0];
        Resources res = (Resources) param.thisObject;
        if (isTargetDimen(res, resId)) {
            param.setResult(0);
        }
    }

    private boolean isTargetDimen(Resources res, int resId) {
        try {
            String name = res.getResourceEntryName(resId);
            if (name == null) return false;
            for (String target : TARGET_DIMEN_NAMES) {
                if (target.equals(name)) {
                    return true;
                }
            }
        } catch (Resources.NotFoundException ignored) {
            // resId мог быть не ресурсом (например, произвольное число) — игнорируем
        }
        return false;
    }

    private void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
