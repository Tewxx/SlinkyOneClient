package tewxx.wtf.bridge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

public final class Agent {
    private static final String VERSION = "1.8.9-0.19.5";

    public static void premain(String args, Instrumentation inst) throws IOException {
        if (!"1.8.9".equals(System.getProperty("fabric.gameVersion")))
            throw new IllegalArgumentException("Requires -Dfabric.gameVersion=1.8.9");
        checkProperty("fabric.runtimeMappingNamespace", "official");
        checkProperty("fabric.defaultModDistributionNamespace", "intermediary");
        boolean legacyWindow = "legacy-window".equals(args);
        if (args != null && !args.isEmpty() && !legacyWindow)
            throw new IllegalArgumentException("Unknown agent option: " + args);

        Path gameDir = Paths.get("").toAbsolutePath();
        Path dir = gameDir.resolve("slinkybridge");
        Files.createDirectories(dir);
        Path classpath = dir.resolve("classpath.txt");
        Files.write(classpath, remapClasspath(gameDir).getBytes(StandardCharsets.UTF_8));

        System.setProperty("fabric.runtimeMappingNamespace", "official");
        System.setProperty("fabric.defaultModDistributionNamespace", "intermediary");
        System.setProperty("fabric.development", "true");
        System.setProperty("fabric.remapClasspathFile", classpath.toString());
        System.setProperty("fabric.fixPackageAccess", "true");
        System.setProperty("fabric.debug.disableModShuffle", "true");
        if (legacyWindow) {
            String disabled = System.getProperty("fabric.debug.disableModIds", "");
            System.setProperty("fabric.debug.disableModIds", disabled.isEmpty() ? "pylon" : disabled + ",pylon");
        }

        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        inst.addTransformer(new LoaderPatch());
        if (windows) inst.addTransformer(new RegisterAppPatch());
        inst.addTransformer(new DisplaySizePatch());

        Path runtimeJar = dir.resolve("runtime.jar");
        try (InputStream in = Agent.class.getResourceAsStream("runtime.jar")) {
            if (in == null) throw new IOException("runtime.jar missing from agent jar");
            Files.copy(in, runtimeJar, StandardCopyOption.REPLACE_EXISTING);
        }
        inst.addTransformer(new DisplayUpdatePatch(inst, runtimeJar, windows));
        System.setProperty("slinkybridge.active", "true");
    }

    private static String remapClasspath(Path gameDir) {
        Path cache = gameDir.resolve(".fabric/remappedJars/minecraft-" + VERSION);
        Path client = cache.resolve("client-intermediary.jar");
        Path realms = cache.resolve("realms-intermediary.jar");
        if (!Files.isRegularFile(client))
            throw new IllegalStateException("Missing " + client + ", launch once without the mod first");

        List<String> remap = new ArrayList<String>();
        boolean found = false;
        for (String entry : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))) {
            String path = entry.replace('\\', '/');
            if (path.endsWith("/metadata/versions/" + VERSION + "/" + VERSION + ".jar")) {
                remap.add(client.toString());
                found = true;
            } else if (path.contains("/com/mojang/realms/") && path.endsWith(".jar") && Files.isRegularFile(realms)) {
                remap.add(realms.toString());
            } else {
                remap.add(entry);
            }
        }
        if (!found) throw new IllegalStateException("Unrecognized game classpath");
        StringBuilder out = new StringBuilder();
        for (String entry : remap) {
            if (out.length() > 0) out.append(File.pathSeparator);
            out.append(entry);
        }
        return out.toString();
    }

    private static void checkProperty(String key, String expected) {
        String value = System.getProperty(key);
        if (value != null && !expected.equals(value))
            throw new IllegalArgumentException("Conflicting " + key + "=" + value);
    }
}
