package me.vertex.core.enchant.binds;

import me.vertex.core.enchant.EnchantDefinition;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneCooldownStore;
import me.vertex.core.enchant.RuneEquipment;
import me.vertex.core.enchant.RuneFormatting;
import me.vertex.core.lang.Messages;
import me.vertex.core.user.User;
import me.vertex.core.user.UserManager;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The temporary BossBar "Bind HUD" shown when a player triggers their
 * activation opener: one bar per non-empty bind key (never one per rune),
 * each rune segment inside a bar colored independently by its own live
 * state, refreshed by one shared repeating task for as long as any player
 * has an active HUD (mirroring {@code me.vertex.core.faction.RallyManager}'s
 * identical single-timer idiom, not one task per player). Also owns
 * {@link BossBarSuppression} for the duration the HUD is visible.
 */
public final class BindHudService {
    private static final long REFRESH_PERIOD_TICKS = 4L;
    /**
     * Hotbar slot 8 (index 7) is never a valid bind target (binds are 1-7,
     * mapping to hotbar slots 0-6) -- parking the player's held slot here
     * while the HUD is open guarantees every subsequent 1-7 key press is a
     * genuine slot <em>change</em> from Bukkit's perspective, since {@link
     * org.bukkit.event.player.PlayerItemHeldEvent} never fires when the
     * pressed number is the slot the player is already on. Without this,
     * a bind whose number happens to match the player's already-held slot
     * at HUD-open time could never be selected by pressing that number.
     */
    private static final int PARKED_HOTBAR_SLOT = 7;

    private record HudState(Map<Integer, BossBar> bars, int heldSlotAtOpen, long expiresAt) {
    }

    private final Plugin plugin;
    private final EnchantManager enchants;
    private final BindManager binds;
    private final RuneCooldownStore cooldowns;
    private final UserManager users;
    private final BindQueue queue;
    private final Messages messages;
    private final Map<UUID, HudState> active = new ConcurrentHashMap<>();
    private volatile long lifetimeMillis = 5_000L;
    private BukkitTask task;

    public BindHudService(Plugin plugin, EnchantManager enchants, BindManager binds, RuneCooldownStore cooldowns,
            UserManager users, BindQueue queue, Messages messages) {
        this.plugin = plugin;
        this.enchants = enchants;
        this.binds = binds;
        this.cooldowns = cooldowns;
        this.users = users;
        this.queue = queue;
        this.messages = messages;
    }

    public void setLifetimeSeconds(double seconds) {
        this.lifetimeMillis = Math.max(500L, Math.round(seconds * 1000D));
    }

    public boolean isActive(UUID uuid) {
        return active.containsKey(uuid);
    }

    public int heldSlotAtOpen(UUID uuid) {
        HudState state = active.get(uuid);
        return state == null ? -1 : state.heldSlotAtOpen();
    }

    /** Re-triggering the opener while the HUD is already up closes it instead of refreshing it -- the same gesture both opens and closes. */
    public void activate(Player player) {
        if (isActive(player.getUniqueId())) {
            hide(player.getUniqueId());
            return;
        }
        PlayerBinds playerBinds = binds.get(player.getUniqueId());
        if (playerBinds == null) {
            return;
        }
        Map<Integer, List<String>> nonEmpty = new TreeMap<>();
        for (Map.Entry<Integer, List<String>> entry : playerBinds.binds().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                nonEmpty.put(entry.getKey(), entry.getValue());
            }
        }
        if (nonEmpty.isEmpty()) {
            player.sendMessage(messages.get(player, "binds.hud-empty"));
            return;
        }
        Map<Integer, BossBar> bars = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<String>> entry : nonEmpty.entrySet()) {
            BossBar bar = BossBar.bossBar(barText(player, entry.getKey(), entry.getValue()), 1F,
                    BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
            bars.put(entry.getKey(), bar);
            player.showBossBar(bar);
        }
        int heldSlotAtOpen = player.getInventory().getHeldItemSlot();
        active.put(player.getUniqueId(), new HudState(bars, heldSlotAtOpen,
                System.currentTimeMillis() + lifetimeMillis));
        if (heldSlotAtOpen != PARKED_HOTBAR_SLOT) {
            player.getInventory().setHeldItemSlot(PARKED_HOTBAR_SLOT);
        }
        BossBarSuppression.suppress(player.getUniqueId());
        ensureTaskRunning();
    }

    public void hide(UUID uuid) {
        hide(uuid, true);
    }

    /**
     * @param restoreHeldSlot false when the caller is deliberately letting
     *                        the player's own in-progress scroll stand (see
     *                        {@code BindActivationListener}'s scroll-spam
     *                        guard) -- forcing their held slot back in that
     *                        case would fight the very input they're
     *                        already mid-way through.
     */
    public void hide(UUID uuid, boolean restoreHeldSlot) {
        HudState state = active.remove(uuid);
        if (state == null) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            for (BossBar bar : state.bars().values()) {
                player.hideBossBar(bar);
            }
            if (restoreHeldSlot && player.getInventory().getHeldItemSlot() == PARKED_HOTBAR_SLOT
                    && state.heldSlotAtOpen() != PARKED_HOTBAR_SLOT) {
                player.getInventory().setHeldItemSlot(state.heldSlotAtOpen());
            }
        }
        BossBarSuppression.release(uuid);
    }

    /** Pressing a bind number: immediately hides every Bind HUD bar and queues that bind. */
    public void selectBind(Player player, int bindIndex) {
        PlayerBinds playerBinds = binds.get(player.getUniqueId());
        HudState state = active.get(player.getUniqueId());
        if (playerBinds == null || state == null || !state.bars().containsKey(bindIndex)) {
            return;
        }
        List<String> runeIds = playerBinds.bind(bindIndex);
        hide(player.getUniqueId());
        if (!queue.enqueue(player, bindIndex, runeIds)) {
            return;
        }
    }

    private void ensureTaskRunning() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, REFRESH_PERIOD_TICKS, REFRESH_PERIOD_TICKS);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID uuid : List.copyOf(active.keySet())) {
            hide(uuid);
        }
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        for (UUID uuid : List.copyOf(active.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            HudState state = active.get(uuid);
            if (player == null || state == null) {
                hide(uuid);
                continue;
            }
            if (now >= state.expiresAt()) {
                hide(uuid);
                continue;
            }
            PlayerBinds playerBinds = binds.get(uuid);
            if (playerBinds == null) {
                continue;
            }
            for (Map.Entry<Integer, BossBar> entry : state.bars().entrySet()) {
                entry.getValue().name(barText(player, entry.getKey(), playerBinds.bind(entry.getKey())));
            }
        }
    }

    private Component barText(Player player, int bindIndex, List<String> runeIds) {
        Component text = Component.text("[" + bindIndex + "] ", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
        User user = users == null ? null : users.get(player.getUniqueId());
        for (int i = 0; i < runeIds.size(); i++) {
            if (i > 0) {
                text = text.append(Component.text(" | ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            text = text.append(runeSegment(player, user, runeIds.get(i)));
        }
        return text;
    }

    private Component runeSegment(Player player, User user, String enchantId) {
        EnchantDefinition definition = enchants.definition(enchantId);
        String name = RuneFormatting.smallCaps(definition == null ? enchantId : definition.displayName());
        int level = RuneEquipment.highestAvailableLevel(player, enchants, enchantId);
        if (level <= 0) {
            return Component.text(name + " (" + messages.getRaw(player, "binds.hud-unavailable") + ")", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false);
        }
        String leveled = name + " " + RuneFormatting.roman(level);
        long remaining = user == null ? 0L : cooldowns.remainingMillis(user, enchantId);
        if (remaining > 0L) {
            String seconds = String.format(java.util.Locale.ROOT, "%.1f", remaining / 1000D);
            return Component.text(leveled + " (" + seconds + "s)", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false);
        }
        return Component.text(leveled, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false);
    }
}
