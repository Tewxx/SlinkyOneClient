package tewxx.wtf.bridge;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

public final class Relauncher implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        if (Boolean.getBoolean("slinkybridge.active")) {
            if (!"official".equals(System.getProperty("fabric.runtimeMappingNamespace")))
                throw new IllegalStateException("Agent did not select official mappings");
            return;
        }
        try {
            relaunch();
        } catch (Exception e) {
            throw new RuntimeException("Relaunch failed", e);
        }
    }

    private static void relaunch() throws Exception {
        FabricLoader fabric = FabricLoader.getInstance();
        Path gameDir = fabric.getGameDir().toAbsolutePath();
        Path jar = fabric.getModContainer("slinkybridge").get().getOrigin().getPaths().get(0).toAbsolutePath();
        if (!Files.isRegularFile(jar)) throw new IllegalStateException("Mod must be loaded from a jar");

        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElseThrow(() -> new IllegalStateException("Can't find java executable")));
        command.add("-javaagent:" + jar);
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("net.fabricmc.loader.impl.launch.knot.KnotClient");
        command.addAll(Arrays.asList(fabric.getLaunchArguments(false)));

        Process child = new ProcessBuilder(command).directory(gameDir.toFile()).inheritIO().start();
        Runtime.getRuntime().addShutdownHook(new Thread(child::destroy));
        System.exit(child.waitFor());
    }
}
