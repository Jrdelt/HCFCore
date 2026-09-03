package me.hcfcore.core.tag;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TagManager {

    private static final DateTimeFormatter CREATED_FORMAT = DateTimeFormatter.ofPattern("MM/yy", Locale.ROOT);

    private final Plugin plugin;
    private final File file;
    private final Map<String, Tag> tags = new HashMap<>();
    private final Map<UUID, PlayerPrefs> playerPrefs = new HashMap<>();
    private final Map<String, Integer> owners = new HashMap<>();

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
        owners.clear();

        ConfigurationSection definitions = config.getConfigurationSection("tags");
        if (definitions != null) {
            for (String id : definitions.getKeys(false)) {
                ConfigurationSection section = definitions.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                tags.put(id.toLowerCase(Locale.ROOT), new Tag(id, section.getString("display", id),
                    section.getString("permission", "hcfcore.tag." + id.toLowerCase(Locale.ROOT)),
                    section.getLong("created-at", System.currentTimeMillis()),
                    section.getString("material", null),
                    section.isInt("custom-model-data") ? section.getInt("custom-model-data") : null,
                    List.copyOf(section.getStringList("lore"))));
                owners.put(id.toLowerCase(Locale.ROOT), section.getInt("owners", 0));
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
            case ALPHABETICAL -> Comparator.comparing(
                    tag -> GradientColor.stripLeadingColor(tag.display()), String.CASE_INSENSITIVE_ORDER);
            case AGE -> Comparator.comparingLong(Tag::createdAt);
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

    public int owners(String id) {
        return owners.getOrDefault(id.toLowerCase(Locale.ROOT), 0);
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
            owners.merge(tag.id().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        save();
    }

    public void unselect(UUID uuid) {
        PlayerPrefs current = playerPrefs.getOrDefault(uuid, PlayerPrefs.DEFAULT);
        if (current.tagId() == null) {
            return;
        }
        playerPrefs.put(uuid, current.withTag(null));
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
     * The color/gradient to render the player's name with in chat, pulled
     * from the leading color tag(s) of their equipped tag's `display` --
     * or null if nickname-match is off, they have no tag equipped, or
     * their tag's display doesn't start with a color.
     */
    public String getNicknameColor(Player player) {
        PlayerPrefs prefs = playerPrefs.get(player.getUniqueId());
        if (prefs == null || !prefs.nicknameMatch() || prefs.tagId() == null) {
            return null;
        }
        Tag tag = get(prefs.tagId());
        String color = tag == null ? null : GradientColor.extractLeadingColor(tag.display());
        if (color == null) {
            return null;
        }
        return prefs.nicknameReversed() ? GradientColor.reverse(color) : color;
    }

    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Tag tag : tags.values()) {
            String path = "tags." + tag.id();
            config.set(path + ".display", tag.display());
            config.set(path + ".permission", tag.permission());
            config.set(path + ".created-at", tag.createdAt());
            config.set(path + ".owners", owners(tag.id()));
            if (tag.material() != null && !tag.material().isBlank()) {
                config.set(path + ".material", tag.material());
            }
            if (tag.customModelData() != null) {
                config.set(path + ".custom-model-data", tag.customModelData());
            }
            if (tag.lore() != null && !tag.lore().isEmpty()) {
                config.set(path + ".lore", tag.lore());
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

    public static String formatCreated(long epochMillis) {
        return CREATED_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC));
    }

    public enum Sort { ALPHABETICAL, AGE }

    public enum Filter { YOUR, UNOWNED, ALL }

    public record Tag(String id, String display, String permission, long createdAt, String material,
                       Integer customModelData, List<String> lore) { }

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
