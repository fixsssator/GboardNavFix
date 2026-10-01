package com.example.gboardnavfix;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Gboard Nav Pad Fix.
 *
 * Убирает пустую полосу под клавиатурой Gboard на Pixel (gesture nav).
 *
 *   1. InputView.onMeasure → setMeasuredDimension(w, mh + extra)
 *   2. FrameLayout (ребёнок InputView).onMeasure → setMeasuredDimension(w, mh + extra),
 *      перед этим обнуляется его нижний padding (Gboard сам подмешивает туда инсет).
 *   3. Инсеты captionBar / tappableElement / mandatorySystemGestures вырезаются
 *      на входе в InputView, чтобы Gboard не добавлял свои ~99px внутрь раскладки.
 *   4. Скрытие captionBar через WindowInsetsController (Android 13+).
 *   5. Пост-проверки (один набор на View) догоняют правильную высоту.
 *   6. Диагностика: при скачке высоты ребёнка InputView (> 50px) в лог пишется
 *      дерево вью (высота, padB, marB, visibility) — по нему видно, откуда лишние px.
 *
 * extra = max(captionBar, navigationBars) из insets (кэш по ориентации),
 * запасной вариант — системный dimen, затем STRETCH_PX.
 */
public class GboardNavPadFix implements IXposedHookLoadPackage {

    private static final String TAG = "GboardNavFix";
    private static final String GBOARD_PKG = "com.google.android.inputmethod.latin";

    private static final String TARGET_VIEW_CLASS =
            "com.google.android.libraries.inputmethod.inputview.InputView";

    private static final String NAV_BAR_FRAME_CLASS =
            "android.inputmethodservice.navigationbar.NavigationBarFrame";

    /** Запасное значение растяжки (раньше было захардкожено). */
    private static final int STRETCH_PX = 99;

    /**
     * true  — брать высоту из insets / системного dimen (реальное значение пишется в лог).
     * false — всегда STRETCH_PX (старое поведение).
     */
    private static final boolean USE_DYNAMIC_STRETCH = true;

    /**
     * Вырезать captionBar/tappable/mandatory-gestures инсеты у InputView.
     * По логам это НЕ убирает padding 99 (он приходит откуда-то выше по иерархии),
     * а Gboard может по этим инсетам решать, показывать ли глобус, поэтому выключено.
     */
    private static final boolean STRIP_INSETS_FOR_INPUT_VIEW = false;

    /** Порог скачка высоты ребёнка InputView, после которого пишем дерево вью. */
    private static final int JUMP_DUMP_THRESHOLD_PX = 50;

    private static final int[] FIX_DELAYS = {0, 100, 300, 700, 1500};

    private static final String[] TARGET_DIMEN_NAMES = {
            "navigation_bar_height",
            "navigation_bar_height_landscape",
            "navigation_bar_frame_height",
            "navigation_bar_frame_height_landscape",
            "navigation_bar_width",
            "navigation_bar_gesture_height"
    };

    private static final Set<String> ALWAYS_HIDDEN_IDS = new HashSet<>(Arrays.asList(
            "input_method_nav_back",
            "input_method_nav_ime_switcher",
            "input_method_nav_home_handle"
    ));

    private static final String LANGUAGE_KEY_ID = "key_pos_switch_to_next_language";

    private static final boolean SPOOF_VERSION_TO_DISABLE_EXPERIMENT = true;
    private static final boolean ADD_CUSTOM_GLOBE_BUTTON = false;
    /** Логи измерений (пишутся только при изменении значений). */
    private static final boolean DEBUG_DUMP = true;
    /**
     * true  — чистить phenotype-кэш только при первом запуске.
     * false — чистить при каждом старте процесса (как в старой сборке).
     * Пока ищем причину «клавиатура снова поднимается» — false.
     */
    private static final boolean WIPE_PHENOTYPE_ONLY_ONCE = false;

    private static final String[] PHENOTYPE_CACHE_PATHS = {
            "files/phenotype",
            "files/phenotype_storage_info",
            "files/datastore/flags_jetpack_data_store.pb"
    };

    private static final String WIPE_MARKER = "files/.gboardnavfix_wiped";
    private static final String REPURPOSED_TAG = "gboardnavfix_repurposed";

    private static volatile InputMethodService sImeService;
    private static volatile Drawable sLanguageIconDrawable;

    // Обход собственных хуков на Resources при чтении системных dimen
    private static final ThreadLocal<Boolean> BYPASS = new ThreadLocal<>();

    // Кэши
    private static final ConcurrentHashMap<Integer, String> NAME_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Boolean> DIMEN_CACHE = new ConcurrentHashMap<>();
    /** [портрет, ландшафт] */
    private static final int[] sNavCache = {-1, -1};

    // Один набор пост-проверок на View
    private static final Set<View> FIX_PENDING = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>()));

    // Для логов «только при изменении»
    private static volatile int sLastInputViewH = -1;
    private static volatile int sLastChildH = -1;
    private static volatile int sLastChildMh = -1;
    private static volatile String sLastLayoutSig = "";

    private static Method sSetMeasured;

    // ===================== Helpers =====================

    private static void tryHook(String className, ClassLoader cl, String methodName,
                                XC_MethodHook hook, Object... paramTypes) {
        try {
            Object[] args = Arrays.copyOf(paramTypes, paramTypes.length + 1);
            args[paramTypes.length] = hook;
            XposedHelpers.findAndHookMethod(className, cl, methodName, args);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": tryHook failed for " + className + "." + methodName
                    + Arrays.toString(paramTypes) + ": " + t);
        }
    }

    private static void tryHookClass(Class<?> cls, String methodName,
                                     XC_MethodHook hook, Object... paramTypes) {
        try {
            Object[] args = Arrays.copyOf(paramTypes, paramTypes.length + 1);
            args[paramTypes.length] = hook;
            XposedHelpers.findAndHookMethod(cls, methodName, args);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": tryHookClass failed for " + cls.getSimpleName() + "."
                    + methodName + Arrays.toString(paramTypes) + ": " + t);
        }
    }

    private static boolean isAlwaysHidden(String idName) {
        return ALWAYS_HIDDEN_IDS.contains(idName);
    }

    private static boolean isLanguageCd(CharSequence cd) {
        if (cd == null) return false;
        String s = cd.toString().toLowerCase();
        return s.contains("language") || s.contains("язык");
    }

    /**
     * Клавишу смены языка (глобус) прячем и блокируем ТОЛЬКО когда спуф выключен.
     * При SPOOF_VERSION_TO_DISABLE_EXPERIMENT = true она должна оставаться видимой и кликабельной
     * (раньше setVisibility/performClick прятали её всегда, хотя в логе писалось «real language key visible»).
     */
    private static boolean shouldSuppressLanguageKey(View v, String idName) {
        return !SPOOF_VERSION_TO_DISABLE_EXPERIMENT
                && LANGUAGE_KEY_ID.equals(idName)
                && isLanguageCd(v.getContentDescription());
    }

    private static boolean isNavBarFrame(View v) {
        return v != null && NAV_BAR_FRAME_CLASS.equals(v.getClass().getName());
    }

    private static boolean isInputView(View v) {
        return v != null && TARGET_VIEW_CLASS.equals(v.getClass().getName());
    }

    /** true, если v — прямой ребёнок InputView. */
    private static boolean isInputViewChild(View v) {
        ViewParent parent = v.getParent();
        return parent instanceof View && isInputView((View) parent);
    }

    private static void callSetMeasuredDimension(View v, int w, int h) {
        try {
            if (sSetMeasured == null) {
                Method m = View.class.getDeclaredMethod("setMeasuredDimension",
                        int.class, int.class);
                m.setAccessible(true);
                sSetMeasured = m;
            }
            sSetMeasured.invoke(v, w, h);
        } catch (Throwable t) {
            try {
                XposedHelpers.callMethod(v, "setMeasuredDimension", w, h);
            } catch (Throwable t2) {
                XposedBridge.log(TAG + ": setMeasuredDimension failed: " + t2);
            }
        }
    }

    /** Кэшированное имя id-ресурса View. */
    private static String resName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "NO_ID";
        String cached = NAME_CACHE.get(id);
        if (cached != null) return cached;
        String name;
        try {
            name = v.getResources().getResourceEntryName(id);
        } catch (Exception e) {
            name = "?";
        }
        NAME_CACHE.put(id, name);
        return name;
    }

    /** Сколько добавить к высоте. */
    private static int navBarPx(View v) {
        if (!USE_DYNAMIC_STRETCH) return STRETCH_PX;

        int idx = 0;
        try {
            idx = v.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE ? 1 : 0;
        } catch (Throwable ignored) {}

        int cached = sNavCache[idx];
        if (cached > 0) return cached;

        int caption = 0;
        int navBars = 0;
        try {
            WindowInsets ins = v.getRootWindowInsets();
            if (ins != null) {
                try {
                    caption = ins.getInsetsIgnoringVisibility(
                            WindowInsets.Type.captionBar()).bottom;
                } catch (Throwable ignored) {}
                try {
                    navBars = ins.getInsetsIgnoringVisibility(
                            WindowInsets.Type.navigationBars()).bottom;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        int fromInsets = Math.max(caption, navBars);

        int fromDimen = 0;
        try {
            BYPASS.set(Boolean.TRUE);
            Resources r = Resources.getSystem();
            int id = r.getIdentifier("navigation_bar_height", "dimen", "android");
            if (id != 0) fromDimen = r.getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        } finally {
            BYPASS.remove();
        }

        if (fromInsets > 0) {
            sNavCache[idx] = fromInsets;
            log("nav stretch px=" + fromInsets + " (insets: caption=" + caption
                    + ", navBars=" + navBars + "), systemDimen=" + fromDimen
                    + ", const=" + STRETCH_PX + ", orientation=" + (idx == 1 ? "land" : "port"));
            return fromInsets;
        }
        // insets ещё нет — не кэшируем, чтобы перечитать позже
        if (fromDimen > 0) return fromDimen;
        return STRETCH_PX;
    }

    private void forceZeroNavBarFrame(View v) {
        if (v == null) return;
        try {
            v.setVisibility(View.GONE);
            v.setMinimumHeight(0);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                lp.height = 0;
                try { v.setLayoutParams(lp); } catch (Throwable ignored) {}
            }
            v.setClickable(false);
            v.setFocusable(false);
            v.setEnabled(false);
        } catch (Throwable t) {
            log("forceZeroNavBarFrame failed: " + t);
        }
    }

    /** Один набор отложенных проверок на View (не копится при повторных onMeasure). */
    private void scheduleFixChecks(final View v) {
        if (!FIX_PENDING.add(v)) return;
        final int last = FIX_DELAYS[FIX_DELAYS.length - 1];
        for (final int delay : FIX_DELAYS) {
            v.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        int actualH = v.getHeight();
                        int targetH = v.getMeasuredHeight();
                        if (targetH > 0 && actualH != targetH) {
                            log("fix[" + delay + "]: " + actualH + " → " + targetH);
                            v.requestLayout();
                        }
                    } catch (Throwable t) {
                        log("fix[" + delay + "] failed: " + t);
                    }
                    if (delay == last) FIX_PENDING.remove(v);
                }
            }, delay);
        }
    }

    /** Дерево вью для поиска источника лишних пикселей. */
    private static void dumpTree(View v, int depth, StringBuilder sb) {
        for (int i = 0; i < depth; i++) sb.append("  ");
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        sb.append(v.getClass().getSimpleName())
                .append(" id=").append(resName(v))
                .append(" h=").append(v.getMeasuredHeight())
                .append(" padB=").append(v.getPaddingBottom());
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            sb.append(" marB=").append(((ViewGroup.MarginLayoutParams) lp).bottomMargin);
        }
        sb.append(" vis=").append(v.getVisibility()).append('\n');
        if (v instanceof ViewGroup && depth < 6) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpTree(g.getChildAt(i), depth + 1, sb);
            }
        }
    }

    private void dumpWindowInsets(InputMethodService svc) {
        try {
            Window window = svc.getWindow().getWindow();
            if (window == null) return;
            View decor = window.getDecorView();
            if (decor == null) return;
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets == null) return;
            log("Insets: sysBars=" + insets.getInsets(WindowInsets.Type.systemBars()).bottom
                    + ", navBars=" + insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                    + ", captionBar=" + insets.getInsets(WindowInsets.Type.captionBar()).bottom
                    + ", ime=" + insets.getInsets(WindowInsets.Type.ime()).bottom
                    + ", mandatorySys=" + insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom
                    + ", tappableElement=" + insets.getInsets(WindowInsets.Type.tappableElement()).bottom);
        } catch (Throwable t) {
            log("dumpWindowInsets failed: " + t);
        }
    }

    private void hideCaptionBar(InputMethodService svc) {
        try {
            Window window = svc.getWindow().getWindow();
            if (window == null) return;
            View decor = window.getDecorView();
            if (decor == null) return;

            try {
                android.view.WindowInsetsController ctrl =
                        decor.getWindowInsetsController();
                if (ctrl != null) {
                    ctrl.hide(WindowInsets.Type.captionBar());
                    log("hide captionBar requested");
                } else {
                    log("hide captionBar: controller is null");
                }
            } catch (Throwable t) {
                log("hide captionBar failed: " + t);
            }

            try {
                decor.requestApplyInsets();
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            log("hideCaptionBar outer failed: " + t);
        }
    }

    // ===================== Main =====================

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!GBOARD_PKG.equals(lpparam.packageName)) {
            return;
        }

        log("hooking into Gboard, process=" + lpparam.processName);

        wipePhenotypeCache();
        installCrashGuard();

        // ============ Спуфинг версии/подписи ============
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
                        } catch (Throwable ignored) {}
                        XposedHelpers.setObjectField(info, "versionName", "999.0.0-spoof");
                        spoofSignatureFields(info);
                    } catch (Throwable t) {
                        log("version spoof failed: " + t);
                    }
                }
            };

            tryHook("android.app.ApplicationPackageManager", lpparam.classLoader,
                    "getPackageInfo", spoofHook, String.class, int.class);

            try {
                Class<?> flagsClass = Class.forName(
                        "android.content.pm.PackageManager$PackageInfoFlags",
                        false, lpparam.classLoader);
                tryHook("android.app.ApplicationPackageManager", lpparam.classLoader,
                        "getPackageInfo", spoofHook, String.class, flagsClass);
            } catch (Throwable ignored) {}
        }

        // ============ InputMethodService ============
        tryHookClass(InputMethodService.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                sImeService = (InputMethodService) param.thisObject;
            }
        });

        // ============ setInputView ============
        tryHookClass(InputMethodService.class, "setInputView", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // сброс кэша высоты навбара (мог смениться режим навигации)
                sNavCache[0] = -1;
                sNavCache[1] = -1;
                View inputView = (View) param.args[0];
                if (inputView != null) {
                    int bottom = inputView.getPaddingBottom();
                    if (bottom > 0) {
                        log("setInputView: zeroing bottom padding, was=" + bottom);
                        inputView.setPadding(
                                inputView.getPaddingLeft(),
                                inputView.getPaddingTop(),
                                inputView.getPaddingRight(),
                                0
                        );
                    }
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                InputMethodService svc = (InputMethodService) param.thisObject;
                hideCaptionBar(svc);
                dumpWindowInsets(svc);
            }
        }, View.class);

        // ============ Вырезаем инсеты у InputView ============
        if (STRIP_INSETS_FOR_INPUT_VIEW) {
            tryHookClass(View.class, "dispatchApplyWindowInsets", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    View v = (View) param.thisObject;
                    if (!isInputView(v)) return;
                    try {
                        WindowInsets in = (WindowInsets) param.args[0];
                        if (in == null) return;
                        param.args[0] = new WindowInsets.Builder(in)
                                .setInsets(WindowInsets.Type.captionBar(), Insets.NONE)
                                .setInsets(WindowInsets.Type.tappableElement(), Insets.NONE)
                                .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.NONE)
                                .build();
                    } catch (Throwable t) {
                        log("insets strip failed: " + t);
                    }
                }
            }, WindowInsets.class);
        }

        // ============ Иконка языка ============
        tryHook("android.inputmethodservice.navigationbar.KeyButtonView",
                lpparam.classLoader, "setImageDrawable", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if ("input_method_nav_ime_switcher".equals(resName(v))) {
                            Drawable d = (Drawable) param.args[0];
                            if (d != null && sLanguageIconDrawable == null) {
                                try {
                                    Drawable fresh = d.getConstantState() != null
                                            ? d.getConstantState().newDrawable().mutate()
                                            : d;
                                    sLanguageIconDrawable = fresh;
                                    log("captured real language icon drawable");
                                } catch (Throwable t) {
                                    log("failed to capture icon drawable: " + t);
                                }
                            }
                        }
                    }
                }, Drawable.class);

        // ============ setPadding ============
        tryHookClass(View.class, "setPadding", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                int bottom = (int) param.args[3];
                if (bottom <= 0) return;
                if (isInputView(v)) {
                    log("zeroing InputView bottom padding, was=" + bottom);
                    param.args[3] = 0;
                } else if (isNavBarFrame(v)) {
                    param.args[3] = 0;
                } else if (isInputViewChild(v)) {
                    log("zeroing InputView child bottom padding, was=" + bottom
                            + " (" + v.getClass().getSimpleName() + ")");
                    param.args[3] = 0;
                }
            }
        }, int.class, int.class, int.class, int.class);

        tryHookClass(View.class, "setPaddingRelative", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                if ((int) param.args[3] > 0 && (isInputView(v) || isInputViewChild(v))) {
                    param.args[3] = 0;
                }
            }
        }, int.class, int.class, int.class, int.class);

        // ============ NavigationBarFrame ============
        tryHookClass(View.class, "setMinimumHeight", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if ((int) param.args[0] > 0 && isNavBarFrame((View) param.thisObject)) {
                    param.args[0] = 0;
                }
            }
        }, int.class);

        tryHookClass(View.class, "setLayoutParams", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                if (isNavBarFrame(v)) {
                    ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) param.args[0];
                    if (lp != null && lp.height > 0) {
                        lp.height = 0;
                    }
                }
            }
        }, ViewGroup.LayoutParams.class);

        tryHookClass(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                if (isNavBarFrame(v)) {
                    forceZeroNavBarFrame(v);
                }
            }
        });

        // ============ Resources dimen ============
        tryHookClass(Resources.class, "getDimensionPixelSize", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (BYPASS.get() != null) return;
                int resId = (int) param.args[0];
                if (isTargetDimen((Resources) param.thisObject, resId)) {
                    param.setResult(0);
                }
            }
        }, int.class);

        tryHookClass(Resources.class, "getDimension", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (BYPASS.get() != null) return;
                int resId = (int) param.args[0];
                if (isTargetDimen((Resources) param.thisObject, resId)) {
                    param.setResult(0f);
                }
            }
        }, int.class);

        // ============ Hide nav bar buttons ============
        tryHookClass(View.class, "setVisibility", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                if (v.getId() == View.NO_ID) return;
                if ((int) param.args[0] == View.GONE) return;
                String idName = resName(v);
                if (isAlwaysHidden(idName)) {
                    param.args[0] = View.GONE;
                } else if (shouldSuppressLanguageKey(v, idName)) {
                    param.args[0] = View.GONE;
                }
            }
        }, int.class);

        tryHookClass(View.class, "performClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                if (v.getId() == View.NO_ID) return;
                String idName = resName(v);
                if (isAlwaysHidden(idName)) {
                    param.setResult(false);
                } else if (shouldSuppressLanguageKey(v, idName)) {
                    param.setResult(false);
                }
            }
        });

        tryHookClass(View.class, "setOnClickListener", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                if (v.getId() == View.NO_ID) return;
                if (isAlwaysHidden(resName(v))) {
                    v.setVisibility(View.GONE);
                }
            }
        }, View.OnClickListener.class);

        // ============ key_pos_switch_to_next_language ============
        tryHookClass(View.class, "setContentDescription", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                if (v.getId() == View.NO_ID) return;
                if (!LANGUAGE_KEY_ID.equals(resName(v))) return;

                CharSequence cd = (CharSequence) param.args[0];
                if (isLanguageCd(cd) && !SPOOF_VERSION_TO_DISABLE_EXPERIMENT) {
                    v.setVisibility(View.GONE);
                    v.setClickable(false);
                    v.setFocusable(false);
                } else if (isLanguageCd(cd)) {
                    log("real language key visible: cd=\"" + cd + "\"");
                } else if (ADD_CUSTOM_GLOBE_BUTTON) {
                    repurposeAsLanguageKey(v, cd);
                }
            }
        }, CharSequence.class);

        // ============================================================
        // InputView.onMeasure — растягиваем на navBarPx
        // ============================================================
        try {
            Class<?> inputViewClass = Class.forName(
                    TARGET_VIEW_CLASS, false, lpparam.classLoader);

            XposedHelpers.findAndHookMethod(inputViewClass, "onMeasure",
                    int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View v = (View) param.thisObject;
                            // защита: если onMeasure унаследован, хук иначе сработал бы на всех
                            if (!isInputView(v)) return;

                            int mh = v.getMeasuredHeight();
                            if (mh <= 0) return;

                            int newH = mh + navBarPx(v);

                            if (DEBUG_DUMP && newH != sLastInputViewH) {
                                sLastInputViewH = newH;
                                log("InputView.onMeasure: mh=" + mh
                                        + " → stretch to " + newH);
                            }

                            callSetMeasuredDimension(v, v.getMeasuredWidth(), newH);
                            scheduleFixChecks(v);
                        }
                    });

            log("hooked InputView.onMeasure");
        } catch (Throwable t) {
            log("failed to hook InputView.onMeasure: " + t);
        }

        // ============================================================
        // FrameLayout (ребёнок InputView).onMeasure — обнуляем padB, растягиваем
        // ============================================================
        try {
            XposedHelpers.findAndHookMethod(FrameLayout.class, "onMeasure",
                    int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            View v = (View) param.thisObject;
                            if (!isInputViewChild(v)) return;
                            int padB = v.getPaddingBottom();
                            if (padB > 0) {
                                log("child onMeasure: zeroing bottom padding, was=" + padB);
                                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                                        v.getPaddingRight(), 0);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View v = (View) param.thisObject;
                            if (!isInputViewChild(v)) return;

                            int mh = v.getMeasuredHeight();
                            if (mh <= 0) return;

                            int newH = mh + navBarPx(v);

                            if (DEBUG_DUMP) {
                                int prevMh = sLastChildMh;
                                if (prevMh > 0
                                        && Math.abs(mh - prevMh) > JUMP_DUMP_THRESHOLD_PX) {
                                    StringBuilder sb = new StringBuilder();
                                    dumpTree(v, 0, sb);
                                    long upSec = (android.os.SystemClock.uptimeMillis()
                                            - android.os.Process.getStartUptimeMillis()) / 1000;
                                    log("TREE child jump mh " + prevMh + " → " + mh
                                            + " (t+" + upSec + "s since process start)");
                                    logLines("TREE", sb.toString());
                                }
                                sLastChildMh = mh;

                                if (newH != sLastChildH) {
                                    sLastChildH = newH;
                                    log("FrameLayout(InputView child).onMeasure: mh=" + mh
                                            + " → stretch to " + newH);
                                }
                            }

                            callSetMeasuredDimension(v, v.getMeasuredWidth(), newH);
                            scheduleFixChecks(v);
                        }
                    });

            log("hooked FrameLayout.onMeasure (InputView child)");
        } catch (Throwable t) {
            log("failed to hook FrameLayout.onMeasure: " + t);
        }

        // ============================================================
        // InputView.onLayout — диагностика (только при изменении)
        // ============================================================
        if (DEBUG_DUMP) {
            try {
                Class<?> inputViewClass = Class.forName(
                        TARGET_VIEW_CLASS, false, lpparam.classLoader);

                XposedHelpers.findAndHookMethod(inputViewClass, "onLayout",
                        boolean.class, int.class, int.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                View v = (View) param.thisObject;
                                if (!isInputView(v) || !(v instanceof ViewGroup)) return;
                                ViewGroup vg = (ViewGroup) v;
                                StringBuilder sb = new StringBuilder();
                                sb.append("InputView.onLayout: h=").append(v.getHeight())
                                        .append(", padB=").append(v.getPaddingBottom())
                                        .append(", children=").append(vg.getChildCount());
                                for (int i = 0; i < vg.getChildCount(); i++) {
                                    View child = vg.getChildAt(i);
                                    sb.append(" | child[").append(i).append("] ")
                                            .append(child.getClass().getSimpleName())
                                            .append(" top=").append(child.getTop())
                                            .append(" bottom=").append(child.getBottom())
                                            .append(" h=").append(child.getHeight());
                                }
                                String sig = sb.toString();
                                if (!sig.equals(sLastLayoutSig)) {
                                    sLastLayoutSig = sig;
                                    log(sig);
                                }
                            }
                        });

                log("hooked InputView.onLayout (диагностика)");
            } catch (Throwable t) {
                log("failed to hook InputView.onLayout: " + t);
            }
        }
    }

    // ===================== Phenotype cache =====================

    private void wipePhenotypeCache() {
        String base = "/data/data/" + GBOARD_PKG + "/";
        File marker = new File(base + WIPE_MARKER);
        try {
            if (WIPE_PHENOTYPE_ONLY_ONCE && marker.exists()) return;
        } catch (Throwable ignored) {}

        logDir(base + "files");
        logDir(base + "files/datastore");

        for (String rel : PHENOTYPE_CACHE_PATHS) {
            try {
                File f = new File(base + rel);
                if (f.exists()) {
                    boolean ok = deleteRecursively(f);
                    log("wiped phenotype cache: " + f.getPath() + " ok=" + ok);
                }
            } catch (Throwable t) {
                log("failed to wipe " + rel + ": " + t);
            }
        }

        if (WIPE_PHENOTYPE_ONLY_ONCE) {
            try {
                marker.createNewFile();
            } catch (Throwable t) {
                log("failed to create wipe marker: " + t);
            }
        }
    }

    private boolean deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursively(k);
            }
        }
        return f.delete();
    }

    // ===================== Crash guard =====================

    private void installCrashGuard() {
        try {
            final Thread.UncaughtExceptionHandler original =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    if (isSelfInflictedSignatureCrash(e)) {
                        log("suppressed self-inflicted signature-check crash on thread "
                                + t.getName() + ":\n" + Log.getStackTraceString(e));
                        return;
                    }
                    if (original != null) original.uncaughtException(t, e);
                    else System.exit(1);
                }
            });
            log("installed crash guard");
        } catch (Throwable t) {
            log("installCrashGuard failed: " + t);
        }
    }

    private boolean isSelfInflictedSignatureCrash(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 5) {
            if (cur instanceof IllegalStateException
                    && cur.getMessage() != null
                    && cur.getMessage().contains("signed by unrecognized certificates")) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    // ===================== Signature spoofing =====================

    private void spoofSignatureFields(Object packageInfo) {
        try {
            android.content.pm.Signature dummy = new android.content.pm.Signature(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            );
            try {
                XposedHelpers.setObjectField(packageInfo, "signatures",
                        new android.content.pm.Signature[]{dummy});
            } catch (Throwable ignored) {}
            try {
                Object signingInfo = XposedHelpers.getObjectField(packageInfo, "signingInfo");
                patchSignaturesRecursively(signingInfo, dummy, 3);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            log("spoofSignatureFields failed: " + t);
        }
    }

    private void patchSignaturesRecursively(Object obj,
                                            android.content.pm.Signature dummy,
                                            int depth) {
        if (obj == null || depth < 0) return;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Class<?> ft = f.getType();
                    if (ft == android.content.pm.Signature[].class) {
                        f.set(obj, new android.content.pm.Signature[]{dummy});
                    } else if (ft == android.content.pm.Signature.class) {
                        f.set(obj, dummy);
                    } else if (!ft.isPrimitive()
                            && ft.getName().startsWith("android.content.pm.")) {
                        Object nested = f.get(obj);
                        if (nested != null) {
                            patchSignaturesRecursively(nested, dummy, depth - 1);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    // ===================== Language key =====================

    private void repurposeAsLanguageKey(View v, CharSequence originalCd) {
        try {
            if (REPURPOSED_TAG.equals(v.getTag())) return;
            if (sLanguageIconDrawable == null) return;
            boolean iconSet = false;
            if (v instanceof ImageView) {
                try {
                    Drawable fresh = sLanguageIconDrawable.getConstantState() != null
                            ? sLanguageIconDrawable.getConstantState().newDrawable().mutate()
                            : sLanguageIconDrawable;
                    ((ImageView) v).setImageDrawable(fresh);
                    iconSet = true;
                } catch (Throwable t) {
                    log("repurpose: setImageDrawable failed: " + t);
                }
            } else {
                iconSet = trySetDrawableField(v);
            }
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    InputMethodService svc = sImeService;
                    if (svc != null) {
                        try { svc.switchToNextInputMethod(false); }
                        catch (Throwable t) { log("switchToNextInputMethod failed: " + t); }
                    }
                }
            });
            v.setTag(REPURPOSED_TAG);
            log("repurposed emoji-slot key as language switch, iconSet=" + iconSet);
        } catch (Throwable t) {
            log("repurposeAsLanguageKey failed: " + t);
        }
    }

    private boolean trySetDrawableField(View v) {
        Class<?> cls = v.getClass();
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (Drawable.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Drawable fresh = sLanguageIconDrawable.getConstantState() != null
                                ? sLanguageIconDrawable.getConstantState().newDrawable().mutate()
                                : sLanguageIconDrawable;
                        f.set(v, fresh);
                        v.invalidate();
                        return true;
                    } catch (Throwable ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

    // ===================== Dimen =====================

    /** Только framework-ресурсы (package "android"), результат кэшируется по resId. */
    private static boolean isTargetDimen(Resources res, int resId) {
        Boolean cached = DIMEN_CACHE.get(resId);
        if (cached != null) return cached;

        boolean result = false;
        try {
            String name = res.getResourceEntryName(resId);
            if (name != null && "android".equals(res.getResourcePackageName(resId))) {
                for (String target : TARGET_DIMEN_NAMES) {
                    if (target.equals(name)) {
                        result = true;
                        break;
                    }
                }
            }
        } catch (Resources.NotFoundException ignored) {}

        DIMEN_CACHE.put(resId, result);
        return result;
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    /** Каждая строка — отдельная запись в логе (иначе logcat сворачивает «N more lines»). */
    private static void logLines(String prefix, String text) {
        String[] lines = text.split("\n");
        int max = Math.min(lines.length, 150);
        for (int i = 0; i < max; i++) {
            if (!lines[i].isEmpty()) log(prefix + " " + lines[i]);
        }
        if (lines.length > max) log(prefix + " ... +" + (lines.length - max) + " lines");
    }

    private static void logDir(String path) {
        try {
            File d = new File(path);
            String[] names = d.list();
            log("dir " + path + ": " + (names == null ? "null" : Arrays.toString(names)));
        } catch (Throwable t) {
            log("logDir failed for " + path + ": " + t);
        }
    }
}
