package tewxx.wtf.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.discovery.RuntimeModRemapper;

public final class RemapCache {
    public static void remap(Collection<ModCandidateImpl> mods, Path tmpDir, Path outputDir) {
        Path index = outputDir.resolveSibling("remapped.txt");
        String key;
        try {
            key = key(mods);
            if (restore(mods, index, key)) return;
        } catch (Exception e) {
            System.err.println("Remap cache unusable: " + e);
            key = null;
        }

        RuntimeModRemapper.remap(mods, tmpDir, outputDir);
        if (key == null) return;

        StringBuilder out = new StringBuilder(key).append('\n');
        for (ModCandidateImpl mod : mods) {
            if (mod.getRequiresRemap() && mod.hasPath())
                out.append(mod.getId()).append('\t').append(mod.getPaths().get(0)).append('\n');
        }
        try {
            Files.write(index, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Couldn't save remap cache: " + e);
        }
    }

    private static boolean restore(Collection<ModCandidateImpl> mods, Path index, String key) throws IOException {
        if (!Files.isRegularFile(index)) return false;
        List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !key.equals(lines.get(0))) return false;

        Map<String, Path> cached = new HashMap<String, Path>();
        for (String line : lines.subList(1, lines.size())) {
            int tab = line.indexOf('\t');
            if (tab > 0) cached.put(line.substring(0, tab), Paths.get(line.substring(tab + 1)));
        }
        for (ModCandidateImpl mod : mods) {
            if (!mod.getRequiresRemap()) continue;
            Path path = cached.get(mod.getId());
            if (path == null || !Files.isRegularFile(path)) return false;
        }
        for (ModCandidateImpl mod : mods) {
            if (mod.getRequiresRemap()) mod.setPaths(Collections.singletonList(cached.get(mod.getId())));
        }
        return true;
    }

    private static String key(Collection<ModCandidateImpl> mods) throws Exception {
        List<String> parts = new ArrayList<String>();
        for (ModCandidateImpl mod : mods) {
            StringBuilder part = new StringBuilder(mod.getId()).append(' ').append(mod.getVersion()).append(' ').append(mod.getRequiresRemap());
            for (Path path : mod.getOriginPaths()) {
                part.append(' ').append(path);
                if (Files.isRegularFile(path))
                    part.append(' ').append(Files.size(path)).append(' ').append(Files.getLastModifiedTime(path).toMillis());
            }
            parts.add(part.toString());
        }
        Collections.sort(parts);
        parts.add(new String(Files.readAllBytes(Paths.get(System.getProperty("fabric.remapClasspathFile"))), StandardCharsets.UTF_8));

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (String part : parts) sha.update((part + '\n').getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
