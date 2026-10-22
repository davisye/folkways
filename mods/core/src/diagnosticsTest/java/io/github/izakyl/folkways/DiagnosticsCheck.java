package io.github.izakyl.folkways;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;

public final class DiagnosticsCheck {
    private static final String BACKGROUND = "spark.backgroundProfiler";
    private static int checks;

    public static void main(String[] args) throws Exception {
        boolean nightly = "nightly".equals(args[0]);
        require(BuildChannel.nightly() == nightly, "packaged build channel");
        require(FolkwaysConfig.logPlanHeartbeat() == nightly, "heartbeat default follows channel");
        Path directory = Files.createTempDirectory("folkways-diagnostics-");
        String previous = System.getProperty(BACKGROUND);
        Path config = directory.resolve("config.json");
        try {
            System.clearProperty(BACKGROUND);
            NightlyDiagnostics.installDefaults(config);
            require(Objects.equals(nightly ? "true" : null, System.getProperty(BACKGROUND)), "fresh install default");
            require(!Files.exists(config), "no persistent Spark preference written");

            System.setProperty(BACKGROUND, "false");
            NightlyDiagnostics.installDefaults(config);
            require("false".equals(System.getProperty(BACKGROUND)), "explicit JVM opt-out");

            System.clearProperty(BACKGROUND);
            Files.writeString(config, "{\"backgroundProfiler\":false,\"other\":123}");
            NightlyDiagnostics.installDefaults(config);
            require(System.getProperty(BACKGROUND) == null, "saved Spark opt-out");
            require(Files.readString(config).equals("{\"backgroundProfiler\":false,\"other\":123}"), "saved config preserved");

            Files.writeString(config, "{}");
            NightlyDiagnostics.installDefaults(config);
            require(nightly == "true".equals(System.getProperty(BACKGROUND)), "config with no profiler preference");

            System.clearProperty(BACKGROUND);
            Files.writeString(config, "invalid-json");
            NightlyDiagnostics.installDefaults(config);
            require(System.getProperty(BACKGROUND) == null, "invalid config does not force sampling");
        } finally {
            if (previous == null) System.clearProperty(BACKGROUND);
            else System.setProperty(BACKGROUND, previous);
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Diagnostics " + args[0] + ": " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
