package me.vertex.core.capture;

import eu.decentsoftware.holograms.api.DHAPI;
import me.vertex.core.ability.AbilityGate;
import me.vertex.core.faction.RallyManager;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.factions.event.FactionLifecycleEvent;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import me.vertex.core.preferences.AnnouncementCategory;
import me.vertex.core.preferences.AnnouncementPreferenceManager;
import me.vertex.core.staff.StaffManager;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Shared faction capture engine for KOTH and Outpost. Definitions are stored
 * in {@code capture-events.yml}; runtime capture state intentionally ends on
 * a reboot, preventing a server restart from quietly awarding an unattended
 * event or leaving an orphaned hologram behind.
 */
public final class CaptureEventManager implements Listener {

    public enum FocusOutcome { FOCUSED, CLEARED, NO_ACTIVE, NOT_FOUND, WRONG_WORLD }

    private static final DateTimeFormatter SCHEDULE_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final Plugin plugin;
    private final Messages messages;
    private final RallyManager rallyManager;
    private final StaffManager staffManager;
    private final AnnouncementPreferenceManager announcements;
    private final File file;
    private final NamespacedKey wandKey;
    private final FactionXpBoosterManager xpBoosters;
    private volatile me.vertex.core.faction.PvpTopManager pvpTopManager;
    private final Map<String, CaptureDefinition> definitions = new HashMap<>();
    private final Map<String, ActiveCapture> active = new HashMap<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, String> focusedEvents = new HashMap<>();
    private final Map<UUID, BossBar> focusBossBars = new HashMap<>();
    private final Map<UUID, Location> originalCompassTargets = new HashMap<>();
    private final Map<String, String> lastScheduledMinute = new HashMap<>();
    private final Set<String> hologramFailures = new HashSet<>();

    private YamlConfiguration config;
    private boolean enabled;
    private int defaultCaptureSeconds;
    private int defaultMaxDurationSeconds;
    private double defaultAdditionalMemberSpeed;
    private long maximumSelectionVolume;
    private boolean hologramsEnabled;
    private List<String> hologramLines = List.of();
    private String neutralDisplayName = "No Faction";
    private ZoneId scheduleZone = ZoneId.systemDefault();
    private BukkitTask tickTask;
    private BukkitTask directionTask;
    private long directionUpdateTicks;

    public CaptureEventManager(Plugin plugin, Messages messages, RallyManager rallyManager, StaffManager staffManager) {
        this(plugin, messages, rallyManager, staffManager, null);
    }

    public CaptureEventManager(Plugin plugin, Messages messages, RallyManager rallyManager, StaffManager staffManager,
            AnnouncementPreferenceManager announcements) {
        this.plugin = plugin;
        this.messages = messages;
        this.rallyManager = rallyManager;
        this.staffManager = staffManager;
        this.announcements = announcements;
        this.file = new File(plugin.getDataFolder(), "capture-events.yml");
        this.wandKey = new NamespacedKey(plugin, "capture_selection_wand");
        this.xpBoosters = new FactionXpBoosterManager(plugin);
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("capture-events.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        enabled = config.getBoolean("enabled", true);
        defaultCaptureSeconds = Math.max(1, config.getInt("defaults.capture-seconds", 300));
        defaultMaxDurationSeconds = Math.max(1, config.getInt("defaults.max-duration-seconds", 45 * 60));
        defaultAdditionalMemberSpeed = Math.max(0D, config.getDouble("defaults.additional-member-speed", 0.25D));
        directionUpdateTicks = Math.max(1L, config.getLong("defaults.direction-update-ticks", 10L));
        maximumSelectionVolume = Math.max(1L, config.getLong("selection.maximum-volume", 1_000_000L));
        hologramsEnabled = config.getBoolean("hologram.enabled", true);
        hologramLines = List.copyOf(config.getStringList("hologram.lines"));
        neutralDisplayName = config.getString("neutral-display-name", "No Faction");
        try {
            scheduleZone = ZoneId.of(config.getString("schedule-time-zone", ZoneId.systemDefault().getId()));
        } catch (DateTimeException ignored) {
            scheduleZone = ZoneId.systemDefault();
            plugin.getLogger().warning("Invalid Capture schedule-time-zone; using " + scheduleZone.getId() + ".");
        }

        definitions.clear();
        ConfigurationSection events = config.getConfigurationSection("events");
        if (events != null) {
            for (String id : events.getKeys(false)) {
                CaptureDefinition definition = CaptureDefinition.from(id, events.getConfigurationSection(id),
                        defaultCaptureSeconds, defaultAdditionalMemberSpeed, defaultMaxDurationSeconds);
                if (definition == null) {
                    plugin.getLogger().warning("Ignoring invalid capture event definition: " + id);
                    continue;
                }
                definitions.put(id.toLowerCase(Locale.ROOT), definition);
            }
        }
        removeStaleConfiguredHolograms();
        xpBoosters.load();
    }

    /** Wired after faction leaderboard state is available. */
    public void setPvpTopManager(me.vertex.core.faction.PvpTopManager pvpTopManager) {
        this.pvpTopManager = pvpTopManager;
    }

    public void start() {
        if (tickTask == null) {
            Bukkit.getPluginManager().registerEvents(xpBoosters, plugin);
            tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        }
        restartDirectionTask();
        rallyManager.setExternalDirectionFocus(this::hasFocus);
        AbilityGate.setCaptureZoneDisabled(this::areAbilitiesDisabledAt);
    }

    public void reload() {
        stopAll(false);
        load();
        if (tickTask != null) {
            restartDirectionTask();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<CaptureDefinition> definitions(CaptureEventType type) {
        return definitions.values().stream().filter(definition -> definition.type() == type)
                .sorted(Comparator.comparing(CaptureDefinition::id, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /**
     * Read-only state used by /events. It is derived from the scheduler's
     * live maps, so the GUI cannot drift into a second definition of whether
     * a KOTH is active, who is taking it, or when the next configured start
     * is due.
     */
    public EventSnapshot snapshot(CaptureEventType type) {
        ActiveCapture capture = active.values().stream()
                .filter(candidate -> candidate.definition.type() == type)
                .min(Comparator.comparing(candidate -> candidate.definition.id(), String.CASE_INSENSITIVE_ORDER))
                .orElse(null);
        long next = secondsUntilNextScheduled(type);
        if (capture == null) {
            return new EventSnapshot(false, "", "", 0L, next);
        }
        String owner = capture.capturingTeamKey == null || capture.capturingName == null
                || capture.capturingName.isBlank() || capture.capturingName.equals("-")
                ? neutralDisplayName : capture.capturingName;
        long remaining = Math.max(0L, capture.definition.maxDurationSeconds()
                - (System.currentTimeMillis() - capture.startedAtMillis) / 1_000L);
        return new EventSnapshot(true, capture.definition.displayName(), owner, remaining, next);
    }

    /** Seconds until the next configured HH:mm start for this event type, or -1 when none is scheduled. */
    public long secondsUntilNextScheduled(CaptureEventType type) {
        ZonedDateTime now = ZonedDateTime.now(scheduleZone);
        long shortest = Long.MAX_VALUE;
        for (CaptureDefinition definition : definitions(type)) {
            for (String configured : definition.scheduleTimes()) {
                try {
                    LocalTime time = LocalTime.parse(configured, SCHEDULE_TIME);
                    ZonedDateTime candidate = now.toLocalDate().atTime(time).atZone(scheduleZone);
                    if (!candidate.isAfter(now)) {
                        candidate = candidate.plusDays(1L);
                    }
                    shortest = Math.min(shortest, Math.max(0L, Duration.between(now, candidate).getSeconds()));
                } catch (DateTimeException ignored) {
                    // load()/validate() already reports malformed schedule entries.
                }
            }
        }
        return shortest == Long.MAX_VALUE ? -1L : shortest;
    }

    public record EventSnapshot(boolean active, String name, String owner, long remainingSeconds,
            long nextScheduledSeconds) { }

    /** Validates raw YAML as well as loaded definitions, so staff can repair a bad file without reading console stack traces. */
    public List<ValidationIssue> validate(CaptureEventType requestedType) {
        List<ValidationIssue> issues = new ArrayList<>();
        ConfigurationSection events = config == null ? null : config.getConfigurationSection("events");
        if (events == null) {
            issues.add(new ValidationIssue("-", "events", "capture.validate-missing", ""));
            return issues;
        }
        for (String id : events.getKeys(false)) {
            ConfigurationSection section = events.getConfigurationSection(id);
            String root = "events." + id;
            if (section == null) {
                issues.add(new ValidationIssue(id, root, "capture.validate-invalid-section", ""));
                continue;
            }
            CaptureEventType configuredType = CaptureEventType.from(section.getString("type"));
            if (configuredType == null) {
                issues.add(new ValidationIssue(id, root + ".type", "capture.validate-invalid-type", ""));
                continue;
            }
            if (configuredType != requestedType) {
                continue;
            }
            if (!id.equals(normalizeId(id))) {
                issues.add(new ValidationIssue(id, root, "capture.validate-invalid-id", ""));
            }
            String worldName = section.getString("world");
            World world = worldName == null || worldName.isBlank() ? null : Bukkit.getWorld(worldName);
            if (world == null) {
                issues.add(new ValidationIssue(id, root + ".world", "capture.validate-unknown-world",
                        worldName == null ? "" : worldName));
            }
            validateCoordinateSection(issues, id, section, root + ".minimum", "minimum");
            validateCoordinateSection(issues, id, section, root + ".maximum", "maximum");
            ConfigurationSection minimum = section.getConfigurationSection("minimum");
            ConfigurationSection maximum = section.getConfigurationSection("maximum");
            if (minimum != null && maximum != null && areCoordinatesNumeric(minimum) && areCoordinatesNumeric(maximum)) {
                long volume = ((long) Math.abs(maximum.getInt("x") - minimum.getInt("x")) + 1L)
                        * ((long) Math.abs(maximum.getInt("y") - minimum.getInt("y")) + 1L)
                        * ((long) Math.abs(maximum.getInt("z") - minimum.getInt("z")) + 1L);
                if (volume > maximumSelectionVolume) {
                    issues.add(new ValidationIssue(id, root, "capture.validate-volume", String.format("%,d", volume)));
                }
            }
            validatePositive(issues, id, section, root + ".capture-seconds", "capture-seconds");
            validatePositive(issues, id, section, root + ".max-duration-seconds", "max-duration-seconds");
            if (section.contains("additional-member-speed") && section.getDouble("additional-member-speed") < 0D) {
                issues.add(new ValidationIssue(id, root + ".additional-member-speed", "capture.validate-non-negative", ""));
            }
            if (section.contains("schedule-times") && !section.isList("schedule-times")) {
                issues.add(new ValidationIssue(id, root + ".schedule-times", "capture.validate-list", ""));
            } else {
                for (String time : section.getStringList("schedule-times")) {
                    if (!time.matches("[0-2][0-9]:[0-5][0-9]") || !isValidScheduleTime(time)) {
                        issues.add(new ValidationIssue(id, root + ".schedule-times", "capture.validate-time", time));
                    }
                }
            }
            if (configuredType == CaptureEventType.OUTPOST && section.getBoolean("rewards.xp-booster.enabled", false)) {
                if (section.getDouble("rewards.xp-booster.multiplier", 1D) <= 1D) {
                    issues.add(new ValidationIssue(id, root + ".rewards.xp-booster.multiplier",
                            "capture.validate-booster-multiplier", ""));
                }
                if (section.getLong("rewards.xp-booster.duration-seconds", 0L) <= 0L) {
                    issues.add(new ValidationIssue(id, root + ".rewards.xp-booster.duration-seconds",
                            "capture.validate-positive", ""));
                }
            }
        }
        return issues;
    }

    private static void validateCoordinateSection(List<ValidationIssue> issues, String id, ConfigurationSection parent,
            String path, String key) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) {
            issues.add(new ValidationIssue(id, path, "capture.validate-missing", ""));
            return;
        }
        for (String coordinate : List.of("x", "y", "z")) {
            if (!(section.get(coordinate) instanceof Number)) {
                issues.add(new ValidationIssue(id, path + "." + coordinate, "capture.validate-number", ""));
            }
        }
    }

    private static boolean areCoordinatesNumeric(ConfigurationSection section) {
        return section.get("x") instanceof Number && section.get("y") instanceof Number && section.get("z") instanceof Number;
    }

    private static void validatePositive(List<ValidationIssue> issues, String id, ConfigurationSection section,
            String path, String key) {
        if (!section.contains(key)) {
            return; // Per-event value may intentionally inherit the documented default.
        }
        if (!(section.get(key) instanceof Number) || section.getLong(key) <= 0L) {
            issues.add(new ValidationIssue(id, path, "capture.validate-positive", ""));
        }
    }

    private static boolean isValidScheduleTime(String time) {
        try {
            LocalTime.parse(time, SCHEDULE_TIME);
            return true;
        } catch (DateTimeException ignored) {
            return false;
        }
    }

    public boolean beginSelection(Player player, CaptureEventType type, String eventId) {
        String normalized = normalizeId(eventId);
        if (normalized == null) {
            player.sendMessage(messages.get(player, "capture.invalid-name"));
            return false;
        }
        if (definitions.containsKey(normalized)) {
            player.sendMessage(messages.get(player, "capture.already-exists", "name", normalized));
            return false;
        }
        Selection selection = selections.computeIfAbsent(player.getUniqueId(), ignored -> new Selection());
        selection.first = null;
        selection.second = null;
        selection.type = type;
        selection.eventId = normalized;
        giveWand(player, type);
        player.sendMessage(messages.get(player, "capture.selection-started", "name", normalized,
                "type", type.display()));
        return true;
    }

    public void giveWand(Player player, CaptureEventType type) {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(messages.getGui(player, "capture.wand-name"));
        meta.lore(messages.getGuiList(player, "capture.wand-lore"));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.STRING, type.id());
        wand.setItemMeta(meta);
        if (!me.vertex.core.storage.DeliveryManager.queueOverflow(
                plugin, player, List.of(wand), "capture-selector")) {
            player.sendMessage(messages.get(player, "delivery.storage-unavailable"));
        }
    }

    public boolean cancelSelection(Player player) {
        return selections.remove(player.getUniqueId()) != null;
    }

    public boolean start(CaptureEventType type, String eventId) {
        if (!enabled) {
            return false;
        }
        CaptureDefinition definition = definition(type, eventId);
        if (definition == null || active.containsKey(definition.id()) || definition.center() == null
                || active.values().stream().anyMatch(existing -> existing.definition.type() == type)) {
            return false;
        }
        ActiveCapture capture = new ActiveCapture(definition);
        active.put(definition.id(), capture);
        updateHologram(capture);
        broadcast(definition, "started", "name", definition.displayName());
        return true;
    }

    public boolean stop(CaptureEventType type, String eventId, boolean announce) {
        CaptureDefinition definition = definition(type, eventId);
        if (definition == null) {
            return false;
        }
        ActiveCapture capture = active.remove(definition.id());
        if (capture == null) {
            return false;
        }
        removeHologram(capture);
        clearFocusFor(definition.id());
        if (announce) {
            broadcast(definition, "stopped", "name", definition.displayName());
        }
        return true;
    }

    public boolean delete(CaptureEventType type, String eventId) {
        CaptureDefinition definition = definition(type, eventId);
        if (definition == null) {
            return false;
        }
        stop(type, eventId, false);
        definitions.remove(definition.id());
        config.set("events." + definition.id(), null);
        return saveDefinitions();
    }

    public FocusOutcome toggleFocus(Player player, CaptureEventType type, String requestedEventId) {
        String currentId = focusedEvents.get(player.getUniqueId());
        ActiveCapture nearest = requestedEventId == null || requestedEventId.isBlank()
                ? nearestActive(player, type) : null;
        CaptureDefinition requested = nearest == null && requestedEventId != null && !requestedEventId.isBlank()
                ? definition(type, requestedEventId) : nearest == null ? null : nearest.definition;
        if (requested == null) {
            return requestedEventId == null || requestedEventId.isBlank() ? FocusOutcome.NO_ACTIVE : FocusOutcome.NOT_FOUND;
        }
        if (!active.containsKey(requested.id())) {
            return FocusOutcome.NOT_FOUND;
        }
        if (requested.center() == null || !player.getWorld().equals(requested.center().getWorld())) {
            return FocusOutcome.WRONG_WORLD;
        }
        if (Objects.equals(currentId, requested.id())) {
            focusedEvents.remove(player.getUniqueId());
            hideFocusBossBar(player);
            return FocusOutcome.CLEARED;
        }
        focusedEvents.put(player.getUniqueId(), requested.id());
        return FocusOutcome.FOCUSED;
    }

    public boolean clearFocus(Player player) {
        boolean hadFocus = focusedEvents.remove(player.getUniqueId()) != null;
        hideFocusBossBar(player);
        return hadFocus;
    }

    public boolean isActive(CaptureEventType type, String eventId) {
        CaptureDefinition definition = definition(type, eventId);
        return definition != null && active.containsKey(definition.id());
    }

    /** Used by RallyManager to yield the personal bossbar to an event focus. */
    public boolean hasFocus(Player player) {
        if (player == null) {
            return false;
        }
        ActiveCapture capture = active.get(focusedEvents.get(player.getUniqueId()));
        return capture != null && capture.definition.center() != null
                && player.getWorld().equals(capture.definition.center().getWorld());
    }

    /** True only for an active event that explicitly disables Vertex ability items in its cuboid. */
    public boolean areAbilitiesDisabledAt(Location location) {
        return location != null && active.values().stream().anyMatch(capture -> capture.definition.abilitiesDisabled()
                && capture.definition.contains(location));
    }

    /** Public integration seam for the forthcoming shop: active boosts multiply rather than replace each other. */
    public void grantFactionXpBooster(int factionId, double multiplier, long durationSeconds) {
        xpBoosters.grant(factionId, multiplier, durationSeconds);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onWandInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isSelectionWand(event.getItem())) {
            return;
        }
        CaptureEventType wandType = CaptureEventType.from(event.getItem().getItemMeta()
                .getPersistentDataContainer().get(wandKey, PersistentDataType.STRING));
        if (wandType == null) {
            return;
        }
        Player player = event.getPlayer();
        Selection selection = selections.computeIfAbsent(player.getUniqueId(), ignored -> new Selection());
        if (selection.type == null) {
            selection.type = wandType;
        }

        switch (event.getAction()) {
            case LEFT_CLICK_BLOCK -> {
                event.setCancelled(true);
                selection.first = event.getClickedBlock().getLocation();
                player.sendMessage(messages.get(player, "capture.selection-first",
                        "x", String.valueOf(selection.first.getBlockX()), "y", String.valueOf(selection.first.getBlockY()),
                        "z", String.valueOf(selection.first.getBlockZ())));
            }
            case RIGHT_CLICK_BLOCK -> {
                event.setCancelled(true);
                selection.second = event.getClickedBlock().getLocation();
                player.sendMessage(messages.get(player, "capture.selection-second",
                        "x", String.valueOf(selection.second.getBlockX()), "y", String.valueOf(selection.second.getBlockY()),
                        "z", String.valueOf(selection.second.getBlockZ())));
            }
            case LEFT_CLICK_AIR, RIGHT_CLICK_AIR -> {
                if (player.isSneaking()) {
                    event.setCancelled(true);
                    completeSelection(player, selection);
                }
            }
            default -> {
                // No selection action for physical interactions other than the two corners.
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
        focusedEvents.remove(event.getPlayer().getUniqueId());
        hideFocusBossBar(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // Keep the selection for a return to its world, but never render a
        // cross-world compass/bossbar in the meantime.
        hideFocusBossBar(event.getPlayer());
    }

    @EventHandler
    public void onFactionDisband(FactionLifecycleEvent event) {
        if (event.action() == FactionLifecycleEvent.Action.DISBAND) xpBoosters.revoke(event.faction().id());
    }

    public void shutdown() {
        if (directionTask != null) {
            directionTask.cancel();
            directionTask = null;
        }
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        stopAll(false);
        selections.clear();
        xpBoosters.shutdown();
        AbilityGate.setCaptureZoneDisabled(null);
        rallyManager.setExternalDirectionFocus(null);
    }

    /** Keeps direction rendering responsive without running capture scoring every movement tick. */
    private void restartDirectionTask() {
        if (directionTask != null) {
            directionTask.cancel();
        }
        directionTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateDirections, 1L, directionUpdateTicks);
    }

    private void completeSelection(Player player, Selection selection) {
        if (selection.type == null || selection.eventId == null || selection.first == null || selection.second == null) {
            player.sendMessage(messages.get(player, "capture.selection-incomplete"));
            return;
        }
        if (!selection.first.getWorld().equals(selection.second.getWorld())) {
            player.sendMessage(messages.get(player, "capture.selection-world-mismatch"));
            return;
        }
        int minX = Math.min(selection.first.getBlockX(), selection.second.getBlockX());
        int minY = Math.min(selection.first.getBlockY(), selection.second.getBlockY());
        int minZ = Math.min(selection.first.getBlockZ(), selection.second.getBlockZ());
        int maxX = Math.max(selection.first.getBlockX(), selection.second.getBlockX());
        int maxY = Math.max(selection.first.getBlockY(), selection.second.getBlockY());
        int maxZ = Math.max(selection.first.getBlockZ(), selection.second.getBlockZ());
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > maximumSelectionVolume) {
            player.sendMessage(messages.get(player, "capture.selection-too-large", "maximum", String.format("%,d", maximumSelectionVolume)));
            return;
        }
        String path = "events." + selection.eventId;
        config.set(path + ".type", selection.type.id());
        config.set(path + ".display-name", selection.eventId);
        config.set(path + ".world", selection.first.getWorld().getName());
        setLocation(path + ".minimum", minX, minY, minZ);
        setLocation(path + ".maximum", maxX, maxY, maxZ);
        config.set(path + ".capture-seconds", defaultCaptureSeconds);
        config.set(path + ".max-duration-seconds", defaultMaxDurationSeconds);
        config.set(path + ".additional-member-speed", defaultAdditionalMemberSpeed);
        config.set(path + ".disable-abilities", false);
        config.set(path + ".schedule-times", List.of());
        config.set(path + ".rewards.commands", List.of());
        config.set(path + ".rewards.xp-booster.enabled", false);
        config.set(path + ".rewards.xp-booster.multiplier", 2D);
        config.set(path + ".rewards.xp-booster.duration-seconds", 3600L);
        if (!saveDefinitions()) {
            player.sendMessage(messages.get(player, "capture.save-failed"));
            return;
        }
        CaptureDefinition definition = CaptureDefinition.from(selection.eventId,
                config.getConfigurationSection(path), defaultCaptureSeconds, defaultAdditionalMemberSpeed,
                defaultMaxDurationSeconds);
        if (definition == null) {
            player.sendMessage(messages.get(player, "capture.save-failed"));
            return;
        }
        definitions.put(definition.id(), definition);
        selections.remove(player.getUniqueId());
        player.sendMessage(messages.get(player, "capture.created", "type", definition.type().display(),
                "name", definition.displayName()));
    }

    private void setLocation(String path, double x, double y, double z) {
        config.set(path + ".x", x);
        config.set(path + ".y", y);
        config.set(path + ".z", z);
    }

    private boolean isSelectionWand(ItemStack item) {
        return item != null && item.getType() == Material.BLAZE_ROD && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.STRING);
    }

    private void tick() {
        if (!enabled) {
            hideAllFocusBars();
            return;
        }
        startScheduledEvents();
        List<Completion> completions = new ArrayList<>();
        List<ActiveCapture> timedOut = new ArrayList<>();
        for (ActiveCapture capture : List.copyOf(active.values())) {
            if (capture.hasTimedOut()) {
                timedOut.add(capture);
                continue;
            }
            Completion completion = advance(capture);
            updateHologram(capture);
            if (completion != null) {
                completions.add(completion);
            }
        }
        for (ActiveCapture capture : timedOut) {
            timeout(capture);
        }
        for (Completion completion : completions) {
            complete(completion);
        }
        xpBoosters.cleanupExpired();
    }

    private void startScheduledEvents() {
        LocalTime now = LocalTime.now(scheduleZone).withSecond(0).withNano(0);
        String formattedTime = SCHEDULE_TIME.format(now);
        String minute = LocalDate.now(scheduleZone) + "T" + formattedTime;
        for (CaptureDefinition definition : definitions.values()) {
            if (!definition.scheduleTimes().contains(formattedTime)
                    || minute.equals(lastScheduledMinute.get(definition.id()))) {
                continue;
            }
            lastScheduledMinute.put(definition.id(), minute);
            if (start(definition.type(), definition.id())) {
                plugin.getLogger().info("Started scheduled " + definition.type().display() + " " + definition.id() + ".");
            }
        }
    }

    private Completion advance(ActiveCapture capture) {
        Map<String, CaptureTeam> teams = contestants(capture.definition);
        if (teams.size() != 1) {
            capture.lastUncontestedTeamKey = null;
            if (teams.isEmpty()) {
                // A faction has to keep a body in the zone. Leaving resets
                // control completely; an arriving faction starts fresh.
                capture.capturingFactionId = FactionsHook.NO_FACTION;
                capture.capturingTeamKey = null;
                capture.capturingName = "-";
                capture.memberCount = 0;
                capture.progressPercent = 0D;
            }
            return null; // two or more factions means the current progress freezes.
        }

        CaptureTeam team = teams.values().iterator().next();
        if (!team.key().equals(capture.capturingTeamKey)) {
            capture.capturingTeamKey = team.key();
            capture.capturingFactionId = team.factionId();
            capture.capturingName = team.displayName();
            capture.progressPercent = 0D;
        }
        capture.memberCount = team.members().size();
        recordContributions(capture, team);
        Player representative = winningPlayer(capture, team);
        if (!team.key().equals(capture.lastUncontestedTeamKey)) {
            capture.lastUncontestedTeamKey = team.key();
            broadcast(capture.definition, "uncontested", "name", capture.definition.displayName(),
                    "player", representative.getName(), "faction", team.displayName());
        }

        capture.progressPercent = Math.min(100D, capture.progressPercent + CaptureProgression.progressPerSecond(
                capture.definition.captureSeconds(), team.members().size(), capture.definition.additionalMemberSpeed()));
        if (capture.progressPercent < 100D) {
            return null;
        }
        return new Completion(capture, representative, team.factionId(), team.displayName());
    }

    private Map<String, CaptureTeam> contestants(CaptureDefinition definition) {
        World world = Bukkit.getWorld(definition.worldName());
        if (world == null) {
            return Map.of();
        }
        Map<String, List<Player>> members = new HashMap<>();
        Map<String, Integer> factionIds = new HashMap<>();
        for (Player player : world.getPlayers()) {
            if (!isEligibleContestant(player, definition)) {
                continue;
            }
            int factionId = FactionsHook.getFactionId(player);
            // KOTH ownership and its PvP rules are faction objectives. A
            // factionless player cannot make solo progress there, whereas an
            // Outpost may still use the legacy neutral-contestant behavior.
            if (definition.type() == CaptureEventType.KOTH && factionId == FactionsHook.NO_FACTION) {
                continue;
            }
            String key = factionId == FactionsHook.NO_FACTION ? "player:" + player.getUniqueId() : "faction:" + factionId;
            members.computeIfAbsent(key, ignored -> new ArrayList<>()).add(player);
            factionIds.put(key, factionId);
        }
        Map<String, CaptureTeam> teams = new HashMap<>();
        for (Map.Entry<String, List<Player>> entry : members.entrySet()) {
            int factionId = factionIds.get(entry.getKey());
            teams.put(entry.getKey(), new CaptureTeam(entry.getKey(), factionId,
                    factionId == FactionsHook.NO_FACTION ? neutralDisplayName : FactionsHook.getFactionName(factionId),
                    entry.getValue()));
        }
        return teams;
    }

    private boolean isEligibleContestant(Player player, CaptureDefinition definition) {
        return player.isOnline() && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                && !staffManager.isVanished(player.getUniqueId()) && definition.contains(player.getLocation());
    }

    /**
     * Factionless players cannot use an active KOTH as a no-risk PvP area.
     * Cancel the hit when either participant lacks a faction and either side
     * is inside the event cuboid; projectile hits resolve to their shooter.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onKothDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = playerDamager(event);
        if (attacker == null || !insideActiveKoth(attacker.getLocation()) && !insideActiveKoth(victim.getLocation())) {
            return;
        }
        if (FactionsHook.getFactionId(attacker) == FactionsHook.NO_FACTION
                || FactionsHook.getFactionId(victim) == FactionsHook.NO_FACTION) {
            event.setCancelled(true);
        }
    }

    private Player playerDamager(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private boolean insideActiveKoth(Location location) {
        return active.values().stream().anyMatch(capture -> capture.definition.type() == CaptureEventType.KOTH
                && capture.definition.contains(location));
    }

    private void recordContributions(ActiveCapture capture, CaptureTeam team) {
        capture.contributionTick++;
        for (Player member : team.members()) {
            Contribution contribution = capture.contributions.get(member.getUniqueId());
            if (contribution == null || !team.key().equals(contribution.teamKey)) {
                contribution = new Contribution(team.key(), capture.contributionTick);
                capture.contributions.put(member.getUniqueId(), contribution);
            }
            contribution.seconds++;
        }
    }

    private Player winningPlayer(ActiveCapture capture, CaptureTeam team) {
        return team.members().stream().min(Comparator
                .<Player>comparingLong(player -> -capture.contributions
                        .getOrDefault(player.getUniqueId(), Contribution.EMPTY).seconds)
                .thenComparingLong(player -> capture.contributions
                        .getOrDefault(player.getUniqueId(), Contribution.EMPTY).firstContributionTick))
                .orElse(team.members().getFirst());
    }

    private void complete(Completion completion) {
        ActiveCapture capture = completion.capture;
        if (active.remove(capture.definition.id()) != capture) {
            return;
        }
        CaptureDefinition definition = capture.definition;
        String faction = completion.factionName;
        long contributedSeconds = capture.contributions
                .getOrDefault(completion.player.getUniqueId(), Contribution.EMPTY).seconds;
        plugin.getLogger().info(definition.type().display() + " " + definition.id() + " claimed by "
                + completion.player.getName() + " (" + faction + ") after " + contributedSeconds
                + " uncontested contribution second(s).");
        for (String configured : definition.rewardCommands()) {
            String command = configured.replace("{player}", completion.player.getName())
                    .replace("{faction}", faction).replace("{faction_id}", String.valueOf(completion.factionId))
                    .replace("{event}", definition.id()).replace("{name}", definition.displayName())
                    .replace("{type}", definition.type().id());
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            if (!command.isBlank()) {
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                    plugin.getLogger().warning("Capture reward command reported failure for " + definition.id() + ": " + command);
                }
            }
        }
        if (definition.type() == CaptureEventType.OUTPOST) {
            xpBoosters.grant(completion.factionId, definition.outpostXpMultiplier(), definition.outpostXpDurationSeconds());
        }
        me.vertex.core.faction.PvpTopManager pvpTop = pvpTopManager;
        if (pvpTop != null) {
            pvpTop.awardCapture(definition.type(), completion.factionId, completion.player,
                    definition.id(), capture.operationId);
        }
        removeHologram(capture);
        clearFocusFor(definition.id());
        broadcast(definition, "claimed", "name", definition.displayName(), "player", completion.player.getName(),
                "faction", faction, "progress", "100");
    }

    private void timeout(ActiveCapture capture) {
        if (active.remove(capture.definition.id()) != capture) {
            return;
        }
        removeHologram(capture);
        clearFocusFor(capture.definition.id());
        broadcast(capture.definition, "timed-out", "name", capture.definition.displayName());
    }

    private void updateDirections() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ActiveCapture focused = focusedCapture(player);
            if (focused != null) {
                updateDirectionBossBar(player, focused, true);
                continue;
            }
            // A cleared /koth focus must stay clear. Showing the nearest
            // active event here was what made /koth focus off disappear for
            // one tick and then immediately return.
            hideFocusBossBar(player);
        }
    }

    private void updateDirectionBossBar(Player player, ActiveCapture capture, boolean focused) {
        Direction direction = direction(player, capture.definition.center());
        if (direction == null) {
            hideFocusBossBar(player);
            return;
        }
        long seconds = capture.memberCount > 0 && capture.capturingTeamKey != null
                ? CaptureProgression.remainingSeconds(capture.progressPercent, capture.definition.captureSeconds(),
                        capture.memberCount, capture.definition.additionalMemberSpeed()) : 0L;
        BossBar bar = focusBossBars.get(player.getUniqueId());
        net.kyori.adventure.text.Component title = messages.get(player, "capture.direction-bossbar",
                "type", capture.definition.type().display(), "name", capture.definition.displayName(),
                "arrow", direction.arrow(), "distance", String.format("%,d", direction.distance()),
                "progress", String.format("%.0f", capture.progressPercent), "seconds", String.valueOf(seconds));
        float progress = (float) Math.max(0D, Math.min(1D, capture.progressPercent / 100D));
        if (bar == null) {
            bar = BossBar.bossBar(title, progress,
                    capture.definition.type() == CaptureEventType.KOTH ? BossBar.Color.RED : BossBar.Color.YELLOW,
                    BossBar.Overlay.PROGRESS);
            focusBossBars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(progress);
            bar.color(capture.definition.type() == CaptureEventType.KOTH ? BossBar.Color.RED : BossBar.Color.YELLOW);
        }
        if (focused) {
            originalCompassTargets.putIfAbsent(player.getUniqueId(), player.getCompassTarget());
            Location target = capture.definition.center();
            if (target != null) {
                player.setCompassTarget(target);
            }
        }
    }

    private ActiveCapture focusedCapture(Player player) {
        String id = focusedEvents.get(player.getUniqueId());
        ActiveCapture capture = id == null ? null : active.get(id);
        if (capture == null || capture.definition.center() == null) {
            focusedEvents.remove(player.getUniqueId());
            if (id != null) {
                hideFocusBossBar(player);
            }
            return null;
        }
        if (!player.getWorld().equals(capture.definition.center().getWorld())) {
            hideFocusBossBar(player);
            return null;
        }
        return capture;
    }

    private ActiveCapture nearestActive(Player player, CaptureEventType type) {
        return active.values().stream()
                .filter(capture -> type == null || capture.definition.type() == type)
                .filter(capture -> capture.definition.center() != null && player.getWorld().equals(capture.definition.center().getWorld()))
                .min(Comparator.comparingInt((ActiveCapture capture) -> capture.definition.type() == CaptureEventType.KOTH ? 0 : 1)
                        .thenComparingDouble(capture -> capture.definition.center().distanceSquared(player.getLocation())))
                .orElse(null);
    }

    /** Package-visible for the focus command's wrong-world explanation. */
    CaptureDefinition definition(CaptureEventType type, String eventId) {
        String normalized = normalizeId(eventId);
        CaptureDefinition definition = normalized == null ? null : definitions.get(normalized);
        return definition != null && definition.type() == type ? definition : null;
    }

    private void updateHologram(ActiveCapture capture) {
        if (!hologramsEnabled || !hologramsAvailable()) {
            return;
        }
        Location location = capture.definition.hologramLocation();
        if (location == null) {
            return;
        }
        String faction = capture.capturingTeamKey == null ? "-" : capture.capturingName;
        long seconds = capture.memberCount > 0 && capture.capturingTeamKey != null
                ? CaptureProgression.remainingSeconds(capture.progressPercent, capture.definition.captureSeconds(),
                        capture.memberCount, capture.definition.additionalMemberSpeed()) : 0L;
        long eventRemaining = Math.max(0L, capture.definition.maxDurationSeconds()
                - ((System.currentTimeMillis() - capture.startedAtMillis) / 1000L));
        List<String> lines = hologramLines.stream().map(line -> MessageFormatter.legacyAmpersand(line
                .replace("{type}", capture.definition.type().display()).replace("{name}", capture.definition.displayName())
                .replace("{progress}", String.format("%.0f", capture.progressPercent))
                .replace("{remaining}", String.valueOf(seconds)).replace("{event-remaining}", String.valueOf(eventRemaining))
                .replace("{faction}", faction)))
                .toList();
        try {
            eu.decentsoftware.holograms.api.holograms.Hologram hologram = DHAPI.getHologram(capture.hologramName());
            if (hologram == null) {
                DHAPI.createHologram(capture.hologramName(), location, true, lines);
            } else {
                DHAPI.setHologramLines(hologram, lines);
            }
            // A hologram that recovers should be able to warn again if it later breaks again.
            hologramFailures.remove(capture.hologramName());
        } catch (Throwable e) {
            // Every active capture retries every tick regardless of a
            // past failure -- a transient error (DecentHolograms still
            // starting up, a momentarily unloaded chunk) self-heals on
            // its own instead of leaving the hologram silently broken
            // for the rest of that capture's run. Throwable (not just
            // Exception) is caught deliberately: an incompatible
            // DecentHolograms API upgrade throws a LinkageError/
            // NoSuchMethodError, which would otherwise escape this
            // per-capture try and abort the whole shared tick() pass.
            // Set.add(...) is only true the first time, so this warns
            // once per failure streak instead of once per second.
            if (hologramFailures.add(capture.hologramName())) {
                plugin.getLogger().log(Level.WARNING, "Failed to update capture hologram "
                        + capture.hologramName() + " -- will keep retrying silently.", e);
            }
        }
    }

    private void removeHologram(ActiveCapture capture) {
        hologramFailures.remove(capture.hologramName());
        if (!hologramsAvailable()) {
            return;
        }
        try {
            DHAPI.removeHologram(capture.hologramName());
        } catch (Exception ignored) {
            // The hologram may already have been removed manually; it is no longer active either way.
        }
    }

    /** On an unclean stop no runtime record survives, so delete the names Vertex owns before accepting new starts. */
    private void removeStaleConfiguredHolograms() {
        if (!hologramsAvailable()) {
            return;
        }
        for (CaptureDefinition definition : definitions.values()) {
            try {
                DHAPI.removeHologram("vertex_capture_" + definition.type().id() + "_" + definition.id());
            } catch (Exception ignored) {
                // A missing hologram is expected; DecentHolograms is the source of truth for its own registry.
            }
        }
    }

    private boolean hologramsAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("DecentHolograms");
    }

    private void broadcast(CaptureDefinition definition, String action, String... placeholders) {
        String key = "capture." + definition.type().id() + "." + action;
        if (announcements != null) {
            announcements.broadcast(definition.type() == CaptureEventType.KOTH
                    ? AnnouncementCategory.KOTH : AnnouncementCategory.OUTPOST, key, placeholders);
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(messages.get(player, key, placeholders));
        }
    }

    private void clearFocusFor(String eventId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (eventId.equals(focusedEvents.get(player.getUniqueId()))) {
                focusedEvents.remove(player.getUniqueId());
                hideFocusBossBar(player);
            }
        }
    }

    private void hideFocusBossBar(Player player) {
        BossBar bar = focusBossBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
        Location original = originalCompassTargets.remove(player.getUniqueId());
        if (original != null) {
            player.setCompassTarget(original);
        }
    }

    private void hideAllFocusBars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            hideFocusBossBar(player);
        }
    }

    private void stopAll(boolean announce) {
        for (ActiveCapture capture : List.copyOf(active.values())) {
            active.remove(capture.definition.id());
            removeHologram(capture);
            if (announce) {
                broadcast(capture.definition, "stopped", "name", capture.definition.displayName());
            }
        }
        focusedEvents.clear();
        hideAllFocusBars();
    }

    private boolean saveDefinitions() {
        try {
            config.save(file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save capture-events.yml", e);
            return false;
        }
    }

    private static Direction direction(Player player, Location target) {
        if (target == null || !player.getWorld().equals(target.getWorld())) {
            return null;
        }
        Location from = player.getLocation();
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();
        int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        float targetBearing = (float) Math.toDegrees(Math.atan2(dx, -dz));
        // Bukkit yaw is 0=south and grows counter-clockwise, while the
        // bearing above is 0=north and grows clockwise. Convert it before
        // taking the difference so ↑ always means straight ahead of the
        // moving player, not permanently north on their screen.
        float playerBearing = player.getLocation().getYaw() + 180F;
        return new Direction(arrow(targetBearing - playerBearing), distance);
    }

    private static String arrow(float bearing) {
        float normalized = bearing % 360F;
        while (normalized > 180F) normalized -= 360F;
        while (normalized < -180F) normalized += 360F;
        if (normalized >= -22.5F && normalized < 22.5F) return "↑";
        if (normalized < 67.5F) return "↗";
        if (normalized < 112.5F) return "→";
        if (normalized < 157.5F) return "↘";
        if (normalized >= 157.5F || normalized < -157.5F) return "↓";
        if (normalized < -112.5F) return "↙";
        if (normalized < -67.5F) return "←";
        return "↖";
    }

    private static String normalizeId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_-]{1,32}") ? normalized : null;
    }

    private static final class Selection {
        private CaptureEventType type;
        private String eventId;
        private Location first;
        private Location second;
    }

    private static final class ActiveCapture {
        private final CaptureDefinition definition;
        private final String operationId = UUID.randomUUID().toString();
        private final long startedAtMillis = System.currentTimeMillis();
        private final Map<UUID, Contribution> contributions = new HashMap<>();
        private int capturingFactionId = FactionsHook.NO_FACTION;
        private String capturingTeamKey;
        private String lastUncontestedTeamKey;
        private String capturingName = "-";
        private int memberCount;
        private long contributionTick;
        private double progressPercent;

        private ActiveCapture(CaptureDefinition definition) {
            this.definition = definition;
        }

        private String hologramName() {
            return "vertex_capture_" + definition.type().id() + "_" + definition.id();
        }

        private boolean hasTimedOut() {
            return System.currentTimeMillis() - startedAtMillis >= definition.maxDurationSeconds() * 1000L;
        }
    }

    private record CaptureTeam(String key, int factionId, String displayName, List<Player> members) {
    }

    private static final class Contribution {
        private static final Contribution EMPTY = new Contribution("", Long.MAX_VALUE);
        private final String teamKey;
        private final long firstContributionTick;
        private long seconds;

        private Contribution(String teamKey, long firstContributionTick) {
            this.teamKey = teamKey;
            this.firstContributionTick = firstContributionTick;
        }
    }

    private record Completion(ActiveCapture capture, Player player, int factionId, String factionName) {
    }

    private record Direction(String arrow, int distance) {
    }

    public record ValidationIssue(String eventId, String path, String reasonKey, String value) {
    }
}
