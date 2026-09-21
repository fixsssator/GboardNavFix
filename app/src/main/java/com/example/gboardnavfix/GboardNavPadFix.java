package com.example.gboardnavfix;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Gboard Nav Pad Fix
 * Убирает пустую полосу ~99px под клавиатурой на Pixel (gesture nav)
 *
 * Главное исправление: onComputeInsets принудительно возвращает 0
 * для contentTopInsets и visibleTopInsets — система перестаёт
 * резервировать место под клавиатуру, и полоса исчезает.
 */
public class GboardNavPadFix implements IXposedHookLoadPackage {

    private static final String TAG = "GboardNavFix";
    private static final String GBOARD_PKG = "com.google.android.inputmethod.latin";
    private static final String TARGET_VIEW_CLASS =
            "com.google.android.libraries.inputmethod.inputview.InputView";
    private static final String NAV_BAR_FRAME_CLASS =
            "android.inputmethodservice.navigationbar.NavigationBarFrame";

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

    // === Настройки ===
    private static final boolean SPOOF_VERSION_TO_DISABLE_EXPERIMENT = true;
    private static final boolean ADD_CUSTOM_GLOBE_BUTTON = false;
    private static final boolean APPLY_HIDE_NAVBAR_PROP = true;

    private static final String[] PHENOTYPE_CACHE_PATHS = {
            "files/phenotype",
            "files/phenotype_storage_info",
            "files/datastore/flags_jetpack_data_store.pb"
    };

    private static final String REPURPOSED_TAG = "gboardnavfix_repurposed";

    private static volatile InputMethodService sImeService;
    private static volatile Drawable sLanguageIconDrawable;

    // ===================== Helpers =====================

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

    private static boolean isAlwaysHidden(View v, String idName) {
        return ALWAYS_HIDDEN_IDS.contains(idName);
    }

    private static boolean isLanguageCd(CharSequence cd) {
        if (cd == null) return false;
        String s = cd.toString().toLowerCase();
        return s.contains("language") || s.contains("язык");
    }

    private static boolean isNavBarFrame(View v) {
        return v != null && NAV_BAR_FRAME_CLASS.equals(v.getClass().getName());
    }

    private void applyHideNavBarProp() {
        if (!APPLY_HIDE_NAVBAR_PROP) return;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            XposedHelpers.callStaticMethod(sp, "set", "ro.com.google.ime.kb_pad_port_b", "1.0");
            XposedHelpers.callStaticMethod(sp, "set", "ro.com.google.ime.kb_pad_land_b", "1.0");
            log("HideNavBar-like: set ro.com.google.ime.kb_pad_port_b = 1.0");
        } catch (Throwable t) {
            log("failed to set kb_pad prop: " + t);
        }
    }

    private void forceZeroNavBarFrame(View v) {
        if (v == null) return;
        try {
            ViewParent parent = v.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(v);
                log("NavigationBarFrame REMOVED from parent");
            }

            v.setVisibility(View.GONE);
            v.setMinimumHeight(0);

            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                lp.height = 0;
                try {
                    v.setLayoutParams(lp);
                } catch (Throwable ignored) {}
            }

            v.setClickable(false);
            v.setFocusable(false);
            v.setEnabled(false);

            v.post(() -> {
                try {
                    ViewParent p2 = v.getParent();
                    if (p2 instanceof ViewGroup) {
                        ((ViewGroup) p2).removeView(v);
                    }
                    v.setVisibility(View.GONE);
                    v.setMinimumHeight(0);
                } catch (Throwable ignored) {}
            });
            v.postDelayed(() -> forceZeroNavBarFrame(v), 40);
            v.postDelayed(() -> forceZeroNavBarFrame(v), 150);
            v.postDelayed(() -> forceZeroNavBarFrame(v), 400);
        } catch (Throwable t) {
            log("forceZeroNavBarFrame failed: " + t);
        }
    }

    private void forceImeWindowNoLimits(Window window) {
        if (window == null) return;
        try {
            WindowManager.LayoutParams lp = window.getAttributes();
            if (lp == null) return;

            log("IME LP BEFORE force: h=" + lp.height
                    + ", y=" + lp.y
                    + ", gravity=" + lp.gravity
                    + ", flags=0x" + Integer.toHexString(lp.flags));

            lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            lp.flags &= ~WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;

            lp.y = 0;

            window.setAttributes(lp);

            View decor = window.getDecorView();
            if (decor != null) {
                decor.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                );
            }

            log("IME LP AFTER  force: h=" + lp.height
                    + ", y=" + lp.y
                    + ", flags=0x" + Integer.toHexString(lp.flags));
        } catch (Throwable t) {
            log("forceImeWindowNoLimits failed: " + t);
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
        applyHideNavBarProp();

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
                        log("spoofed self versionCode/versionName/signature for " + pkg);
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

        // ============ InputMethodService.onCreate ============
        try {
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sImeService = (InputMethodService) param.thisObject;
                            applyHideNavBarProp();
                        }
                    });
        } catch (Throwable t) {
            log("failed to hook InputMethodService.onCreate: " + t);
        }

        // ============ setInputView ============
        try {
            XposedHelpers.findAndHookMethod(InputMethodService.class, "setInputView",
                    View.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
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
                            try {
                                Window window = svc.getWindow().getWindow();
                                forceImeWindowNoLimits(window);

                                if (window != null) {
                                    View decor = window.getDecorView();
                                    if (decor != null) {
                                        try {
                                            Object controller = XposedHelpers.callMethod(
                                                    decor, "getWindowInsetsController");
                                            if (controller != null) {
                                                Class<?> typeClass = Class.forName(
                                                        "android.view.WindowInsets$Type");
                                                int captionBar = XposedHelpers.getStaticIntField(
                                                        typeClass, "captionBar");
                                                XposedHelpers.callMethod(controller, "hide", captionBar);
                                                log("requested hide captionBar");
                                            }
                                        } catch (Throwable t) {
                                            log("hide captionBar failed: " + t);
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                log("setInputView after failed: " + t);
                            }
                        }
                    });
            log("hooked setInputView");
        } catch (Throwable t) {
            log("failed to hook setInputView: " + t);
        }

        // ============ Window.setAttributes ============
        try {
            XposedHelpers.findAndHookMethod(Window.class, "setAttributes",
                    WindowManager.LayoutParams.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            WindowManager.LayoutParams lp =
                                    (WindowManager.LayoutParams) param.args[0];
                            if (lp == null) return;

                            if (lp.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD) {
                                log("IME setAttributes BEFORE: h=" + lp.height
                                        + ", y=" + lp.y
                                        + ", gravity=" + lp.gravity
                                        + ", flags=0x" + Integer.toHexString(lp.flags));

                                lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                                lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                                lp.flags &= ~WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
                                lp.y = 0;

                                log("IME setAttributes AFTER : h=" + lp.height
                                        + ", y=" + lp.y
                                        + ", flags=0x" + Integer.toHexString(lp.flags));
                            }
                        }
                    });
            log("hooked Window.setAttributes");
        } catch (Throwable t) {
            log("failed to hook Window.setAttributes: " + t);
        }

        // ============================================================
        // ГЛАВНЫЙ ХУК: onComputeInsets — зануляем insets
        // ============================================================
        try {
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onComputeInsets",
                    InputMethodService.Insets.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            InputMethodService.Insets insets =
                                    (InputMethodService.Insets) param.args[0];
                            if (insets == null) return;
                            log("onComputeInsets BEFORE: contentTop=" + insets.contentTopInsets
                                    + ", visibleTop=" + insets.visibleTopInsets
                                    + ", touchable=" + insets.touchableInsets);
                            insets.contentTopInsets = 0;
                            insets.visibleTopInsets = 0;
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            InputMethodService.Insets insets =
                                    (InputMethodService.Insets) param.args[0];
                            if (insets == null) return;

                            int oldContent = insets.contentTopInsets;
                            int oldVisible = insets.visibleTopInsets;

                            insets.contentTopInsets = 0;
                            insets.visibleTopInsets = 0;

                            log("onComputeInsets FORCED: content " + oldContent
                                    + " → 0, visible " + oldVisible + " → 0");
                        }
                    });
            log("hooked onComputeInsets (force zero)");
        } catch (Throwable t) {
            log("failed to hook onComputeInsets: " + t);
        }

        // ============ Иконка языка ============
        tryHook("android.inputmethodservice.navigationbar.KeyButtonView",
                lpparam.classLoader, "setImageDrawable", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if ("input_method_nav_ime_switcher".equals(safeResName(v))) {
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
        XposedHelpers.findAndHookMethod(View.class, "setPadding",
                int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (TARGET_VIEW_CLASS.equals(v.getClass().getName())) {
                            int bottom = (int) param.args[3];
                            int top = (int) param.args[1];
                            if (bottom > 0) {
                                log("zeroing InputView bottom padding, was=" + bottom);
                                param.args[3] = 0;
                            }
                            if (top < 0) {
                                log("zeroing InputView negative top padding, was=" + top);
                                param.args[1] = 0;
                            }
                        }
                        if (isNavBarFrame(v) && (int) param.args[3] > 0) {
                            param.args[3] = 0;
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(View.class, "setPaddingRelative",
                int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (TARGET_VIEW_CLASS.equals(v.getClass().getName())) {
                            if ((int) param.args[3] > 0) param.args[3] = 0;
                            if ((int) param.args[1] < 0) param.args[1] = 0;
                        }
                    }
                });

        // ============ NavigationBarFrame ============
        XposedHelpers.findAndHookMethod(View.class, "setMinimumHeight", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isNavBarFrame((View) param.thisObject) && (int) param.args[0] > 0) {
                            log("zeroing NavBarFrame minHeight was=" + param.args[0]);
                            param.args[0] = 0;
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(View.class, "setLayoutParams",
                ViewGroup.LayoutParams.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (isNavBarFrame(v)) {
                            ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) param.args[0];
                            if (lp != null && lp.height > 0) {
                                log("zeroing NavBarFrame height was=" + lp.height);
                                lp.height = 0;
                            }
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isNavBarFrame((View) param.thisObject)
                                && (int) param.args[0] != View.GONE) {
                            log("forcing NavBarFrame to GONE");
                            param.args[0] = View.GONE;
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (isNavBarFrame(v)) {
                            forceZeroNavBarFrame(v);
                        }
                    }
                });

        // ============ Resources dimen ============
        XposedHelpers.findAndHookMethod(Resources.class, "getDimensionPixelSize", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        maybeZeroOut(param);
                    }
                });

        XposedHelpers.findAndHookMethod(Resources.class, "getDimension", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int resId = (int) param.args[0];
                        Resources res = (Resources) param.thisObject;
                        if (isTargetDimen(res, resId)) {
                            param.setResult(0f);
                        }
                    }
                });

        // ============ Скрытие кнопок ============
        XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        String idName = safeResName(v);
                        if (isAlwaysHidden(v, idName)) {
                            if ((int) param.args[0] != View.GONE) param.args[0] = View.GONE;
                        } else if (LANGUAGE_KEY_ID.equals(idName)
                                && isLanguageCd(v.getContentDescription())
                                && (int) param.args[0] != View.GONE) {
                            param.args[0] = View.GONE;
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(View.class, "performClick",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        String idName = safeResName(v);
                        if (isAlwaysHidden(v, idName)
                                || (LANGUAGE_KEY_ID.equals(idName)
                                && isLanguageCd(v.getContentDescription()))) {
                            param.setResult(false);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(View.class, "setOnClickListener",
                View.OnClickListener.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (isAlwaysHidden(v, safeResName(v))) {
                            v.setVisibility(View.GONE);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(View.class, "setContentDescription",
                CharSequence.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        String idName = safeResName(v);
                        if (LANGUAGE_KEY_ID.equals(idName)) {
                            CharSequence cd = (CharSequence) param.args[0];
                            if (isLanguageCd(cd) && !SPOOF_VERSION_TO_DISABLE_EXPERIMENT) {
                                v.setVisibility(View.GONE);
                                v.setClickable(false);
                                v.setFocusable(false);
                            } else if (isLanguageCd(cd)) {
                                log("real language key visible (spoof active): cd=\"" + cd + "\"");
                            } else if (ADD_CUSTOM_GLOBE_BUTTON) {
                                // repurposeAsLanguageKey(v, cd);
                            }
                        }
                    }
                });

        // ============ Диагностика InputView ============
        try {
            Class<?> inputViewClass = Class.forName(TARGET_VIEW_CLASS, false, lpparam.classLoader);

            XposedHelpers.findAndHookMethod(inputViewClass, "onMeasure",
                    int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View v = (View) param.thisObject;
                            if (!(v instanceof ViewGroup)) return;
                            ViewGroup vg = (ViewGroup) v;
                            log("InputView.onMeasure: mh=" + v.getMeasuredHeight()
                                    + ", padT=" + v.getPaddingTop()
                                    + ", padB=" + v.getPaddingBottom()
                                    + ", children=" + vg.getChildCount());
                            for (int i = 0; i < Math.min(vg.getChildCount(), 2); i++) {
                                View child = vg.getChildAt(i);
                                log("  child[" + i + "] " + child.getClass().getSimpleName()
                                        + " mh=" + child.getMeasuredHeight());
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(inputViewClass, "onLayout",
                    boolean.class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View v = (View) param.thisObject;
                            if (!(v instanceof ViewGroup)) return;
                            ViewGroup vg = (ViewGroup) v;
                            log("InputView.onLayout: h=" + v.getHeight()
                                    + ", padB=" + v.getPaddingBottom());
                            for (int i = 0; i < Math.min(vg.getChildCount(), 2); i++) {
                                View child = vg.getChildAt(i);
                                log("  child[" + i + "] top=" + child.getTop()
                                        + " h=" + child.getHeight());
                            }
                        }
                    });
            log("hooked InputView measure/layout");
        } catch (Throwable t) {
            log("failed to hook InputView: " + t);
        }
    }

    // ===================== Phenotype / Crash / Signature =====================

    private void wipePhenotypeCache() {
        String base = "/data/data/" + GBOARD_PKG + "/";
        for (String rel : PHENOTYPE_CACHE_PATHS) {
            try {
                java.io.File f = new java.io.File(base + rel);
                if (f.exists()) {
                    boolean ok = deleteRecursively(f);
                    log("wiped phenotype cache: " + f.getPath() + " ok=" + ok);
                }
            } catch (Throwable t) {
                log("failed to wipe " + rel + ": " + t);
            }
        }
    }

    private boolean deleteRecursively(java.io.File f) {
        if (f.isDirectory()) {
            java.io.File[] kids = f.listFiles();
            if (kids != null) {
                for (java.io.File k : kids) deleteRecursively(k);
            }
        }
        return f.delete();
    }

    private void installCrashGuard() {
        try {
            final Thread.UncaughtExceptionHandler original =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                if (isSelfInflictedSignatureCrash(e)) {
                    log("suppressed signature crash: " + e);
                    return;
                }
                if (original != null) original.uncaughtException(t, e);
                else System.exit(1);
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
                                            android.content.pm.Signature dummy, int depth) {
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

    private String safeResName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return "NO_ID";
            return v.getResources().getResourceEntryName(id);
        } catch (Exception e) {
            return "?";
        }
    }

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
                if (target.equals(name)) return true;
            }
        } catch (Resources.NotFoundException ignored) {}
        return false;
    }

    private void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
