package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class VanillaCooldownListener implements Listener {
    private final Plugin plugin;
    private final Messages messages;
    private final VanillaCooldownManager cooldownManager;
    private final int pearlCooldownSeconds;

    public VanillaCooldownListener(Plugin plugin, Messages messages, VanillaCooldownManager cooldownManager) {
        this.plugin = plugin;
        this.messages = messages;
        this.cooldownManager = cooldownManager;
        this.pearlCooldownSeconds = plugin.getConfig().getInt("pvp.pearl-cooldown-seconds", 12);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) return;
        if (item.getType() == Material.ENDER_PEARL && !AbilityGate.isAbility(plugin, item, "fake-pearl")
                && !isBlockUse(event)) {
            checkPearlCooldown(event, player);
        }
    }

    /**
     * Whether this click will open/toggle the block rather than throw the
     * pearl -- vanilla gives the block priority over the held item unless
     * the player is sneaking. Both halves of the pearl cooldown have to
     * skip those clicks: starting it would burn a pearl cooldown for
     * opening a chest, and checking it would stop a player who is already
     * on cooldown from opening that chest at all.
     */
    private static boolean isBlockUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getPlayer().isSneaking()) {
            return false;
        }
        if (event.useInteractedBlock() == Event.Result.DENY) {
            return false;
        }
        return event.getClickedBlock() != null && event.getClickedBlock().getType().isInteractable();
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Material material = event.getItem().getType();
        String configKey;
        String itemKey;
        if (material == Material.GOLDEN_APPLE) {
            configKey = "golden-apple-cooldown-seconds";
            itemKey = "golden-apple";
        } else if (material == Material.ENCHANTED_GOLDEN_APPLE) {
            configKey = "enchanted-golden-apple-cooldown-seconds";
            itemKey = "enchanted-golden-apple";
        } else {
            return;
        }

        Player player = event.getPlayer();
        long remaining = cooldownManager.remainingMillis(player.getUniqueId(), material);
        if (remaining > 0L) {
            event.setCancelled(true);
            sendCooldownMessage(player, itemKey, remaining);
            return;
        }
        cooldownManager.start(player.getUniqueId(), material,
                Math.max(0, plugin.getConfig().getInt("pvp." + configKey, 0)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile instanceof org.bukkit.entity.EnderPearl) || !(projectile.getShooter() instanceof Player)) {
            return;
        }

        Player player = (Player) projectile.getShooter();

        // Skip cooldown for fake pearls
        if (FakePearlListener.isThrowingFakePearl(player.getUniqueId())) {
            return;
        }

        long remaining = cooldownManager.remainingMillis(player.getUniqueId(), Material.ENDER_PEARL);
        if (remaining > 0L) {
            event.setCancelled(true);
            sendCooldownMessage(player, "ender-pearl", remaining);
            return;
        }

        // Pearl was thrown - start cooldown for next pearl
        cooldownManager.start(player.getUniqueId(), Material.ENDER_PEARL, pearlCooldownSeconds);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldownManager.clearIfExpired(event.getPlayer().getUniqueId());
    }

    private void checkPearlCooldown(PlayerInteractEvent event, Player player) {
        long remaining = cooldownManager.remainingMillis(player.getUniqueId(), Material.ENDER_PEARL);
        if (remaining > 0L) {
            event.setCancelled(true);
            sendCooldownMessage(player, "ender-pearl", remaining);
        }
    }

    private void sendCooldownMessage(Player player, String itemKey, long remainingMillis) {
        long remainingSeconds = (remainingMillis + 999L) / 1000L;
        player.sendMessage(messages.getChat(player, "cooldowns.item-on-cooldown",
                "item", messages.getRaw(player, "cooldowns." + itemKey),
                "seconds", String.valueOf(remainingSeconds)));
    }
}
