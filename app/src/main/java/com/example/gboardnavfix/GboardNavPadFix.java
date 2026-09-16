package com.example.gboardnavfix;

import android.view.View;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Gboard 18.2.4 beta / Android 17 fix.
 *
 * The beta contains an internal config object with the following fields:
 *   Laeov.a ... Laeov.u : Larzo
 * and Laeov.u corresponds to the config key "keyboardPaddingEnabled".
 *
 * Laeuk.a() builds that config object.  We hook that exact point and replace
 * only the value for keyboardPaddingEnabled with a Larzo proxy returning
 * Boolean.FALSE from gV().  No Gboard data, SharedPreferences or caches are
 * cleared or modified.
 *
 * A narrow InputView.setPadding fallback is kept because the beta can still
 * apply a 99 px bottom padding after the config object has been built.
 */
public final class GboardNavPadFix implements IXposedHookLoadPackage {

    private static final String TAG = "GboardNavFix";
    private static final String GBOARD = "com.google.android.inputmethod.latin";

    // Gboard 18.2.4.969776716-beta-arm64-v8a
    private static final String CONFIG_BUILDER = "Laeuk";
    private static final String CONFIG_VALUE = "Laeov";
    private static final String LARZO = "Larzo";

    private static final String INPUT_VIEW =
            "com.google.android.libraries.inputmethod.inputview.InputView";

    private static final boolean KEEP_PADDING_FALLBACK = true;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!GBOARD.equals(lpparam.packageName)) {
            return;
        }

        log("GboardNavFix loaded: " + lpparam.processName);

        hookKeyboardPaddingConfig(lpparam.classLoader);

        if (KEEP_PADDING_FALLBACK) {
            hookInputViewPadding(lpparam.classLoader);
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
                            if (result == null) {
                                return;
                            }

                            // Laeuk.a() returns Laeuj, but the concrete object
                            // created by this beta is Laeov.
                            if (!CONFIG_VALUE.equals(result.getClass().getName())) {
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
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
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

                    // Should not be reached for Larzo in this Gboard build.
                    return defaultValue(method.getReturnType());
                }
            };

            return Proxy.newProxyInstance(
                    cl,
                    new Class<?>[]{larzo},
                    handler
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

    private static void hookInputViewPadding(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "setPadding",
                    int.class,
                    int.class,
                    int.class,
                    int.class,
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
        } catch (Throwable t) {
            log("InputView.setPadding hook failed: " + t);
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
