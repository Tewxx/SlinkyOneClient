package tewxx.wtf.bridge;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

public final class DisplayUpdatePatch implements ClassFileTransformer {
    private static final String SDL = "pl/tomgirl/pylon/window/DisplaySdl";
    private static final String RUNTIME = "tewxx/wtf/bridge/runtime/";

    private final Instrumentation inst;
    private final Path runtimeJar;
    private final boolean windows;
    private ClassLoader loadedInto;

    public DisplayUpdatePatch(Instrumentation inst, Path runtimeJar, boolean windows) {
        this.inst = inst;
        this.runtimeJar = runtimeJar;
        this.windows = windows;
    }

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
        byte[] result;
        if (windows && SDL.equals(name)) result = patchMouse(bytes);
        else if ("ave".equals(name)) result = patchUpdate(bytes);
        else return null;

        if (result == null) return null;
        try {
            loadRuntime(loader);
        } catch (ReflectiveOperationException e) {
            System.err.println("Couldn't load runtime.jar: " + e);
            return null;
        }
        return result;
    }

    private synchronized void loadRuntime(ClassLoader loader) throws ReflectiveOperationException {
        if (loadedInto == loader) return;
        Object launcher = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase").getMethod("getLauncher").invoke(null);
        Class.forName("net.fabricmc.loader.impl.launch.FabricLauncher")
            .getMethod("addToClassPath", Path.class, String[].class)
            .invoke(launcher, runtimeJar, new String[]{"tewxx.wtf.bridge.runtime."});
        Class.forName("tewxx.wtf.bridge.runtime.DisplayHook", true, loader)
            .getMethod("init", Instrumentation.class).invoke(null, inst);
        loadedInto = loader;
    }

    private static byte[] patchMouse(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final int[] count = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, method, desc, signature, exceptions);
                if (!"create".equals(method) || !"(Lpl/tomgirl/pylon/window/GpuSurface;)V".equals(desc)) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, SDL, "handle", "J");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME + "MouseFix", "install", "(J)V", false);
                            count[0]++;
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        if (count[0] != 1) {
            System.err.println("Couldn't patch DisplaySdl.create (" + count[0] + ")");
            return null;
        }
        return writer.toByteArray();
    }

    private static boolean isDisplayUpdate(int opcode, String owner, String name, String desc) {
        return opcode == Opcodes.INVOKESTATIC && "org/lwjgl/opengl/Display".equals(owner) && "update".equals(name) && "()V".equals(desc);
    }

    private static byte[] patchUpdate(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        final boolean[] direct = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                if (!"h".equals(method) || !"()V".equals(desc)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (isDisplayUpdate(opcode, owner, name, desc)) direct[0] = true;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (direct[0]) return null;

        ClassWriter writer = new ClassWriter(reader, 0);
        final int[] count = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, method, desc, signature, exceptions);
                if (!method.startsWith("mixinextras$bridge$update$")) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (isDisplayUpdate(opcode, owner, name, desc)) {
                            owner = RUNTIME + "DisplayHook";
                            name = "update";
                            count[0]++;
                        }
                        super.visitMethodInsn(opcode, owner, name, desc, itf);
                    }
                };
            }
        }, 0);
        if (count[0] != 1) {
            System.err.println("Couldn't patch Display.update wrapper (" + count[0] + ")");
            return null;
        }
        return writer.toByteArray();
    }
}
