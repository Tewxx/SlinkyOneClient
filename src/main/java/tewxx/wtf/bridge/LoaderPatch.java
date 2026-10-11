package tewxx.wtf.bridge;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

public final class LoaderPatch implements ClassFileTransformer {
    private static final String REMAPPER = "net/fabricmc/loader/impl/discovery/RuntimeModRemapper";

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
        if (!"net/fabricmc/loader/impl/FabricLoaderImpl".equals(name)) return null;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        final int[] count = new int[2];
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, method, desc, signature, exceptions);
                if (!"setup".equals(method)) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override public void visitLdcInsn(Object value) {
                        if (".fabric".equals(value)) {
                            value = ".fabric/slinkybridge-official";
                            count[0]++;
                        }
                        super.visitLdcInsn(value);
                    }

                    @Override public void visitMethodInsn(int opcode, String owner, String target, String desc, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC && REMAPPER.equals(owner) && "remap".equals(target)) {
                            owner = "tewxx/wtf/bridge/RemapCache";
                            count[1]++;
                        }
                        super.visitMethodInsn(opcode, owner, target, desc, itf);
                    }
                };
            }
        }, 0);
        if (count[0] != 1 || count[1] != 1) {
            System.err.println("Couldn't patch fabric loader (" + count[0] + "/" + count[1] + ")");
            return null;
        }
        return writer.toByteArray();
    }
}
