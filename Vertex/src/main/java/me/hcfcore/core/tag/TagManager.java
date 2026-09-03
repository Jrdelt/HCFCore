package me.hcfcore.core.tag;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TagManager {

    private final Plugin plugin;
    private final File file;
    private final Map<String, Tag> tags = new HashMap<>();
    private final Map<UUID, PlayerPrefs> playerPrefs = new HashMap<>();
    private final Map<String, Integer> uses = new HashMap<>();

    public TagManager(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tags.yml");
    }

    public Plugin plugin() {
        return plugin;
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("tags.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        tags.clear();
        playerPrefs.clear();
        uses.clear();

        ConfigurationSection definitions = config.getConfigurationSection("tags");
        if (definitions != null) {
            for (String id : definitions.getKeys(false)) {
                ConfigurationSection section = definitions.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                tags.put(id.toLowerCase(Locale.ROOT), new Tag(id, section.getString("display", id),
                    section.getString("permission", "hcfcore.tag." + id.toLowerCase(Locale.ROOT)),
                    section.getString("rarity", "COMMON"), section.getLong("created-at", System.currentTimeMillis()),
                    section.getString("color", null)));
                uses.put(id.toLowerCase(Locale.ROOT), section.getInt("uses", 0));
            }
        }

        ConfigurationSection players = config.getConfigurationSection("players");
        if (players != null) {
            for (String uuidKey : players.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidKey);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                ConfigurationSection playerSection = players.getConfigurationSection(uuidKey);
                if (playerSection != null) {
                    playerPrefs.put(uuid, new PlayerPrefs(
                        playerSection.getString("tag"),
                        playerSection.getBoolean("nickname-match", false),
                        playerSection.getBoolean("nickname-reversed", false)));
                } else {
                    // Backward-compat: a bare string value is just a tag id.
                    String legacyTagId = players.getString(uuidKey);
                    if (legacyTagId != null) {
                        playerPrefs.put(uuid, new PlayerPrefs(legacyTagId, false, false));
                    }
                }
            }
        }
    }

    public List<Tag> getSorted(Sort sort, boolean ascending) {
        List<Tag> result = new ArrayList<>(tags.values());
        Comparator<Tag> comparator = switch (sort) {
            case ALPHABETICAL -> Comparator.comparing(Tag::display, String.CASE_INSENSITIVE_ORDER);
            case AGE -> Comparator.comparingLong(Tag::createdAt);
            case RARITY -> Comparator.comparingInt(tag -> rarityOrder(tag.rarity()));
        };
        if (!ascending) {
            comparator = comparator.reversed();
        }
        return result.stream().sorted(comparator.thenComparing(Tag::id)).toList();
    }

    public Tag get(String id) {
        return id == null ? null : tags.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean isUnlocked(Player player, Tag tag) {
        return tag != null && (tag.permission().isBlank() || player.hasPermission(tag.permission()));
    }

    public int playerCount(String id) {
        return (int) playerPrefs.values().stream()
                .filter(prefs -> id.equalsIgnoreCase(prefs.tagId()))
                .count();
    }

    public int uses(String id) {
        return uses.getOrDefault(id.toLowerCase(Locale.ROOT), 0);
    }

    public String getPlayerTag(UUID uuid) {
        return playerPrefs.getOrDefault(uuid, PlayerPrefs.DEFAULT).tagId();
    }

    public void select(UUID uuid, String id) {
        Tag tag = get(id);
        if (tag == null) {
            return;
        }
        PlayerPrefs current = playerPrefs.getOrDefault(uuid, PlayerPrefs.DEFAULT);
        String previous = current.tagId();
        playerPrefs.put(uuid, current.withTag(tag.id()));
        if (previous == null || !previous.equalsIgnoreCase(tag.id())) {
            uses.merge(tag.id().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        save();
    }

    public boolean isNicknameMatchEnabled(UUID uuid) {
        return playerPrefs.getOrDefault(uuid, PlayerPrefs.DEFAULT).nicknameMatch();
    }

    public void setNicknameMatchEnabled(UUID uuid, boolean enabled) {
        playerPrefs.put(uuid, playerPrefs.getOrDefault(uuid, PlayerPrefs.DEFAULT).withNicknameMatch(enabled));
        save();
    }

    public boolean isNicknameReversed(UUID uuid) {
        return playerPrefs.getOrDefault(uuid, PlayerPrefs.DEFAULT).nicknameReversed();
    }

    public void setNicknameReversed(UUID uuid, boolean reversed) {
        playerPrefs.put(uuid, playerPrefs.getOrDefault(uuid, PlayerPrefs.DEFAULT).withNicknameReversed(reversed));
        save();
    }

    /**
     * The color/gradient to render the player's name with in chat, or null
     * if nickname-match is off, they have no tag equipped, or their tag
     * has no color set.
     */
    public String getNicknameColor(Player player) {
        PlayerPrefs prefs = playerPrefs.get(player.getUniqueId());
        if (prefs == null || !prefs.nicknameMatch() || prefs.tagId() == null) {
            return null;
        }
        Tag tag = get(prefs.tagId());
        if (tag == null || tag.color() == null || tag.color().isBlank()) {
            return null;
        }
        return prefs.nicknameReversed() ? GradientColor.reverse(tag.color()) : tag.color();
    }

    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Tag tag : tags.values()) {
            String path = "tags." + tag.id();
            config.set(path + ".display", tag.display());
            config.set(path + ".permission", tag.permission());
            config.set(path + ".rarity", tag.rarity());
            config.set(path + ".created-at", tag.createdAt());
            config.set(path + ".uses", uses(tag.id()));
            if (tag.color() != null && !tag.color().isBlank()) {
                config.set(path + ".color", tag.color());
            }
        }
        for (Map.Entry<UUID, PlayerPrefs> entry : playerPrefs.entrySet()) {
            String path = "players." + entry.getKey();
            PlayerPrefs prefs = entry.getValue();
            if (prefs.tagId() != null) {
                config.set(path + ".tag", prefs.tagId());
            }
            config.set(path + ".nickname-match", prefs.nicknameMatch());
            config.set(path + ".nickname-reversed", prefs.nicknameReversed());
        }
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save tags.yml: " + e.getMessage());
        }
    }

    public static String formatDate(long epochMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC).toString();
    }

    public static String formatMonth(long epochMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC).toString().substring(0, 7);
    }

    private static int rarityOrder(String rarity) {
        return switch (rarity.toUpperCase(Locale.ROOT)) {
            case "LEGENDARY" -> 0;
            case "EPIC" -> 1;
            case "RARE" -> 2;
            default -> 3;
        };
    }

    public enum Sort { ALPHABETICAL, AGE, RARITY }

    public enum Filter { YOUR, UNOWNED, ALL }

    public record Tag(String id, String display, String permission, String rarity, long createdAt, String color) { }

    private record PlayerPrefs(String tagId, boolean nicknameMatch, boolean nicknameReversed) {
        static final PlayerPrefs DEFAULT = new PlayerPrefs(null, false, false);

        PlayerPrefs withTag(String newTagId) {
            return new PlayerPrefs(newTagId, nicknameMatch, nicknameReversed);
        }

        PlayerPrefs withNicknameMatch(boolean value) {
            return new PlayerPrefs(tagId, value, nicknameReversed);
        }

        PlayerPrefs withNicknameReversed(boolean value) {
            return new PlayerPrefs(tagId, nicknameMatch, value);
        }
    }
}
