package me.vertex.core.factions;

/** Immutable public faction state. Mutable server maps always replace records atomically. */
public record FactionData(int id, String tag, String description, boolean open, boolean system,
                          double power, double powerMax, long createdAtMillis,
                          Home home) {
    public FactionData withTag(String value) { return new FactionData(id, value, description, open, system, power, powerMax, createdAtMillis, home); }
    public FactionData withDescription(String value) { return new FactionData(id, tag, value, open, system, power, powerMax, createdAtMillis, home); }
    public FactionData withOpen(boolean value) { return new FactionData(id, tag, description, value, system, power, powerMax, createdAtMillis, home); }
    public FactionData withPower(double value, double max) { return new FactionData(id, tag, description, open, system, value, max, createdAtMillis, home); }
    public FactionData withHome(Home value) { return new FactionData(id, tag, description, open, system, power, powerMax, createdAtMillis, value); }

    public record Home(String shardId, String world, double x, double y, double z, float yaw, float pitch) {
        /** Backward-compatible constructor for existing standalone callers and stored tests. */
        public Home(String world, double x, double y, double z, float yaw, float pitch) {
            this("", world, x, y, z, yaw, pitch);
        }
    }
}
