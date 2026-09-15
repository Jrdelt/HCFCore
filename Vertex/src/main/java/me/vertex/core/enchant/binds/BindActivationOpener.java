package me.vertex.core.enchant.binds;

/** The player-selectable global gesture that opens the Bind HUD. Never saved inside a preset -- see the {@code /binds} spec. */
public enum BindActivationOpener {
    SHIFT_F("Shift + F"),
    DOUBLE_TAP_F("Double-Tap F"),
    SHIFT_RIGHT_CLICK("Shift + Right-Click"),
    LEFT_THEN_RIGHT_CLICK("Left-Click + Right-Click");

    private final String displayName;

    BindActivationOpener(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public BindActivationOpener next() {
        BindActivationOpener[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
