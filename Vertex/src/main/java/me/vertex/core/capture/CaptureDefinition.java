package me.vertex.core.capture;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/** Immutable configured bounds, schedule, capture speed, and rewards for one event. */
final class CaptureDefinition {
    private final String id;
    private final CaptureEventType type;
    private final String displayName;
    private final String worldName;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final double hologramX;
    private final double hologramY;
    private final double hologramZ;
    private final int captureSeconds;
    private final int maxDurationSeconds;
    private final double additionalMemberSpeed;
    private final boolean abilitiesDisabled;
    private final List<String> scheduleTimes;
    private final List<String> rewardCommands;
    private final double outpostXpMultiplier;
    private final long outpostXpDurationSeconds;

    private CaptureDefinition(String id, CaptureEventType type, String displayName, String worldName,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            double hologramX, double hologramY, double hologramZ, int captureSeconds, int maxDurationSeconds,
            double additionalMemberSpeed, boolean abilitiesDisabled, List<String> scheduleTimes, List<String> rewardCommands,
            double outpostXpMultiplier, long outpostXpDurationSeconds) {
        this.id = id;
        this.type = type;
        this.displayName = displayName;
        this.worldName = worldName;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.hologramX = hologramX;
        this.hologramY = hologramY;
        this.hologramZ = hologramZ;
        this.captureSeconds = captureSeconds;
        this.maxDurationSeconds = maxDurationSeconds;
        this.additionalMemberSpeed = additionalMemberSpeed;
        this.abilitiesDisabled = abilitiesDisabled;
        this.scheduleTimes = List.copyOf(scheduleTimes);
        this.rewardCommands = List.copyOf(rewardCommands);
        this.outpostXpMultiplier = outpostXpMultiplier;
        this.outpostXpDurationSeconds = outpostXpDurationSeconds;
    }

    static CaptureDefinition from(String id, ConfigurationSection section, int defaultCaptureSeconds,
            double defaultAdditionalMemberSpeed, int defaultMaxDurationSeconds) {
        if (section == null) {
            return null;
        }
        CaptureEventType type = CaptureEventType.from(section.getString("type"));
        String worldName = section.getString("world");
        ConfigurationSection minimum = section.getConfigurationSection("minimum");
        ConfigurationSection maximum = section.getConfigurationSection("maximum");
        if (type == null || worldName == null || worldName.isBlank() || minimum == null || maximum == null) {
            return null;
        }
        int minX = Math.min(minimum.getInt("x"), maximum.getInt("x"));
        int minY = Math.min(minimum.getInt("y"), maximum.getInt("y"));
        int minZ = Math.min(minimum.getInt("z"), maximum.getInt("z"));
        int maxX = Math.max(minimum.getInt("x"), maximum.getInt("x"));
        int maxY = Math.max(minimum.getInt("y"), maximum.getInt("y"));
        int maxZ = Math.max(minimum.getInt("z"), maximum.getInt("z"));
        ConfigurationSection hologram = section.getConfigurationSection("hologram");
        double hologramX = hologram == null ? (minX + maxX + 1D) / 2D : hologram.getDouble("x");
        double hologramY = hologram == null ? minY + 2D : hologram.getDouble("y");
        double hologramZ = hologram == null ? (minZ + maxZ + 1D) / 2D : hologram.getDouble("z");
        ConfigurationSection outpostBooster = section.getConfigurationSection("rewards.xp-booster");
        return new CaptureDefinition(id, type, section.getString("display-name", id), worldName,
                minX, minY, minZ, maxX, maxY, maxZ, hologramX, hologramY, hologramZ,
                Math.max(1, section.getInt("capture-seconds", defaultCaptureSeconds)),
                Math.max(1, section.getInt("max-duration-seconds", defaultMaxDurationSeconds)),
                Math.max(0D, section.getDouble("additional-member-speed", defaultAdditionalMemberSpeed)),
                section.getBoolean("disable-abilities", false),
                section.getStringList("schedule-times"), section.getStringList("rewards.commands"),
                outpostBooster == null || !outpostBooster.getBoolean("enabled", false) ? 1D
                        : Math.max(1D, outpostBooster.getDouble("multiplier", 1D)),
                outpostBooster == null ? 0L : Math.max(0L, outpostBooster.getLong("duration-seconds", 0L)));
    }

    String id() { return id; }
    CaptureEventType type() { return type; }
    String displayName() { return displayName; }
    String worldName() { return worldName; }
    int captureSeconds() { return captureSeconds; }
    int maxDurationSeconds() { return maxDurationSeconds; }
    double additionalMemberSpeed() { return additionalMemberSpeed; }
    boolean abilitiesDisabled() { return abilitiesDisabled; }
    List<String> scheduleTimes() { return scheduleTimes; }
    List<String> rewardCommands() { return rewardCommands; }
    double outpostXpMultiplier() { return outpostXpMultiplier; }
    long outpostXpDurationSeconds() { return outpostXpDurationSeconds; }

    Location center() {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, (minX + maxX + 1D) / 2D, (minY + maxY + 1D) / 2D,
                (minZ + maxZ + 1D) / 2D);
    }

    Location hologramLocation() {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, hologramX, hologramY, hologramZ);
    }

    boolean contains(Location location) {
        return location != null && location.getWorld() != null && location.getWorld().getName().equals(worldName)
                && location.getBlockX() >= minX && location.getBlockX() <= maxX
                && location.getBlockY() >= minY && location.getBlockY() <= maxY
                && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
    }

    long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }
}
