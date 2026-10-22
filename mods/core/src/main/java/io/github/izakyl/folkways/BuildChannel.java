package io.github.izakyl.folkways;

import java.io.IOException;
import java.util.Properties;

final class BuildChannel {
    private static final boolean NIGHTLY = read();

    private BuildChannel() {
    }

    static boolean nightly() {
        return NIGHTLY;
    }

    private static boolean read() {
        try (var stream = BuildChannel.class.getResourceAsStream("/META-INF/folkways/build.properties")) {
            if (stream == null) return false;
            var properties = new Properties();
            properties.load(stream);
            return "nightly".equals(properties.getProperty("channel"));
        } catch (IOException exception) {
            return false;
        }
    }
}
