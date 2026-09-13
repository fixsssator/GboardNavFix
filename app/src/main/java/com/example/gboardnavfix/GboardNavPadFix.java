package com.example.gboardnavfix;

import android.content.Context;
import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

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

    // Проверяем теорию про спуфинг версии вместо рисования своей кнопки.
    private static final boolean SPOOF_VERSION_TO_DISABLE_EXPERIMENT = true;
    // Пока проверяем теорию — самодельную кнопку не вставляем, чтобы не
    // задваивалось. Если спуфинг не сработает, включим обратно.
    private static final boolean ADD_CUSTOM_GLOBE_BUTTON = false;

    private static void tryHook(String className, ClassLoader cl, String methodName,
                                 XC_MethodHook hook, Object... paramTypes) {
        try {
            Object[] args = Arrays.copyOf(paramTypes, paramTypes.length + 1);
            args[paramTypes.length] = hook;
            XposedHelpers.findAndHookMethod(className, cl, methodName, args);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": tryHook failed for " + methodName
                    + Arrays.toString(paramTypes) + ": " + t);
        }
    }

    // Класс системного контейнера IME nav bar — зануляем ему высоту при
    // measure (не трогая visibility, чтобы не ломать попап языка).
    private static final String NAV_BAR_FRAME_CLASS =
            "android.inputmethodservice.navigationbar.NavigationBarFrame";

    // Ссылка на запущенный сервис клавиатуры — нужна, чтобы наша кнопка
    // "глобус" могла дёрнуть переключение языка тем же системным методом,
    // которым обычно пользуется сама родная кнопка.
    private static volatile InputMethodService sImeService;

    private static final String GLOBE_TAG = "gboardnavfix_globe_button";

    // Сам контейнер системной IME nav bar. РАНЬШЕ мы прятали и его целиком
    // (не только дочерние кнопки) — но это ломало попап выбора языка при
    // долгом нажатии на пробел: попап позиционируется относительно этого
    // контейнера, а наше периодическое принудительное GONE сбивало его
    // измерение, и он тут же схлопывался. Поэтому теперь НЕ трогаем
    // контейнер, только конкретные кнопки внутри него (см. ALWAYS_HIDDEN_IDS).
    private static boolean isAlwaysHidden(View v, String idName) {
        return ALWAYS_HIDDEN_IDS.contains(idName);
    }

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

        // --- ТЕОРИЯ: server-side experiment-флаги (Phenotype/GServices)
        //     привязаны к конкретному versionCode/подписи APK. Когда юзер
        //     пересобирает Gboard под другой версией — флаг "спрячь родную
        //     кнопку языка, покажи вместо неё эмодзи" перестаёт совпадать,
        //     и приложение откатывается на дефолтное поведение из кода —
        //     с настоящей кнопкой языка. Подменяем versionCode/versionName
        //     именно в тот момент, когда Gboard спрашивает PackageManager
        //     САМ ПРО СЕБЯ (как это обычно делают перед запросом флагов). ---
        if (SPOOF_VERSION_TO_DISABLE_EXPERIMENT) {
            XC_MethodHook spoofHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String pkg = (String) param.args[0];
                        if (!GBOARD_PKG.equals(pkg)) return;
                        Object info = param.getResult();
                        if (info == null) return;
                        XposedHelpers.setIntField(info, "versionCode", 999999);
                        try {
                            XposedHelpers.setLongField(info, "versionCodeMajor", 0L);
                        } catch (Throwable ignored) {
                            // поле есть не на всех версиях API — не критично
                        }
                        XposedHelpers.setObjectField(info, "versionName", "999.0.0-spoof");
                        log("spoofed self versionCode/versionName for " + pkg);
                    } catch (Throwable t) {
                        log("version spoof failed: " + t);
                    }
                }
            };
            // Разные сигнатуры getPackageInfo на разных версиях Android —
            // хукаем какие получится, остальные молча пропускаем.
            tryHook("android.app.ApplicationPackageManager", lpparam.classLoader,
                    "getPackageInfo", spoofHook, String.class, int.class);
            try {
                Class<?> flagsClass = Class.forName(
                        "android.content.pm.PackageManager$PackageInfoFlags",
                        false, lpparam.classLoader);
                tryHook("android.app.ApplicationPackageManager", lpparam.classLoader,
                        "getPackageInfo", spoofHook, String.class, flagsClass);
            } catch (Throwable ignored) {
                // API < 33, такого перегруза нет — и не надо
            }
        }

        // --- Запоминаем инстанс сервиса клавиатуры — понадобится, чтобы
        //     наша кнопка "глобус" могла вызвать переключение языка. ---
        XposedHelpers.findAndHookMethod(
                InputMethodService.class,
                "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sImeService = (InputMethodService) param.thisObject;
                    }
                }
        );

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
                        if (isAlwaysHidden(v, idName)) {
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
                        if (isAlwaysHidden(v, idName)) {
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
                        if (isAlwaysHidden(v, idName)) {
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
                        if (isAlwaysHidden(v, idName)) {
                            if (v.getVisibility() != View.GONE) {
                                log("force-hiding on attach, id=" + idName);
                                v.setVisibility(View.GONE);
                            }
                            v.setClickable(false);
                            v.setFocusable(false);
                        }
                        // Слот языка занят эмодзи (серверный эксперимент
                        // Google прячет родную кнопку) — вставляем свою
                        // кнопку-глобус рядом с пробелом взамен.
                        if (ADD_CUSTOM_GLOBE_BUTTON && "key_pos_space".equals(idName)) {
                            addLanguageSwitchButton(v);
                        }
                    }
                }
        );

        // --- Зануляем ВЫСОТУ контейнера системной nav bar при measure —
        //     не трогая visibility (это и ломало попап языка раньше).
        //     Контейнер продолжает существовать и measure-иться нормально
        //     по ширине, но по высоте всегда получает 0. ---
        XposedHelpers.findAndHookMethod(
                NAV_BAR_FRAME_CLASS,
                lpparam.classLoader,
                "onMeasure",
                int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        try {
                            int width = v.getMeasuredWidth();
                            XposedHelpers.callMethod(v, "setMeasuredDimension", width, 0);
                        } catch (Throwable t) {
                            log("onMeasure override failed: " + t);
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
    private static final boolean USE_NAVBAR_TREE_DUMP = false;

    // Включи, чтобы найти кнопки нижнего тулбара (шеврон/язык) через logcat.
    private static final boolean USE_TOOLBAR_DEBUG_LOGGING = false;

    private void addLanguageSwitchButton(View spaceKey) {
        try {
            ViewParent parentObj = spaceKey.getParent();
            if (!(parentObj instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) parentObj;

            // Уже вставляли в этот ряд — не дублируем.
            if (parent.findViewWithTag(GLOBE_TAG) != null) return;

            Context ctx = spaceKey.getContext();
            TextView globe = new TextView(ctx);
            globe.setTag(GLOBE_TAG);
            globe.setText("\uD83C\uDF10"); // 🌐
            globe.setGravity(Gravity.CENTER);
            float textSize = spaceKey.getHeight() > 0
                    ? spaceKey.getHeight() / 2.5f
                    : 22f;
            globe.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize);
            globe.setClickable(true);
            globe.setFocusable(true);

            ViewGroup.LayoutParams spaceParams = spaceKey.getLayoutParams();
            ViewGroup.LayoutParams newParams;
            if (spaceParams instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams src = (LinearLayout.LayoutParams) spaceParams;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(src);
                lp.weight = src.weight > 0 ? src.weight * 0.4f : 0;
                if (lp.weight == 0) {
                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                }
                newParams = lp;
            } else {
                newParams = new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
            }

            globe.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    InputMethodService svc = sImeService;
                    if (svc != null) {
                        try {
                            svc.switchToNextInputMethod(false);
                        } catch (Throwable t) {
                            log("switchToNextInputMethod failed: " + t);
                        }
                    } else {
                        log("no ime service reference yet, cannot switch language");
                    }
                }
            });

            int index = parent.indexOfChild(spaceKey);
            parent.addView(globe, Math.max(index, 0), newParams);
            log("inserted globe language-switch button next to space");
        } catch (Throwable t) {
            log("failed to insert globe button: " + t);
        }
    }

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
