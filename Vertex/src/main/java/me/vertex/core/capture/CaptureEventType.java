package me.vertex.core.capture;

import java.util.Locale;

/** The two capture-event reward families supported by Vertex. */
public enum CaptureEventType {
    KOTH("koth", "KOTH"),
    OUTPOST("outpost", "OUTPOST");

    private final String id;
    private final String display;

    CaptureEventType(String id, String display) {
        this.id = id;
        this.display = display;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public static CaptureEventType from(String value) {
        if (value == null) {
            return null;
        }
        for (CaptureEventType type : values()) {
            if (type.id.equals(value.toLowerCase(Locale.ROOT))) {
                return type;
            }
        }
        return null;
    }
}
