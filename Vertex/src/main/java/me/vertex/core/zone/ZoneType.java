package me.vertex.core.zone;

import java.util.Locale;

/** The two Vertex farming-zone rule sets. */
public enum ZoneType {
    HAVEN("Haven", false),
    RIFTLANDS("Riftlands", true);

    private final String displayName;
    private final boolean pvp;

    ZoneType(String displayName, boolean pvp) {
        this.displayName = displayName;
        this.pvp = pvp;
    }

    public String displayName() {
        return displayName;
    }

    public boolean pvpEnabled() {
        return pvp;
    }

    public String configKey() {
        return name().toLowerCase(Locale.ROOT);
    }
}
