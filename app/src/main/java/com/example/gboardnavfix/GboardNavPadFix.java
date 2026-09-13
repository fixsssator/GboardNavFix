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

    // Системные кнопки встроенной IME nav bar — скрываем безусловно,
    // это framework-классы, они всегда одна и та же функция.
    private static final Set<String> ALWAYS_HIDDEN_IDS = new HashSet<>(Arrays.asList(
            "input_method_nav_back",
            "input_method_nav_ime_switcher",
            "input_method_nav_home_handle"
    ));

    // ВАЖНО: этот ID у Gboard переиспользуется под разные функции в
    // зависимости от контекста поля ввода (иногда это переключатель
    // языка, а иногда — например в поиске по вкладкам — эмодзи-кнопка).
    // Поэтому скрываем его ТОЛЬКО когда contentDescription подтверждает,
    // что это реально язык — иначе рискуем спрятать что-то другое.
    private static final String LANGUAGE_KEY_ID = "key_pos_switch_to_next_language";

    private static boolean isLanguageCd(CharSequence cd) {
        if (cd == null) return false;
        String s = cd.toString().toLowerCase();
        return s.contains("language") || s.contains("язык");
    }

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

        // --- Скрываем системные элементы встроенной в IME-окно навигационной
        //     панели: "стрелку назад", "переключатель языка", home-хэндл.
        //     Хукаем setVisibility() напрямую (а не однократно при создании),
        //     т.к. система периодически включает их обратно (например,
        //     "Back" появляется/исчезает в зависимости от контекста). ---
        XposedHelpers.findAndHookMethod(
                View.class,
                "setVisibility",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        String idName = safeResName(v);
                        if (ALWAYS_HIDDEN_IDS.contains(idName)) {
                            if ((int) param.args[0] != View.GONE) {
                                log("forcing GONE on id=" + idName
                                        + " (was requesting visibility=" + param.args[0] + ")");
                                param.args[0] = View.GONE;
                            }
                        } else if (LANGUAGE_KEY_ID.equals(idName)
                                && isLanguageCd(v.getContentDescription())
                                && (int) param.args[0] != View.GONE) {
                            log("forcing GONE on language key (cd confirmed)");
                            param.args[0] = View.GONE;
                        }
                    }
                }
        );

        // --- Блокируем сам клик по скрываемым кнопкам, независимо от того,
        //     успела ли она уже стать GONE к моменту тапа (защита от гонки:
        //     если пользователь тапнет в долю секунды до сокрытия — "Back"
        //     всё равно не должен закрывать клавиатуру). ---
        XposedHelpers.findAndHookMethod(
                View.class,
                "performClick",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        String idName = safeResName(v);
                        if (ALWAYS_HIDDEN_IDS.contains(idName)) {
                            log("blocked click on id=" + idName);
                            param.setResult(false);
                        } else if (LANGUAGE_KEY_ID.equals(idName)
                                && isLanguageCd(v.getContentDescription())) {
                            log("blocked click on language key (cd confirmed)");
                            param.setResult(false);
                        }
                    }
                }
        );

        // Подстраховка: скрываем и сразу при первом setOnClickListener
        // (на случай если к этому моменту id уже назначен, а первый
        // setVisibility мог случиться раньше, чем этот хук успел встать).
        XposedHelpers.findAndHookMethod(
                View.class,
                "setOnClickListener",
                View.OnClickListener.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        String idName = safeResName(v);
                        if (ALWAYS_HIDDEN_IDS.contains(idName)) {
                            v.setVisibility(View.GONE);
                        }
                    }
                }
        );

        // --- Скрываем системные элементы, которые не ловятся через
        //     setVisibility() (см. выше) — Android при первичной инфляции
        //     XML применяет android:visibility напрямую во внутренние флаги
        //     view, в обход публичного setVisibility(). Поэтому скрываем
        //     явно здесь, как только view полностью создана и attached. ---
        XposedHelpers.findAndHookMethod(
                View.class,
                "onAttachedToWindow",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        String idName = safeResName(v);
                        if (ALWAYS_HIDDEN_IDS.contains(idName)) {
                            if (v.getVisibility() != View.GONE) {
                                log("force-hiding on attach, id=" + idName);
                                v.setVisibility(View.GONE);
                            }
                            v.setClickable(false);
                            v.setFocusable(false);
                        }
                    }
                }
        );

        // --- Решающая проверка для переиспользуемого слота
        //     key_pos_switch_to_next_language: скрываем ТОЛЬКО когда
        //     Gboard сам подтверждает через contentDescription, что в этой
        //     позиции сейчас реально язык, а не эмодзи или что-то ещё. ---
        XposedHelpers.findAndHookMethod(
                View.class,
                "setContentDescription",
                CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        String idName = safeResName(v);
                        if (LANGUAGE_KEY_ID.equals(idName)) {
                            CharSequence cd = (CharSequence) param.args[0];
                            if (isLanguageCd(cd)) {
                                log("hiding language key, cd=\"" + cd + "\"");
                                v.setVisibility(View.GONE);
                                v.setClickable(false);
                                v.setFocusable(false);
                            }
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

        // --- Отладка: полный дамп всех view из пакета
        //     android.inputmethodservice.navigationbar по мере их attach —
        //     на случай, если останется что-то ещё не учтённое. ---
        if (USE_NAVBAR_TREE_DUMP) {
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View v = (View) param.thisObject;
                            String cls = v.getClass().getName();
                            if (cls.startsWith("android.inputmethodservice.navigationbar")) {
                                log("navbar-tree: class=" + cls
                                        + " id=" + safeResName(v)
                                        + " visibility=" + v.getVisibility()
                                        + " cd=" + v.getContentDescription());
                            }
                        }
                    }
            );
        }
    }

    // Включи, если после скрытия известных ID всё ещё что-то видно —
    // покажет полное дерево системной IME nav bar.
    private static final boolean USE_NAVBAR_TREE_DUMP = true;

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
