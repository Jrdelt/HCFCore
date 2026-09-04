package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.pvp.CombatManager;
import me.hcfcore.core.user.UserManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Right-click teleports the user to whoever hit them last -- but only if
 * that hit landed within the last 15 seconds and both players are
 * currently in combat. A 5-second countdown warns the target in chat
 * before the teleport lands, then the user (the one who moved) gets a
 * short combat buff.
 */
public final class NinjaStarListener implements Listener {

    private static final String ABILITY_ID = "ninja-star";
    private static final long LAST_HIT_WINDOW_MILLIS = 15_000L;
    private static final int COUNTDOWN_SECONDS = 5;
    private static final int BUFF_DURATION_TICKS = 60; // 3 seconds

    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;
    private final CombatManager combatManager;
    private final Messages messages;
    private final Map<UUID, LastHit> lastHits = new ConcurrentHashMap<>();

    public NinjaStarListener(Plugin plugin, AbilityManager abilityManager, UserManager userManager,
                              CombatManager combatManager, Messages messages) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.userManager = userManager;
        this.combatManager = combatManager;
        this.messages = messages;
    }

    /**
     * Records every player-vs-player (or player-vs-player-via-projectile)
     * hit, regardless of who's holding a ninja star -- so "the last player
     * that hit you" is tracked continuously, not just while this item is
     * out.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        lastHits.put(victim.getUniqueId(), new LastHit(attacker.getUniqueId(), System.currentTimeMillis()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!AbilityGate.isAbility(plugin, event.getItem(), ABILITY_ID)) {
            return;
        }

        Ability ability = abilityManager.get(ABILITY_ID);
        if (ability == null) {
            return;
        }
        event.setCancelled(true);
        Player user = event.getPlayer();

        LastHit lastHit = lastHits.get(user.getUniqueId());
        if (lastHit == null || System.currentTimeMillis() - lastHit.timestampMillis > LAST_HIT_WINDOW_MILLIS) {
            user.sendMessage(messages.get(user, "ability.ninja-star-no-target"));
            return;
        }
        Player target = Bukkit.getPlayer(lastHit.attackerId);
        if (target == null || !target.isOnline()) {
            user.sendMessage(messages.get(user, "ability.ninja-star-no-target"));
            return;
        }
        if (!combatManager.isTagged(user.getUniqueId()) || !combatManager.isTagged(target.getUniqueId())) {
            user.sendMessage(messages.get(user, "ability.ninja-star-not-in-combat"));
            return;
        }

        if (!AbilityGate.checkAndStart(plugin, abilityManager, userManager, messages, user, ability)) {
            return;
        }

        user.sendMessage(messages.get(user, "ability.ninja-star-activated",
                "player", target.getName(), "seconds", String.valueOf(COUNTDOWN_SECONDS)));
        startCountdown(user.getUniqueId(), target.getUniqueId());
    }

    private void startCountdown(UUID userId, UUID targetId) {
        new BukkitRunnable() {
            int remaining = COUNTDOWN_SECONDS;

            @Override
            public void run() {
                Player user = Bukkit.getPlayer(userId);
                Player target = Bukkit.getPlayer(targetId);
                if (user == null || target == null) {
                    cancel();
                    return;
                }
                if (remaining <= 0) {
                    user.teleport(target.getLocation());
                    user.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, BUFF_DURATION_TICKS, 1));
                    user.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, BUFF_DURATION_TICKS, 2));
                    user.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, BUFF_DURATION_TICKS, 4));
                    cancel();
                    return;
                }
                target.sendMessage(messages.get(target, "ability.ninja-star-warning",
                        "player", user.getName(), "seconds", String.valueOf(remaining)));
                remaining--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastHits.remove(event.getPlayer().getUniqueId());
    }

    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private record LastHit(UUID attackerId, long timestampMillis) {
    }
}
