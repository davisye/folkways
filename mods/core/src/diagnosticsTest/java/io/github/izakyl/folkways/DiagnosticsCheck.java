package io.github.izakyl.folkways;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

public final class DiagnosticsCheck {
    private static final String BACKGROUND = "spark.backgroundProfiler";
    private static int checks;

    public static void main(String[] args) throws Exception {
        boolean nightly = "nightly".equals(args[0]);
        require(BuildChannel.nightly() == nightly, "packaged build channel");
        require(FolkwaysConfig.logPlanHeartbeat() == nightly, "heartbeat default follows channel");
        checkClientHook(nightly);
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

    @SuppressWarnings("deprecation")
    private static void checkClientHook(boolean nightly) throws Exception {
        String config = "/folkways-nightly-diagnostics.mixins.json";
        String mixin = "/io/github/izakyl/folkways/diagnostics/mixin/SparkClientBackgroundMixin.class";
        require((DiagnosticsCheck.class.getResource(config) != null) == nightly, "client hook config follows channel");
        require((DiagnosticsCheck.class.getResource(mixin) != null) == nightly, "client hook class follows channel");
        // Every mod has the common metadata path. Anchor it to Core's unique resource so Spark or a
        // classpath dependency cannot satisfy this assertion with a different mod's metadata.
        var anchor = DiagnosticsCheck.class.getResource("/META-INF/folkways/build.properties");
        require(anchor != null, "Core metadata resource anchor");
        try (var input = new URL(anchor, "../neoforge.mods.toml").openStream()) {
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            require(metadata.contains("modId=\"folkways_core\"") || metadata.contains("modId = \"folkways_core\""),
                "diagnostics registration is read from Core's own metadata");
            require(metadata.contains("folkways-nightly-diagnostics.mixins.json") == nightly,
                "client hook registration follows channel");
        }
        if (!nightly) return;

        // Check the actual fixed Spark artifact, rather than a fake option holder which misses its client veto.
        try (var input = DiagnosticsCheck.class.getResourceAsStream("/me/lucko/spark/common/sampler/BackgroundSamplerManager.class")) {
            require(input != null, "fixed Spark client lifecycle target is present");
            ClassNode target = new ClassNode();
            new ClassReader(input).accept(target, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            require(target.fields.stream().anyMatch(field -> field.name.equals("enabled") && field.desc.equals("Z")
                && (field.access & Opcodes.ACC_FINAL) != 0), "fixed Spark client enable field matches hook");
            String constructor = "(Lme/lucko/spark/common/SparkPlatform;Lme/lucko/spark/common/util/config/Configuration;)V";
            var create = target.methods.stream().filter(method -> method.name.equals("<init>")
                && method.desc.equals(constructor)).findFirst();
            require(create.isPresent(), "fixed Spark constructor matches the client hook");
            boolean veto = false;
            for (var instruction : create.orElseThrow().instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals("me/lucko/spark/common/platform/PlatformInfo$Type") && field.name.equals("CLIENT")) {
                    veto = true;
                }
            }
            require(veto, "actual Spark artifact contains the client lifecycle veto requiring the hook");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
