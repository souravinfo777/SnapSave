package com.snapsave.app.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-API-key cooldowns so that when one key is rate-limited, invalid or out of credits
 * the resolver automatically rotates to the next key.
 */
public final class KeyPool {

    private static final Map<String, Long> COOLDOWN_UNTIL = new ConcurrentHashMap<String, Long>();

    private KeyPool() {
    }

    public static void cool(String key, long ms) {
        if (key != null) {
            COOLDOWN_UNTIL.put(key, System.currentTimeMillis() + ms);
        }
    }

    public static boolean cooling(String key) {
        Long t = COOLDOWN_UNTIL.get(key);
        return t != null && t > System.currentTimeMillis();
    }

    public static long remainingMs(String key) {
        Long t = COOLDOWN_UNTIL.get(key);
        return t == null ? 0 : Math.max(0, t - System.currentTimeMillis());
    }

    public static void clear(String key) {
        COOLDOWN_UNTIL.remove(key);
    }
}
