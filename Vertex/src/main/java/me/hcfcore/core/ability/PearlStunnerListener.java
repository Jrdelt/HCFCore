package me.hcfcore.core.ability;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.EnderPearl;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PearlStunnerListener implements Listener {

    private static final String ABILITY_ID = "pearl-stunner";
    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;
    private final Messages messages;
    private final Map<UUID, Long> stunnedUntil = new ConcurrentHashMap<>();

    public PearlStunnerListener(Plugin plugin, AbilityManager abilityManager, UserManager userManager,
                                Messages messages) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.userManager = userManager;
        this.messages = messages;
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player victim)
                || attacker.equals(victim)
                || !AbilityGate.isAbility(plugin, attacker.getInventory().getItemInMainHand(), ABILITY_ID)) {
            return;
        }
        // Prevent pearl stunner from affecting teammates/allies
        if (FactionsHook.isSameFaction(attacker, victim)) {
            return;
        }
        Ability ability = abilityManager.get(ABILITY_ID);
        User user = userManager.get(attacker.getUniqueId());
        Set<String> disabledClaims = Set.copyOf(plugin.getConfig().getStringList("abilities.disabled-claim-names"));
        if (ability == null || user == null
                || abilityManager.isOnGlobalCooldown(attacker.getUniqueId())
                || abilityManager.isOnCooldown(user, ability)
                || FactionsHook.isDisabledClaim(attacker.getLocation(), disabledClaims)) {
            return;
        }
        long durationSeconds = Math.max(1, ability.getInt("stun-seconds", 8));
        abilityManager.markGlobalCooldown(attacker.getUniqueId());
        abilityManager.startCooldown(attacker, user, ability);
        stunnedUntil.put(victim.getUniqueId(), System.currentTimeMillis() + durationSeconds * 1000L);
        consume(attacker);
        victim.sendMessage(messages.get(victim, "ability.pearl-stunned", "seconds", String.valueOf(durationSeconds)));
        attacker.sendMessage(messages.get(attacker, "ability.pearl-stunner-hit"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPearlAttempt(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isStunned(player.getUniqueId())) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.ENDER_PEARL
            || AbilityGate.isAbility(plugin, item, "fake-pearl")) {
            return;
        }
        event.setCancelled(true);
        long remaining = (stunnedUntil.get(player.getUniqueId()) - System.currentTimeMillis() + 999L) / 1000L;
        player.sendMessage(messages.get(player, "ability.pearl-stunned", "seconds", String.valueOf(Math.max(1L, remaining))));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPearlLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) {
            return;
        }
        if (!(pearl.getShooter() instanceof Player player)) {
            return;
        }
        if (!isStunned(player.getUniqueId())) {
            return;
        }
        // Don't block fake pearls
        if (AbilityGate.isAbility(plugin, player.getInventory().getItemInMainHand(), "fake-pearl")) {
            return;
        }
        event.setCancelled(true);
        long remaining = (stunnedUntil.get(player.getUniqueId()) - System.currentTimeMillis() + 999L) / 1000L;
        player.sendMessage(messages.get(player, "ability.pearl-stunned", "seconds", String.valueOf(Math.max(1L, remaining))));
    }

    private boolean isStunned(UUID uuid) {
        Long expiry = stunnedUntil.get(uuid);
        if (expiry == null) {
            return false;
        }
        if (expiry <= System.currentTimeMillis()) {
            stunnedUntil.remove(uuid, expiry);
            return false;
        }
        return true;
    }

    private static void consume(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            return;
        }
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }
}
