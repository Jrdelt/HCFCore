package me.hcfcore.core.tag;

import me.hcfcore.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class TagSearchMenu {

    private TagSearchMenu() {
    }

    public static void open(Player player, TagManager manager, Messages messages, TagMenuState state) {
        Holder holder = new Holder(manager, messages, state);
        Inventory inventory = Bukkit.createInventory(holder, InventoryType.ANVIL, messages.get(player, "tags.search-heading"));
        holder.inventory = inventory;

        ItemStack input = new ItemStack(Material.PAPER);
        ItemMeta meta = input.getItemMeta();
        meta.displayName(messages.get(player, "tags.search-prompt"));
        input.setItemMeta(meta);
        inventory.setItem(0, input);

        player.openInventory(inventory);
    }

    @SuppressWarnings("deprecation")
    static String readQuery(Inventory inventory) {
        if (!(inventory instanceof AnvilInventory anvil)) {
            return null;
        }
        return anvil.getRenameText();
    }

    public static final class Holder implements InventoryHolder {
        private final TagManager manager;
        private final Messages messages;
        private final TagMenuState state;
        private Inventory inventory;

        Holder(TagManager manager, Messages messages, TagMenuState state) {
            this.manager = manager;
            this.messages = messages;
            this.state = state;
        }

        public TagManager manager() {
            return manager;
        }

        public Messages messages() {
            return messages;
        }

        public TagMenuState state() {
            return state;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
