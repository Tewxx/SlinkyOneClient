package tewxx.wtf.bridge.runtime;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.*;
import java.lang.reflect.Modifier;

public final class DisplayHook {
    private static final MethodType VOID = MethodType.methodType(void.class);
    private static Instrumentation inst;
    private static MethodHandle original, hook;
    private static long nextSearch;
    private static boolean inHook;

    public static void init(Instrumentation value) {
        inst = value;
    }

    public static void update() throws Throwable {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (original == null)
            original = MethodHandles.publicLookup().findStatic(Class.forName("org.lwjgl.opengl.Display", false, loader), "update", VOID);

        long now = System.nanoTime();
        if (hook == null && inst != null && now >= nextSearch) {
            nextSearch = now + 1000000000L;
            hook = findHook(loader);
        }

        if (hook != null && !inHook) {
            inHook = true;
            try {
                hook.invokeExact();
                return;
            } catch (UnsatisfiedLinkError e) {
                hook = null;
            } finally {
                inHook = false;
            }
        }
        original.invokeExact();
    }

    private static MethodHandle findHook(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> newest = null;
        int newestId = -1;
        for (Class<?> type : inst.getAllLoadedClasses()) {
            String name = type.getName();
            if (!name.matches("SlinkyHooks[0-9]+") || type.getClassLoader() != loader) continue;
            int id = Integer.parseInt(name.substring("SlinkyHooks".length()));
            if (id > newestId && Modifier.isNative(type.getDeclaredMethod("updateDisplay").getModifiers())) {
                newest = type;
                newestId = id;
            }
        }
        return newest == null ? null : MethodHandles.publicLookup().findStatic(newest, "updateDisplay", VOID);
    }
}
