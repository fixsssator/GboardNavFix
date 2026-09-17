package com.example.gboardnavfix;

import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Final guard for the Android IME navigation bar.
 *
 * Gboard may temporarily recreate/reconfigure the framework IME switcher after
 * the normal visibility hooks have already run. That can make a second globe
 * flash at the bottom-right. We do NOT hide NavigationBarFrame itself because
 * Gboard uses that container for the language-selection popup.
 *
 * Instead, only the three known framework navigation children are forced GONE.
 * The dispatchDraw hook is deliberately scoped to NavigationBarFrame and is a
 * last-moment guard immediately before the container draws, eliminating the
 * remaining one-frame/race window.
 */
public final class GboardSystemImeNavGuard implements IXposedHookLoadPackage {

    private static final String TAG = "GboardNavFix";
    private static final String GBOARD_PKG = "com.google.android.inputmethod.latin";
    private static final String NAV_BAR_FRAME_CLASS =
            "android.inputmethodservice.navigationbar.NavigationBarFrame";

    private static final Set<String> HIDDEN_IDS = new HashSet<>(Arrays.asList(
            "input_method_nav_back",
            "input_method_nav_ime_switcher",
            "input_method_nav_home_handle"
    ));

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!GBOARD_PKG.equals(lpparam.packageName)) {
            return;
        }

        // Catch a view whose id is assigned/reassigned after onAttachedToWindow().
        XposedHelpers.findAndHookMethod(
                View.class,
                "setId",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        hideIfTarget((View) param.thisObject);
                    }
                }
        );

        // Catch children when NavigationBarFrame is populated/repopulated.
        XposedHelpers.findAndHookMethod(
                ViewGroup.class,
                "onViewAdded",
                View.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        ViewGroup parent = (ViewGroup) param.thisObject;
                        if (isNavBarFrame(parent)) {
                            hideIfTarget((View) param.args[0]);
                            scan(parent);
                            parent.post(new Runnable() {
                                @Override
                                public void run() {
                                    scan(parent);
                                }
                            });
                        }
                    }
                }
        );

        // Catch normal attach/re-attach cycles.
        XposedHelpers.findAndHookMethod(
                View.class,
                "onAttachedToWindow",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        hideIfTarget(v);
                        ViewParentNav.scanAncestor(v);
                    }
                }
        );

        // Last-moment guard: run immediately before NavigationBarFrame draws.
        // This is intentionally limited to the tiny framework nav container.
        try {
            XposedHelpers.findAndHookMethod(
                    NAV_BAR_FRAME_CLASS,
                    lpparam.classLoader,
                    "dispatchDraw",
                    Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            scan((ViewGroup) param.thisObject);
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": dispatchDraw guard hook failed: " + t);
        }

        XposedBridge.log(TAG + ": installed system IME nav race guard");
    }

    private static boolean isNavBarFrame(View v) {
        return v != null && NAV_BAR_FRAME_CLASS.equals(v.getClass().getName());
    }

    private static void scan(ViewGroup parent) {
        if (parent == null) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            hideIfTarget(child);
            if (child instanceof ViewGroup) {
                scan((ViewGroup) child);
            }
        }
    }

    private static void hideIfTarget(View v) {
        if (v == null) return;
        try {
            String idName = safeResName(v);
            if (!HIDDEN_IDS.contains(idName)) return;

            if (v.getVisibility() != View.GONE) {
                XposedBridge.log(TAG + ": race-guard GONE id=" + idName);
                v.setVisibility(View.GONE);
            }
            v.setClickable(false);
            v.setFocusable(false);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": race-guard failed: " + t);
        }
    }

    private static String safeResName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return "NO_ID";
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Small helper kept separate so the attach hook stays easy to read. */
    private static final class ViewParentNav {
        static void scanAncestor(View v) {
            try {
                android.view.ViewParent p = v.getParent();
                int depth = 0;
                while (p instanceof ViewGroup && depth++ < 8) {
                    ViewGroup vg = (ViewGroup) p;
                    if (isNavBarFrame(vg)) {
                        scan(vg);
                        return;
                    }
                    p = vg.getParent();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
