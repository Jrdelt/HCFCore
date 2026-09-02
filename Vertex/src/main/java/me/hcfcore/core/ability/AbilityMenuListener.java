package me.hcfcore.core.ability;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class AbilityMenuListener implements Listener {

    private final Plugin plugin;
    private final AbilityManager abilityManager;

    public AbilityMenuListener(Plugin plugin, AbilityManager abilityManager) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AbilitiesMenu.Holder)) {
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("hcfcore.ability.give")) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }

        NamespacedKey key = new NamespacedKey(plugin, AbilityManager.ABILITY_ID_KEY);
        String abilityId = clicked.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (abilityId == null) {
            return;
        }
        Ability ability = abilityManager.get(abilityId);
        if (ability == null) {
            return;
        }

        for (ItemStack dropped : player.getInventory().addItem(abilityManager.createItem(ability)).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), dropped);
        }
        player.sendMessage(Component.text("Gave you ", NamedTextColor.GREEN)
                .append(LegacyComponentSerializer.legacyAmpersand().deserialize(ability.getDisplayName()))
                .append(Component.text(".", NamedTextColor.GREEN)));
    }
}
