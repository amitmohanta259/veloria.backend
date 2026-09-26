package com.veloria.automation.support;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Environment first, then automation.properties. */
public final class Config {

    private static final Properties DEFAULTS = new Properties();

    static {
        try (InputStream in = Config.class.getResourceAsStream("/automation.properties")) {
            if (in != null) DEFAULTS.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read automation.properties", e);
        }
    }

    private Config() {}

    public static String get(String key) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) return env;
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) return sys;
        String def = DEFAULTS.getProperty(key);
        if (def == null) throw new IllegalStateException("Missing configuration: " + key);
        return def;
    }

    public static String apiUrl()     { return get("VELORIA_API_URL"); }
    public static String dbUrl()      { return get("VELORIA_DB_URL"); }
    public static String dbUser()     { return get("VELORIA_DB_USER"); }
    public static String dbPassword() { return get("VELORIA_DB_PASSWORD"); }
}
