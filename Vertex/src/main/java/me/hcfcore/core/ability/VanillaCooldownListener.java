package me.hcfcore.core.ability;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class VanillaCooldownListener implements Listener {
    private final Plugin plugin;
    public VanillaCooldownListener(Plugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) return;
        if (item.getType() == Material.ENDER_PEARL && !AbilityGate.isAbility(plugin, item, "fake-pearl")) {
            apply(player, Material.ENDER_PEARL, "pearl-cooldown-seconds");
        } else if (item.getType() == Material.GOLDEN_APPLE) {
            apply(player, Material.GOLDEN_APPLE, "golden-apple-cooldown-seconds");
        } else if (item.getType() == Material.ENCHANTED_GOLDEN_APPLE) {
            apply(player, Material.ENCHANTED_GOLDEN_APPLE, "enchanted-golden-apple-cooldown-seconds");
        }
    }

    private void apply(Player player, Material material, String key) {
        int seconds = Math.max(0, plugin.getConfig().getInt("pvp." + key, 0));
        if (seconds > 0) player.setCooldown(material, seconds * 20);
    }
}
