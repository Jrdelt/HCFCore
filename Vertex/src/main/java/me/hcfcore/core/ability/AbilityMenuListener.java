package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.lang.MessageFormatter;
import net.kyori.adventure.text.Component;
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
    private final Messages messages;

    public AbilityMenuListener(Plugin plugin, AbilityManager abilityManager, Messages messages) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.messages = messages;
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
        player.sendMessage(messages.get(player, "ability.gui-received")
                .append(MessageFormatter.deserialize(ability.getDisplayName()))
                .append(Component.text(".")));
    }
}
