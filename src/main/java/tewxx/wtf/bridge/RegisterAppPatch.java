package tewxx.wtf.bridge;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

public final class RegisterAppPatch implements ClassFileTransformer {
    private static final String DISPLAY = "pl/tomgirl/pylon/window/DisplaySdl";
    private static final String FUNCTIONS = "org/lwjgl/sdl/SDLMain$Functions";
    private static final String MEMORY = "org/lwjgl/system/MemoryUtil";

    @Override public byte[] transform(ClassLoader loader, final String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
        if (!DISPLAY.equals(name) && !FUNCTIONS.equals(name)) return null;
        final int[] count = new int[2];
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, method, desc, signature, exceptions)) {
                    @Override public void visitLdcInsn(Object value) {
                        if (FUNCTIONS.equals(name)) {
                            if ("SDL_SDL_RegisterApp".equals(value)) { value = "SDL_RegisterApp"; count[0]++; }
                            if ("SDL_SDL_UnregisterApp".equals(value)) { value = "SDL_UnregisterApp"; count[1]++; }
                        }
                        super.visitLdcInsn(value);
                    }

                    @Override public void visitMethodInsn(int opcode, String owner, String target, String desc, boolean itf) {
                        boolean sdlInit = DISPLAY.equals(name) && opcode == Opcodes.INVOKESTATIC && "org/lwjgl/sdl/SDLInit".equals(owner);
                        if (sdlInit && "SDL_Init".equals(target) && "(I)Z".equals(desc)) {
                            super.visitLdcInsn("LWJGL");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, MEMORY, "memUTF8", "(Ljava/lang/CharSequence;)Ljava/nio/ByteBuffer;", false);
                            super.visitInsn(Opcodes.DUP);
                            super.visitIntInsn(Opcodes.SIPUSH, 0x1020);
                            super.visitInsn(Opcodes.LCONST_0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/sdl/SDLMain", "SDL_SDL_RegisterApp", "(Ljava/nio/ByteBuffer;IJ)Z", false);
                            super.visitInsn(Opcodes.SWAP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, MEMORY, "memFree", "(Ljava/nio/ByteBuffer;)V", false);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, DISPLAY, "checkSdlError", "(Z)V", false);
                            count[0]++;
                        }
                        super.visitMethodInsn(opcode, owner, target, desc, itf);
                        if (sdlInit && "SDL_Quit".equals(target) && "()V".equals(desc)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/sdl/SDLMain", "SDL_SDL_UnregisterApp", "()V", false);
                            count[1]++;
                        }
                    }
                };
            }
        }, 0);
        if (count[0] != 1 || count[1] != 1) {
            System.err.println("Couldn't patch " + name + " (" + count[0] + "/" + count[1] + ")");
            return null;
        }
        return writer.toByteArray();
    }
}
