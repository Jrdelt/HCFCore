package me.vertex.core.network;

public enum ShardState {
    ONLINE,
    DRAINING,
    RESTARTING,
    OFFLINE,
    CRASH_RECOVERY;

    public static ShardState parse(String raw, ShardState fallback) {
        try { return valueOf(raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }
}
