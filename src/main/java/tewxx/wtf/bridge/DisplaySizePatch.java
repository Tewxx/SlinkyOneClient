package tewxx.wtf.bridge;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import org.objectweb.asm.*;

public final class DisplaySizePatch implements ClassFileTransformer {
    private static final String SDL = "pl/tomgirl/pylon/window/DisplaySdl";
    private static final String DISPLAY = "org/lwjgl/opengl/Display";
    private static final Set<String> FIELDS = new HashSet<String>(Arrays.asList(
        "width", "height", "framebufferWidth", "framebufferHeight"));

    @Override public byte[] transform(ClassLoader loader, final String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
        if (!SDL.equals(name) && !DISPLAY.equals(name)) return null;
        final boolean isSdl = SDL.equals(name);
        final Set<String> existing = new HashSet<String>();
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public FieldVisitor visitField(int access, String field, String desc, String signature, Object value) {
                existing.add(field);
                return super.visitField(access, field, desc, signature, value);
            }

            @Override public MethodVisitor visitMethod(int access, final String method, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, method, desc, signature, exceptions)) {
                    @Override public void visitFieldInsn(int opcode, String owner, String field, String fieldDesc) {
                        boolean mirror = isSdl && !method.startsWith("<") && opcode == Opcodes.PUTFIELD
                            && SDL.equals(owner) && "I".equals(fieldDesc) && FIELDS.contains(field);
                        if (mirror) super.visitInsn(Opcodes.DUP_X1);
                        super.visitFieldInsn(opcode, owner, field, fieldDesc);
                        if (mirror) super.visitFieldInsn(Opcodes.PUTSTATIC, DISPLAY, field, "I");
                    }

                    @Override public void visitInsn(int opcode) {
                        if (!isSdl && "<clinit>".equals(method) && opcode == Opcodes.RETURN) {
                            copy("width", "getWindowWidth");
                            copy("height", "getWindowHeight");
                            copy("framebufferWidth", "getWidth");
                            copy("framebufferHeight", "getHeight");
                        }
                        super.visitInsn(opcode);
                    }

                    private void copy(String field, String getter) {
                        super.visitFieldInsn(Opcodes.GETSTATIC, DISPLAY, "SDL", "L" + SDL + ";");
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SDL, getter, "()I", false);
                        super.visitFieldInsn(Opcodes.PUTSTATIC, DISPLAY, field, "I");
                    }
                };
            }

            @Override public void visitEnd() {
                if (!isSdl) {
                    for (String field : FIELDS) {
                        if (!existing.contains(field))
                            super.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, field, "I", null, null).visitEnd();
                    }
                }
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }
}
