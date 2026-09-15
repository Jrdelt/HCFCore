package me.vertex.core.resetvault;

import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;

/**
 * Manages creation and secure PDC validation of '+1 Reset Vault Slot' tokens.
 * A genuine token is a non-stackable, tradable custom NAME_TAG.
 * Renamed vanilla name tags are strictly rejected.
 */
public final class ResetVaultToken {

    private final NamespacedKey markerKey;
    private final NamespacedKey instanceKey;
    private final Messages messages;

    public ResetVaultToken(Plugin plugin, Messages messages) {
        this.markerKey = new NamespacedKey(plugin, "rv_slot_token");
        this.instanceKey = new NamespacedKey(plugin, "rv_token_instance");
        this.messages = messages;
    }

    /**
     * @return true if the item carries the authoritative PDC marker for a slot token.
     */
    public boolean isToken(ItemStack item) {
        if (item == null || item.getType() != Material.NAME_TAG || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    /**
     * Creates a genuine, non-stackable, tradable +1 Reset Vault Slot token.
     */
    public ItemStack createToken(CommandSender sender) {
        ItemStack item = new ItemStack(Material.NAME_TAG, 1);
        ItemMeta meta = item.getItemMeta();

        // Secure PDC markers
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(instanceKey, PersistentDataType.STRING, UUID.randomUUID().toString());

        // Enchantment glint override
        meta.setEnchantmentGlintOverride(true);

        // Localized name & lore
        meta.displayName(messages.getGui(sender, "reset-vault.token.name")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(messages.getGuiList(sender, "reset-vault.token.lore").stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false))
                .toList());

        item.setItemMeta(meta);
        return item;
    }
}
