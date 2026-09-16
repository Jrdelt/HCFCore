package me.vertex.core.enchant;

import me.vertex.core.backpack.BackpackManager;
import me.vertex.core.lang.Messages;
import me.vertex.core.mine.MineManager;
import me.vertex.core.zone.ZoneManager;
import me.vertex.core.zone.ZoneRegion;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * War Chest (seasonal, backpack tier {@code war_chest} -- see {@code
 * me.vertex.core.backpack.BackpackManager}): passive, owner-only. While the
 * owner's equipped backpack is specifically a War Chest, an eligible mining
 * action or credited mob kill inside an eligible mine/mob arena rolls a
 * chance to grant one random booster directly, no orbs or pickups.
 *
 * <p>Eligibility deliberately reuses the two arena concepts that already
 * exist rather than inventing a third: {@link MineManager#regionAt} for
 * mining, and {@link ZoneManager}'s Haven/Riftlands regions (the same "mob
 * arena" concept Arena-tier runes already gate on via {@code enabled-zones})
 * for mob kills.
 *
 * <p>The proc cooldown here is intentionally a plain in-memory map, not a
 * {@link RuneCooldownStore} entry -- War Chest is not an {@link
 * EnchantDefinition}, and the window is only 10 seconds by default, so
 * losing an in-flight cooldown across a restart is an accepted, disclosed
 * simplification rather than a persisted guarantee.
 */
public final class WarChestListener implements Listener {

    private final EnchantManager manager;
    private final BackpackManager backpacks;
    private final Messages messages;
    private volatile MineManager mines;
    private volatile ZoneManager zones;

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    /** Golden Bounty's temporary drop-bonus window, one per owner. */
    private final Map<UUID, Bounty> activeBounties = new ConcurrentHashMap<>();

    private record Bounty(double oreBonusPercent, double mobBonusPercent, long expiresAt) {
    }

    public WarChestListener(EnchantManager manager, BackpackManager backpacks, Messages messages) {
        this.manager = manager;
        this.backpacks = backpacks;
        this.messages = messages;
    }

    public void setMineManager(MineManager mines) {
        this.mines = mines;
    }

    public void setZoneManager(ZoneManager zones) {
        this.zones = zones;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!isEligibleMiningLocation(event.getBlock().getLocation())) {
            return;
        }
        applyGoldenBountyOreBonus(player, event);
        attemptProc(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || event.getEntity() instanceof Player || !isEligibleMobArenaLocation(event.getEntity().getLocation())) {
            return;
        }
        applyGoldenBountyMobBonus(killer, event);
        attemptProc(killer);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cooldowns.remove(uuid);
        activeBounties.remove(uuid);
    }

    private boolean isEligibleMiningLocation(Location location) {
        MineManager mineManager = mines;
        return mineManager != null && mineManager.regionAt(location) != null;
    }

    private boolean isEligibleMobArenaLocation(Location location) {
        ZoneManager zoneManager = zones;
        if (zoneManager == null) {
            return false;
        }
        ZoneRegion region = zoneManager.regionAt(location);
        return region != null;
    }

    private BackpackManager.EquippedBackpack getEquippedWarChest(Player player) {
        BackpackManager.EquippedBackpack equipped = backpacks.equippedBackpack(player);
        return equipped != null && "war_chest".equals(equipped.tierId()) ? equipped : null;
    }

    private void attemptProc(Player player) {
        BackpackManager.EquippedBackpack equipped = getEquippedWarChest(player);
        if (equipped == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long cooldownUntil = cooldowns.get(uuid);
        if (cooldownUntil != null && now < cooldownUntil) {
            return;
        }
        int level = equipped.level();
        if (level <= 0) {
            return;
        }
        EnchantManager.WarChestLevel config = manager.warChestLevel(level);
        double chance = config.procChancePercent();
        if (chance <= 0D || ThreadLocalRandom.current().nextDouble(100D) >= chance) {
            return;
        }
        EnchantManager.WarChestBooster booster = pickBooster();
        if (booster == null) {
            return;
        }
        // Failed rolls never start the cooldown; only a successful proc does.
        cooldowns.put(uuid, now + Math.round(config.cooldownSeconds() * 1000D));
        grantBooster(player, booster);
    }

    private EnchantManager.WarChestBooster pickBooster() {
        List<EnchantManager.WarChestBooster> eligible = manager.warChestBoosters().stream()
                .filter(b -> b.enabled() && b.weight() > 0D).toList();
        if (eligible.isEmpty()) {
            return null;
        }
        double totalWeight = eligible.stream().mapToDouble(EnchantManager.WarChestBooster::weight).sum();
        double roll = ThreadLocalRandom.current().nextDouble(totalWeight);
        double cumulative = 0D;
        for (EnchantManager.WarChestBooster booster : eligible) {
            cumulative += booster.weight();
            if (roll < cumulative) {
                return booster;
            }
        }
        return eligible.getLast();
    }

    private void grantBooster(Player player, EnchantManager.WarChestBooster booster) {
        int durationTicks = (int) Math.round(booster.durationSeconds() * 20D);
        switch (booster.id()) {
            case "tailwind" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationTicks,
                        Math.max(0, booster.potionLevel() - 1)));
                sendBoosterMessage(player, "war-chest.tailwind", booster);
            }
            case "predators_fury" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, durationTicks,
                        Math.max(0, booster.potionLevel() - 1)));
                sendBoosterMessage(player, "war-chest.predators-fury", booster);
            }
            case "golden_bounty" -> {
                activeBounties.put(player.getUniqueId(), new Bounty(booster.oreDropBonusPercent(),
                        booster.mobDropBonusPercent(), System.currentTimeMillis() + Math.round(booster.durationSeconds() * 1000D)));
                sendBoosterMessage(player, "war-chest.golden-bounty", booster);
            }
            default -> {
            }
        }
    }

    private void sendBoosterMessage(Player player, String key, EnchantManager.WarChestBooster booster) {
        Component message = messages.getGui(player, key,
                "tier", RuneFormatting.roman(booster.potionLevel()),
                "duration", RuneFormatting.percent(booster.durationSeconds()),
                "ore-bonus", RuneFormatting.percent(booster.oreDropBonusPercent()),
                "mob-bonus", RuneFormatting.percent(booster.mobDropBonusPercent()));
        player.sendMessage(message);
    }

    /**
     * Golden Bounty applies to the owner's own eligible normal yield only,
     * computed from what the break already resolved to drop -- never a
     * second independent roll, and never applied to bonus items Golden
     * Vein/Harvest already generated (those effects run in a separate
     * listener and this one only ever sees the vanilla/base drop set here).
     */
    private void applyGoldenBountyOreBonus(Player player, BlockBreakEvent event) {
        Bounty bounty = activeBounties.get(player.getUniqueId());
        if (bounty == null) {
            return;
        }
        if (System.currentTimeMillis() > bounty.expiresAt()) {
            activeBounties.remove(player.getUniqueId());
            return;
        }
        if (bounty.oreBonusPercent() <= 0D) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble(100D) >= bounty.oreBonusPercent()) {
            return;
        }
        var tool = player.getInventory().getItemInMainHand();
        for (var drop : event.getBlock().getDrops(tool, player)) {
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), drop.clone());
        }
    }

    private void applyGoldenBountyMobBonus(Player killer, EntityDeathEvent event) {
        Bounty bounty = activeBounties.get(killer.getUniqueId());
        if (bounty == null) {
            return;
        }
        if (System.currentTimeMillis() > bounty.expiresAt()) {
            activeBounties.remove(killer.getUniqueId());
            return;
        }
        if (bounty.mobBonusPercent() <= 0D || event.getDrops().isEmpty()) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble(100D) >= bounty.mobBonusPercent()) {
            return;
        }
        LivingEntity dead = event.getEntity();
        for (var drop : List.copyOf(event.getDrops())) {
            dead.getWorld().dropItemNaturally(dead.getLocation(), drop.clone());
        }
    }
}
