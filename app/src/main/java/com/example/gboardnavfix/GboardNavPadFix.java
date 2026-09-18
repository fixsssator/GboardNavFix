package com.example.gboardnavfix;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class GboardNavPadFix implements IXposedHookLoadPackage {

    private static final String TAG = "GboardNavFix";
    private static final String GBOARD_PKG = "com.google.android.inputmethod.latin";

    // Тестовая подпись — 64 hex-символа. Конкретное значение не важно,
    // главное чтобы она ОТЛИЧАЛАСЬ от оригинальной подписи Google.
    private static final String FAKE_SIGNATURE_HEX =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static final String TARGET_VIEW_CLASS =
            "com.google.android.libraries.inputmethod.inputview.InputView";

    private static final String NAV_BAR_FRAME_CLASS =
            "android.inputmethodservice.navigationbar.NavigationBarFrame";

    private static final String[] TARGET_DIMEN_NAMES = {
            "navigation_bar_height",
            "navigation_bar_height_landscape",
            "navigation_bar_frame_height",
            "navigation_bar_width"
    };

    private static final Set<String> ALWAYS_HIDDEN_IDS = new HashSet<>(Arrays.asList(
            "input_method_nav_back",
            "input_method_nav_ime_switcher",
            "input_method_nav_home_handle"
    ));

    private static final String LANGUAGE_KEY_ID = "key_pos_switch_to_next_language";

    private static final boolean SPOOF_VERSION_TO_DISABLE_EXPERIMENT = true;
    private static final boolean ADD_CUSTOM_GLOBE_BUTTON = false;

    private static final boolean USE_VIEW_FALLBACK_DEBUG_LOGGING = false;
    private static final boolean USE_TOOLBAR_DEBUG_LOGGING = false;
    private static final boolean USE_NAVBAR_TREE_DUMP = false;

    private static final String[] PHENOTYPE_CACHE_PATHS = {
            "files/phenotype",
            "files/phenotype_storage_info",
            "files/datastore/flags_jetpack_data_store.pb"
    };

    private static final String REPURPOSED_TAG = "gboardnavfix_repurposed";

    private static volatile InputMethodService sImeService;
    private static volatile Drawable sLanguageIconDrawable;

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
        return NAV_BAR_FRAME_CLASS.equals(v.getClass().getName());
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!GBOARD_PKG.equals(lpparam.packageName)) return;

        log("hooking into Gboard, process=" + lpparam.processName);

        wipePhenotypeCache();
        clearPhenotypeViaGms(lpparam.classLoader);
        installCrashGuard();

        if (SPOOF_VERSION_TO_DISABLE_EXPERIMENT) {
            XC_MethodHook spoofHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String pkg = (String) param.args[0];
                        if (!GBOARD_PKG.equals(pkg)) return;
                        Object info = param.getResult();
                        if (info == null) return;

                        XposedHelpers.setIntField(info, "versionCode", 123456789);
                        try {
                            XposedHelpers.setLongField(info, "versionCodeMajor", 0L);
                        } catch (Throwable ignored) {}
                        XposedHelpers.setObjectField(info, "versionName", "99.9.9-spoof");

                        spoofSignatureWithFake(info);
                        log("spoofed PackageInfo (ver+name+fake signature) for " + pkg);
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

        try {
            XposedHelpers.findAndHookMethod(
                    InputMethodService.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sImeService = (InputMethodService) param.thisObject;
                        }
                    });
        } catch (Throwable t) {
            log("failed to hook InputMethodService.onCreate: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    InputMethodService.class, "setInputView", View.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            View inputView = (View) param.args[0];
                            if (inputView != null && inputView.getPaddingBottom() > 0) {
                                int bottom = inputView.getPaddingBottom();
                                log("setInputView: zeroing bottom padding, was=" + bottom);
                                inputView.setPadding(
                                        inputView.getPaddingLeft(),
                                        inputView.getPaddingTop(),
                                        inputView.getPaddingRight(),
                                        0);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("failed to hook setInputView: " + t);
        }

        tryHook(
                "android.inputmethodservice.navigationbar.KeyButtonView",
                lpparam.classLoader,
                "setImageDrawable", new XC_MethodHook() {
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
                },
                Drawable.class);

        XposedHelpers.findAndHookMethod(
                View.class, "setPadding",
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
                        if (isNavBarFrame(v)) {
                            int bottom = (int) param.args[3];
                            if (bottom > 0) {
                                log("zeroing NavigationBarFrame bottom padding, was=" + bottom);
                                param.args[3] = 0;
                            }
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(
                View.class, "setPaddingRelative",
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
                });

        XposedHelpers.findAndHookMethod(
                View.class, "setMinimumHeight", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (isNavBarFrame(v)) {
                            int h = (int) param.args[0];
                            if (h > 0) {
                                log("zeroing NavigationBarFrame minimumHeight, was=" + h);
                                param.args[0] = 0;
                            }
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(
                View.class, "setLayoutParams", ViewGroup.LayoutParams.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (isNavBarFrame(v)) {
                            ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) param.args[0];
                            if (lp != null && lp.height > 0) {
                                log("zeroing NavigationBarFrame LayoutParams.height, was=" + lp.height);
                                lp.height = 0;
                            }
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(
                View.class, "measure", int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (!isNavBarFrame(v)) return;
                        int heightSpec = (int) param.args[1];
                        log("NavigationBarFrame.measure BEFORE: mode="
                                + View.MeasureSpec.getMode(heightSpec)
                                + " size=" + View.MeasureSpec.getSize(heightSpec));
                        param.args[1] = View.MeasureSpec.makeMeasureSpec(
                                0, View.MeasureSpec.EXACTLY);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (!isNavBarFrame(v)) return;
                        if (v.getMeasuredHeight() != 0) {
                            try {
                                java.lang.reflect.Method m = View.class.getDeclaredMethod(
                                        "setMeasuredDimension", int.class, int.class);
                                m.setAccessible(true);
                                m.invoke(v, v.getMeasuredWidth(), 0);
                                log("forced NavigationBarFrame measuredHeight=0");
                            } catch (Throwable t) {
                                log("setMeasuredDimension via reflection failed: " + t);
                            }
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(
                Resources.class, "getDimensionPixelSize", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        maybeZeroOut(param);
                    }
                });

        XposedHelpers.findAndHookMethod(
                Resources.class, "getDimension", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int resId = (int) param.args[0];
                        Resources res = (Resources) param.thisObject;
                        if (isTargetDimen(res, resId)) param.setResult(0f);
                    }
                });

        XposedHelpers.findAndHookMethod(
                View.class, "setVisibility", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        String idName = safeResName(v);
                        if (isAlwaysHidden(v, idName)) {
                            if ((int) param.args[0] != View.GONE) {
                                log("forcing GONE on id=" + idName);
                                param.args[0] = View.GONE;
                            }
                        } else if (LANGUAGE_KEY_ID.equals(idName)
                                && isLanguageCd(v.getContentDescription())
                                && (int) param.args[0] != View.GONE) {
                            log("forcing GONE on language key (cd confirmed)");
                            param.args[0] = View.GONE;
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(
                View.class, "performClick",
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
                });

        XposedHelpers.findAndHookMethod(
                View.class, "setOnClickListener", View.OnClickListener.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        String idName = safeResName(v);
                        if (isAlwaysHidden(v, idName)) v.setVisibility(View.GONE);
                    }
                });

        XposedHelpers.findAndHookMethod(
                View.class, "onAttachedToWindow",
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
                        if (isNavBarFrame(v)) {
                            ViewGroup.LayoutParams lp = v.getLayoutParams();
                            if (lp != null && lp.height != 0) {
                                lp.height = 0;
                                v.setLayoutParams(lp);
                                log("zeroed NavigationBarFrame height via LayoutParams (attach)");
                            }
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(
                View.class, "setContentDescription", CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        String idName = safeResName(v);
                        if (LANGUAGE_KEY_ID.equals(idName)) {
                            CharSequence cd = (CharSequence) param.args[0];
                            if (isLanguageCd(cd) && !SPOOF_VERSION_TO_DISABLE_EXPERIMENT) {
                                log("hiding language key, cd=\"" + cd + "\"");
                                v.setVisibility(View.GONE);
                                v.setClickable(false);
                                v.setFocusable(false);
                            } else if (isLanguageCd(cd)) {
                                log("real language key visible (spoof active): cd=\""
                                        + cd + "\"");
                            } else if (ADD_CUSTOM_GLOBE_BUTTON) {
                                repurposeAsLanguageKey(v, cd);
                            }
                        }
                    }
                });
    }

    // =================== GMS Phenotype ===================

    private void clearPhenotypeViaGms(ClassLoader cl) {
        try {
            Class<?> ph = Class.forName("com.google.android.gms.phenotype.Phenotype", false, cl);
            for (java.lang.reflect.Method m : ph.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("clear") || n.contains("reset")) {
                    try {
                        m.setAccessible(true);
                        Class<?>[] pt = m.getParameterTypes();
                        if (pt.length == 1 && pt[0] == String.class) {
                            m.invoke(null, GBOARD_PKG);
                            log("called GMS Phenotype." + m.getName() + "(pkg)");
                        } else if (pt.length == 2
                                && pt[0] == String.class
                                && (pt[1] == long.class || pt[1] == Long.class)) {
                            m.invoke(null, GBOARD_PKG, 0L);
                            log("called GMS Phenotype." + m.getName() + "(pkg, 0)");
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            log("GMS Phenotype class not found: " + t);
        }
    }

    // =================== Phenotype cache ===================

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
            if (kids != null) for (java.io.File k : kids) deleteRecursively(k);
        }
        return f.delete();
    }

    // =================== Crash guard ===================

    private void installCrashGuard() {
        try {
            final Thread.UncaughtExceptionHandler original =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    if (isSelfInflictedSignatureCrash(e)) {
                        log("suppressed signature-check crash on " + t.getName());
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

    // =================== Signature spoof ===================

    private void spoofSignatureWithFake(Object packageInfo) {
        try {
            android.content.pm.Signature fake =
                    new android.content.pm.Signature(FAKE_SIGNATURE_HEX);

            try {
                XposedHelpers.setObjectField(packageInfo, "signatures",
                        new android.content.pm.Signature[]{fake});
            } catch (Throwable ignored) {}

            try {
                Object signingInfo = XposedHelpers.getObjectField(packageInfo, "signingInfo");
                patchSignaturesRecursively(signingInfo, fake, 3);
            } catch (Throwable ignored) {}

            log("spoofed signature with FAKE_SIGNATURE_HEX");
        } catch (Throwable t) {
            log("spoofSignatureWithFake failed: " + t);
        }
    }

    private void patchSignaturesRecursively(Object obj,
                                             android.content.pm.Signature fake,
                                             int depth) {
        if (obj == null || depth < 0) return;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Class<?> ft = f.getType();
                    if (ft == android.content.pm.Signature[].class) {
                        f.set(obj, new android.content.pm.Signature[]{fake});
                    } else if (ft == android.content.pm.Signature.class) {
                        f.set(obj, fake);
                    } else if (!ft.isPrimitive()
                            && ft.getName().startsWith("android.content.pm.")) {
                        Object nested = f.get(obj);
                        if (nested != null) patchSignaturesRecursively(nested, fake, depth - 1);
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    // =================== Helpers ===================

    private String safeResName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return "NO_ID";
            return v.getResources().getResourceEntryName(id);
        } catch (Exception e) {
            return "?";
        }
    }

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
                        try {
                            svc.switchToNextInputMethod(false);
                        } catch (Throwable t) {
                            log("switchToNextInputMethod failed: " + t);
                        }
                    }
                }
            });
            v.setTag(REPURPOSED_TAG);
            log("repurposed as language key, iconSet=" + iconSet + " cd=\"" + originalCd + "\"");
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
                        log("repurpose: set drawable field '" + f.getName() + "'");
                        return true;
                    } catch (Throwable ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

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
                if (target.equals(name)) return true;
            }
        } catch (Resources.NotFoundException ignored) {}
        return false;
    }

    private void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
