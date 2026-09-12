package com.example.gboardnavfix;

import android.content.res.Resources;
import android.view.View;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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

    // Найдено эмпирически через отладочное логирование setPadding:
    // именно этот класс получает bottom=99 — корневой view клавиатуры.
    private static final String TARGET_VIEW_CLASS =
            "com.google.android.libraries.inputmethod.inputview.InputView";

    // Имена framework dimen-ресурсов, отвечающих за высоту nav bar
    // (оставлено на всякий случай — в тестах ни разу не сработало,
    // Gboard в этой версии берёт значение не отсюда).
    private static final String[] TARGET_DIMEN_NAMES = {
            "navigation_bar_height",
            "navigation_bar_height_landscape",
            "navigation_bar_frame_height",
            "navigation_bar_width"
    };

    // Системные кнопки встроенной IME nav bar, которые нужно скрыть.
    private static final Set<String> HIDDEN_NAV_BUTTON_IDS = new HashSet<>(Arrays.asList(
            "input_method_nav_back",
            "input_method_nav_ime_switcher"
    ));

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!GBOARD_PKG.equals(lpparam.packageName)) {
            return;
        }

        log("hooking into Gboard, process=" + lpparam.processName);

        // --- Основной рабочий хук: обнуляем bottom-padding у InputView ---
        XposedHelpers.findAndHookMethod(
                View.class,
                "setPadding",
                int.class, int.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (TARGET_VIEW_CLASS.equals(v.getClass().getName())) {
                            int bottom = (int) param.args[3];
                            if (bottom > 0) {
                                log("zeroing InputView bottom padding, was=" + bottom);
                                param.args[3] = 0;
                            }
                        }
                    }
                }
        );

        // Некоторые View используют setPaddingRelative вместо setPadding —
        // хукаем и его тем же способом на всякий случай.
        XposedHelpers.findAndHookMethod(
                View.class,
                "setPaddingRelative",
                int.class, int.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (TARGET_VIEW_CLASS.equals(v.getClass().getName())) {
                            int bottom = (int) param.args[3];
                            if (bottom > 0) {
                                log("zeroing InputView bottom padding (relative), was=" + bottom);
                                param.args[3] = 0;
                            }
                        }
                    }
                }
        );

        // --- Резервные хуки по имени ресурса (в тестах не срабатывали,
        //     оставлены на случай других версий Gboard) ---
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

        // --- Скрываем два конкретных системных элемента встроенной в IME-окно
        //     навигационной панели: "стрелку назад" и "переключатель языка".
        //     Найдены эмпирически через отладочное логирование ниже. ---
        XposedHelpers.findAndHookMethod(
                View.class,
                "setOnClickListener",
                View.OnClickListener.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        String idName = safeResName(v);
                        if (HIDDEN_NAV_BUTTON_IDS.contains(idName)) {
                            v.setVisibility(View.GONE);
                            log("hid system IME nav button id=" + idName);
                        }
                    }
                }
        );

        // --- Отладочное логирование (можно выключить, когда всё заработает) ---
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

        // --- Отладка для поиска кнопок нижнего тулбара (шеврон "свернуть",
        //     переключатель языка) по contentDescription — она обычно не
        //     обфусцирована, в отличие от имён ресурсов/классов. ---
        if (USE_TOOLBAR_DEBUG_LOGGING) {
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "setContentDescription",
                    CharSequence.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            CharSequence cd = (CharSequence) param.args[0];
                            if (cd != null && cd.length() > 0) {
                                View v = (View) param.thisObject;
                                log("contentDescription=\"" + cd + "\" on "
                                        + v.getClass().getName()
                                        + " id=" + safeResName(v));
                            }
                        }
                    }
            );

            // Некоторые кнопки не имеют contentDescription, но точно
            // ImageView с drawable — логируем на всякий случай и их создание.
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "setOnClickListener",
                    View.OnClickListener.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View v = (View) param.thisObject;
                            log("setOnClickListener on " + v.getClass().getName()
                                    + " id=" + safeResName(v)
                                    + " cd=" + v.getContentDescription());
                        }
                    }
            );
        }
    }

    // Включи, чтобы найти кнопки нижнего тулбара (шеврон/язык) через logcat.
    private static final boolean USE_TOOLBAR_DEBUG_LOGGING = false;

    private String safeResName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return "NO_ID";
            return v.getResources().getResourceEntryName(id);
        } catch (Exception e) {
            return "?";
        }
    }

    // Переключи в true и пересобери, если нужен режим отладки для поиска
    // класса/значения вручную через logcat (adb logcat | grep GboardNavFix).
    private static final boolean USE_VIEW_FALLBACK_DEBUG_LOGGING = false;

    private void maybeZeroOut(XC_MethodHook.MethodHookParam param) {
        int resId = (int) param.args[0];
        Resources res = (Resources) param.thisObject;
        if (isTargetDimen(res, resId)) {
            log("zeroing dimen, was=" + param.getResult());
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
