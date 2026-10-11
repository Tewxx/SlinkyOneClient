package tewxx.wtf.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.discovery.RuntimeModRemapper;

public final class RemapCache {
    public static void remap(Collection<ModCandidateImpl> mods, Path tmpDir, Path outputDir) {
        Path index = outputDir.resolveSibling("remapped.txt");
        Path keyFile = outputDir.resolveSibling("remapped-key.txt");
        String key;
        try {
            key = key(mods, outputDir.getParent().getParent());
            if (restore(mods, index, keyFile, key)) return;
            if (Files.isRegularFile(keyFile))
                Files.move(keyFile, outputDir.resolveSibling("remapped-key.old.txt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("Remap cache unusable: " + e);
            key = null;
        }

        RuntimeModRemapper.remap(mods, tmpDir, outputDir);
        if (key == null) return;

        StringBuilder out = new StringBuilder();
        for (ModCandidateImpl mod : mods) {
            if (mod.getRequiresRemap() && mod.hasPath())
                out.append(mod.getId()).append('\t').append(mod.getPaths().get(0)).append('\n');
        }
        try {
            Files.write(index, out.toString().getBytes(StandardCharsets.UTF_8));
            Files.write(keyFile, key.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Couldn't save remap cache: " + e);
        }
    }

    private static boolean restore(Collection<ModCandidateImpl> mods, Path index, Path keyFile, String key) throws IOException {
        if (!Files.isRegularFile(index) || !Files.isRegularFile(keyFile)) return false;
        if (!key.equals(new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8))) return false;

        Map<String, Path> cached = new HashMap<String, Path>();
        for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
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

    private static String key(Collection<ModCandidateImpl> mods, Path fabricDir) throws IOException {
        List<String> parts = new ArrayList<String>();
        for (ModCandidateImpl mod : mods) {
            StringBuilder part = new StringBuilder(mod.getId()).append(' ').append(mod.getVersion()).append(' ').append(mod.getRequiresRemap());
            List<Path> origins = mod.getOriginPaths();
            if (origins == null) origins = Collections.emptyList();
            for (Path path : origins) {
                Path absolute = path.toAbsolutePath();
                if (absolute.startsWith(fabricDir)) {
                    part.append(' ').append(absolute.getFileName());
                } else {
                    part.append(' ').append(absolute);
                    if (Files.isRegularFile(absolute))
                        part.append(' ').append(Files.size(absolute)).append(' ').append(Files.getLastModifiedTime(absolute).toMillis());
                }
            }
            parts.add(part.toString());
        }
        Collections.sort(parts);
        String classpath = new String(Files.readAllBytes(Paths.get(System.getProperty("fabric.remapClasspathFile"))), StandardCharsets.UTF_8);
        List<String> entries = new ArrayList<String>(Arrays.asList(classpath.split(Pattern.quote(File.pathSeparator))));
        Collections.sort(entries);
        parts.addAll(entries);

        StringBuilder key = new StringBuilder();
        for (String part : parts) key.append(part).append('\n');
        return key.toString();
    }
}
