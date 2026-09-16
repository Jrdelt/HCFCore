package me.vertex.core.enchant.binds;

import me.vertex.core.enchant.EnchantDefinition;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneCooldownStore;
import me.vertex.core.enchant.RuneEquipment;
import me.vertex.core.enchant.RuneFormatting;
import me.vertex.core.enchant.RuneProtection;
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
import java.util.Set;
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
     * Hotbar slot 1 (index 0) -- parking the player's held slot here while
     * the HUD is open guarantees every bind number 2-7's key press is a
     * genuine slot <em>change</em> from Bukkit's perspective, since {@link
     * org.bukkit.event.player.PlayerItemHeldEvent} never fires when the
     * pressed number is the slot the player is already on. Bind 1 itself
     * can never be detected that way while parked here, so {@code
     * BindActivationListener#onInteract} offers a plain (non-sneaking)
     * right-click as its alternate trigger instead.
     */
    private static final int PARKED_HOTBAR_SLOT = 0;

    /**
     * Effects that need the caster looking directly at a specific player to
     * activate (Huntmaster's Call today) -- while the Bind HUD is open and
     * one of the visible binds carries one of these, {@link #refresh} shows
     * a live "Target: <name>" action bar so the player can see who they're
     * about to hit before committing. Never locks onto the caster's own
     * faction or an ally: it ray-traces through the exact same {@link
     * RuneProtection#rayTraceHostilePlayer} the actual activation uses, so
     * the readout can never promise a lock the real activation wouldn't honor.
     */
    private static final Set<String> TARGET_LOCK_EFFECTS = Set.of("HUNTMASTERS_CALL");

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
    /**
     * Caster UUID -> the lock currently held on their behalf, so {@link
     * #updateTargetLockActionBar} can release the previous lock the instant
     * it moves to someone else (or nobody) instead of leaving a stray
     * outline on a player who's no longer being targeted.
     */
    private final Map<UUID, GlowLock> glowingTargets = new ConcurrentHashMap<>();

    /** @param targetWasAlreadyGlowing whether the target was glowing before this lock touched them -- if so, releasing the lock must never turn it back off. */
    private record GlowLock(UUID targetUuid, boolean targetWasAlreadyGlowing) {
    }
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
            // Clears any "Target: ..." readout from updateTargetLockActionBar
            // immediately instead of leaving it to fade on its own -- a stale
            // lock must never outlive the HUD it was shown on, whether the
            // HUD closed because a bind fired or because it simply timed out.
            player.sendActionBar(Component.empty());
            if (restoreHeldSlot && player.getInventory().getHeldItemSlot() == PARKED_HOTBAR_SLOT
                    && state.heldSlotAtOpen() != PARKED_HOTBAR_SLOT) {
                player.getInventory().setHeldItemSlot(state.heldSlotAtOpen());
            }
        }
        clearGlowingTarget(uuid);
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
            updateTargetLockActionBar(player, state, playerBinds);
        }
    }

    /**
     * Live "Target: <name>" readout for whichever visible bind carries a
     * target-lock rune (see {@link #TARGET_LOCK_EFFECTS}) the player can
     * currently use. Silent (no action bar sent) when none of the visible
     * binds need a target at all, so this never fights some other feature's
     * action bar for a player with an ordinary Bind HUD open. Whoever is
     * currently locked is also given a glowing outline (via {@link
     * org.bukkit.entity.Entity#setGlowing}) so the caster can see who they're
     * about to hit without staring at the action bar, cleared the instant
     * the lock moves to someone else or drops entirely.
     */
    private void updateTargetLockActionBar(Player player, HudState state, PlayerBinds playerBinds) {
        EnchantDefinition targetLockDefinition = null;
        int targetLockLevel = 0;
        for (int bindIndex : state.bars().keySet()) {
            for (String enchantId : playerBinds.bind(bindIndex)) {
                EnchantDefinition definition = enchants.definition(enchantId);
                if (definition == null || !TARGET_LOCK_EFFECTS.contains(definition.effect())) {
                    continue;
                }
                int level = RuneEquipment.highestAvailableLevel(player, enchants, enchantId);
                if (level > 0) {
                    targetLockDefinition = definition;
                    targetLockLevel = level;
                    break;
                }
            }
            if (targetLockDefinition != null) {
                break;
            }
        }
        if (targetLockDefinition == null) {
            clearGlowingTarget(player.getUniqueId());
            return;
        }
        EnchantDefinition.Level level = targetLockDefinition.level(targetLockLevel);
        double range = level == null ? 24D : level.setting("target-range-blocks", 24D);
        Player target = RuneProtection.rayTraceHostilePlayer(player, range);
        applyGlowingTarget(player.getUniqueId(), target);
        Component text = target == null
                ? Component.text("Target: —", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                : Component.text("Target: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(target.getName(), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        player.sendActionBar(text);
    }

    /**
     * Un-glows the previous lock (if any) whenever it changes, then glows
     * the new one -- but only ever turns glowing back <em>off</em> if this
     * lock was the one that turned it on in the first place. A target who
     * was already glowing for an unrelated reason (spectator visibility, an
     * admin /glow, some other effect) must keep glowing once this lock
     * releases, not go dark because we assumed we owned it.
     */
    private void applyGlowingTarget(UUID casterUuid, Player target) {
        UUID targetUuid = target == null ? null : target.getUniqueId();
        GlowLock previous = glowingTargets.get(casterUuid);
        if (previous != null && !previous.targetUuid().equals(targetUuid)) {
            releaseGlowLock(previous);
        }
        if (targetUuid == null) {
            glowingTargets.remove(casterUuid);
            return;
        }
        if (previous == null || !previous.targetUuid().equals(targetUuid)) {
            boolean alreadyGlowing = target.isGlowing();
            if (!alreadyGlowing) {
                target.setGlowing(true);
            }
            glowingTargets.put(casterUuid, new GlowLock(targetUuid, alreadyGlowing));
        }
    }

    private void releaseGlowLock(GlowLock lock) {
        if (lock.targetWasAlreadyGlowing()) {
            return;
        }
        Player previous = Bukkit.getPlayer(lock.targetUuid());
        if (previous != null) {
            previous.setGlowing(false);
        }
    }

    private void clearGlowingTarget(UUID casterUuid) {
        GlowLock previous = glowingTargets.remove(casterUuid);
        if (previous != null) {
            releaseGlowLock(previous);
        }
    }

    private Component barText(Player player, int bindIndex, List<String> runeIds) {
        String label = bindIndex == 1 ? bindOneAlternateTriggerLabel(player) : String.valueOf(bindIndex);
        Component text = Component.text("[" + label + "] ", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
        User user = users == null ? null : users.get(player.getUniqueId());
        for (int i = 0; i < runeIds.size(); i++) {
            if (i > 0) {
                text = text.append(Component.text(" | ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            text = text.append(runeSegment(player, user, runeIds.get(i)));
        }
        return text;
    }

    /**
     * What the HUD shows in place of "1" for bind 1's slot, matching
     * whichever key {@link BindActivationListener} actually listens for:
     * "F" for a click-based opener (right-click is already part of that
     * gesture -- see {@link BindActivationListener#isClickBasedOpener}),
     * the real "1" otherwise since plain right-click still works there.
     */
    private String bindOneAlternateTriggerLabel(Player player) {
        return BindActivationListener.isClickBasedOpener(binds.activationOpener(player)) ? "F" : "1";
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
