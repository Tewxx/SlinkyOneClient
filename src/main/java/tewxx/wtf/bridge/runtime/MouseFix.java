package tewxx.wtf.bridge.runtime;

import java.lang.reflect.*;
import java.nio.*;
import java.util.*;

public final class MouseFix implements InvocationHandler {
    private static final int GWLP_WNDPROC = -4;
    private static final int WM_LBUTTONDOWN = 0x201, WM_LBUTTONUP = 0x202, WM_RBUTTONDOWN = 0x204, WM_RBUTTONUP = 0x205;
    private static final int SDL_EVENT_MOUSE_BUTTON_DOWN = 0x401, SDL_EVENT_MOUSE_BUTTON_UP = 0x402;

    private static final List<MouseFix> installed = new ArrayList<MouseFix>();
    private static final Method invokeDefault;
    static {
        try {
            invokeDefault = InvocationHandler.class.getMethod("invokeDefault", Object.class, Method.class, Object[].class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final long window;
    private final ByteBuffer source = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
    private long previous, sourceFunction, sourceAddress;
    private Object callback;
    private Method callPrevious, relative, callSource, allocateEvent, eventButton, pushEvent, freeEvent;
    private Method setType, setWindow, setButton, setDown, setClicks, setX, setY;
    private int windowId;
    private boolean disabled;

    private MouseFix(long window) {
        this.window = window;
    }

    public static void install(final long window) {
        try {
            Class.forName("pl.tomgirl.pylon.window.MainThread").getMethod("run", Runnable.class).invoke(null, new Runnable() {
                @Override public void run() { installNow(window); }
            });
        } catch (Throwable e) {
            System.err.println("Mouse fix not scheduled: " + unwrap(e));
        }
    }

    private static synchronized void installNow(long window) {
        if (window <= 0) return;
        try {
            MouseFix fix = new MouseFix(window);
            fix.init();
            installed.add(fix);
        } catch (Throwable e) {
            System.err.println("Mouse fix not installed: " + unwrap(e));
        }
    }

    private void init() throws Exception {
        Class<?> video = Class.forName("org.lwjgl.sdl.SDLVideo");
        int props = (Integer) video.getMethod("SDL_GetWindowProperties", long.class).invoke(null, window);
        long hwnd = (Long) Class.forName("org.lwjgl.sdl.SDLProperties")
            .getMethod("SDL_GetPointerProperty", int.class, CharSequence.class, long.class)
            .invoke(null, props, "SDL.window.win32.hwnd", 0L);
        if (hwnd == 0) throw new IllegalStateException("no HWND");

        Class<?> user = Class.forName("org.lwjgl.system.windows.User32");
        callPrevious = user.getMethod("nCallWindowProc", long.class, long.class, int.class, long.class, long.class);
        Object library = user.getMethod("getLibrary").invoke(null);
        sourceFunction = (Long) Class.forName("org.lwjgl.system.FunctionProvider")
            .getMethod("getFunctionAddress", CharSequence.class).invoke(library, "GetCurrentInputMessageSource");
        if (sourceFunction == 0) throw new IllegalStateException("GetCurrentInputMessageSource unavailable");
        sourceAddress = (Long) Class.forName("org.lwjgl.system.MemoryUtil").getMethod("memAddress", ByteBuffer.class).invoke(null, source);
        callSource = Class.forName("org.lwjgl.system.JNI").getMethod("callPI", long.class, long.class);
        relative = Class.forName("org.lwjgl.sdl.SDLMouse").getMethod("SDL_GetWindowRelativeMouseMode", long.class);
        windowId = (Integer) video.getMethod("SDL_GetWindowID", long.class).invoke(null, window);

        Class<?> event = Class.forName("org.lwjgl.sdl.SDL_Event");
        allocateEvent = event.getMethod("calloc");
        eventButton = event.getMethod("button");
        freeEvent = event.getMethod("free");
        pushEvent = Class.forName("org.lwjgl.sdl.SDLEvents").getMethod("SDL_PushEvent", event);
        Class<?> button = Class.forName("org.lwjgl.sdl.SDL_MouseButtonEvent");
        setType = button.getMethod("type", int.class);
        setWindow = button.getMethod("windowID", int.class);
        setButton = button.getMethod("button", byte.class);
        setDown = button.getMethod("down", boolean.class);
        setClicks = button.getMethod("clicks", byte.class);
        setX = button.getMethod("x", float.class);
        setY = button.getMethod("y", float.class);

        Class<?> procI = Class.forName("org.lwjgl.system.windows.WindowProcI");
        Object proxy = Proxy.newProxyInstance(procI.getClassLoader(), new Class<?>[]{procI}, this);
        Class<?> proc = Class.forName("org.lwjgl.system.windows.WindowProc");
        callback = proc.getMethod("create", procI).invoke(null, proxy);
        long address = (Long) proc.getMethod("address").invoke(callback);

        previous = (Long) user.getMethod("GetWindowLongPtr", long.class, int.class).invoke(null, hwnd, GWLP_WNDPROC);
        if (previous == 0) throw new IllegalStateException("no window proc");
        previous = (Long) user.getMethod("SetWindowLongPtr", IntBuffer.class, long.class, int.class, long.class)
            .invoke(null, null, hwnd, GWLP_WNDPROC, address);
        if (previous == 0) throw new IllegalStateException("SetWindowLongPtr failed");
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.isDefault()) {
            try { return invokeDefault.invoke(null, proxy, method, args); }
            catch (InvocationTargetException e) { throw e.getCause(); }
        }
        if (method.getDeclaringClass() == Object.class) {
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return "MouseFix";
        }

        long hwnd = (Long) args[0];
        int msg = (Integer) args[1];
        long wParam = (Long) args[2], lParam = (Long) args[3];
        boolean click = msg == WM_LBUTTONDOWN || msg == WM_LBUTTONUP || msg == WM_RBUTTONDOWN || msg == WM_RBUTTONUP;
        if (click && !disabled) {
            try {
                if ((Boolean) relative.invoke(null, window)) {
                    source.putLong(0, 0L);
                    int ok = (Integer) callSource.invoke(null, sourceAddress, sourceFunction);
                    if (ok != 0 && source.getInt(0) == 0 && source.getInt(4) == 0)
                        pushButton(msg, lParam);
                }
            } catch (Throwable e) {
                disabled = true;
                System.err.println("Mouse fix disabled: " + unwrap(e));
            }
        }
        try { return callPrevious.invoke(null, previous, hwnd, msg, wParam, lParam); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    private void pushButton(int msg, long lParam) throws Exception {
        boolean down = msg == WM_LBUTTONDOWN || msg == WM_RBUTTONDOWN;
        Object event = allocateEvent.invoke(null);
        try {
            Object button = eventButton.invoke(event);
            setType.invoke(button, down ? SDL_EVENT_MOUSE_BUTTON_DOWN : SDL_EVENT_MOUSE_BUTTON_UP);
            setWindow.invoke(button, windowId);
            setButton.invoke(button, (byte) (msg < WM_RBUTTONDOWN ? 1 : 3));
            setDown.invoke(button, down);
            setClicks.invoke(button, (byte) 1);
            setX.invoke(button, (float) (short) (lParam & 0xffff));
            setY.invoke(button, (float) (short) ((lParam >>> 16) & 0xffff));
            if (!(Boolean) pushEvent.invoke(null, event)) throw new IllegalStateException("SDL_PushEvent failed");
        } finally {
            freeEvent.invoke(event);
        }
    }

    private static Throwable unwrap(Throwable e) {
        return e instanceof InvocationTargetException ? e.getCause() : e;
    }
}
