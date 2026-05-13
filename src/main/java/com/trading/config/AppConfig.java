package com.trading.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class AppConfig {

    private static final Logger     log   = LoggerFactory.getLogger(AppConfig.class);
    private static final Properties props = new Properties();

    static {
        // 1. Load bundled defaults from JAR classpath
        try (InputStream is = AppConfig.class.getClassLoader()
                                             .getResourceAsStream("application.properties")) {
            if (is != null) props.load(is);
        } catch (IOException e) {
            log.warn("Could not load bundled application.properties: {}", e.getMessage());
        }

        // 2. Override with external file in working directory (config/application.properties or application.properties)
        //    This allows VPS deployments to keep credentials outside the JAR.
        String[] externalPaths = {
            "config/application.properties",
            "application.properties"
        };
        for (String path : externalPaths) {
            java.io.File f = new java.io.File(path);
            if (f.exists()) {
                try (InputStream is = new java.io.FileInputStream(f)) {
                    props.load(is);
                    log.info("Loaded external config: {} ({} keys total)", f.getAbsolutePath(), props.size());
                } catch (IOException e) {
                    log.error("Failed to load external config {}: {}", path, e.getMessage());
                }
                break;
            }
        }

        if (props.isEmpty()) {
            log.warn("No application.properties found — using defaults and system properties only");
        }
    }

    private AppConfig() {}

    public static String get(String key) {
        String val = System.getProperty(key, props.getProperty(key));
        if (val == null) throw new IllegalStateException("Missing required config: " + key);
        return val.trim();
    }

    public static String get(String key, String defaultValue) {
        String val = System.getProperty(key, props.getProperty(key));
        return val == null ? defaultValue : val.trim();
    }

    public static int    getInt(String key, int def)       { return Integer.parseInt(get(key, String.valueOf(def))); }
    public static double getDouble(String key, double def) { return Double.parseDouble(get(key, String.valueOf(def))); }
    public static boolean getBool(String key, boolean def) { return Boolean.parseBoolean(get(key, String.valueOf(def))); }

    public static boolean isPaperMode() {
        return "paper".equalsIgnoreCase(get("trading.mode", "paper"));
    }
}
