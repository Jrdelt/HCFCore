package me.vertex.core.collector;

import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

/** A small, free anvil prompt used to enter a collector withdrawal amount. */
public final class CollectorWithdrawAmountMenu {

    static final int SLOT_INPUT = 0;
    static final int SLOT_RESULT = 2;

    private CollectorWithdrawAmountMenu() {
    }

    public static void open(Player player, Messages messages, Location location, Material material) {
        Holder holder = new Holder(location, material, messages);
        Inventory inventory = Bukkit.createInventory(holder, InventoryType.ANVIL,
                messages.get(player, "collector.withdraw-title", "item", material.name()));
        holder.inventory = inventory;

        ItemStack input = new ItemStack(material);
        ItemMeta meta = input.getItemMeta();
        meta.displayName(noItalic(messages.get(player, "collector.withdraw-prompt")));
        input.setItemMeta(meta);
        inventory.setItem(SLOT_INPUT, input);
        inventory.setItem(SLOT_RESULT, confirmButton(player, messages, null));
        player.openInventory(inventory);
        setFreeCost(player, inventory);
    }

    static ItemStack confirmButton(Player player, Messages messages, Long amount) {
        boolean valid = amount != null && amount > 0;
        ItemStack button = new ItemStack(valid ? Material.LIME_DYE : Material.PAPER);
        ItemMeta meta = button.getItemMeta();
        meta.displayName(noItalic(messages.get(player,
                valid ? "collector.withdraw-confirm" : "collector.withdraw-confirm-empty",
                "amount", valid ? String.format("%,d", amount) : "")));
        button.setItemMeta(meta);
        return button;
    }

    @SuppressWarnings("removal")
    static Long readAmount(Player player, Messages messages, org.bukkit.inventory.InventoryView view, Inventory inventory) {
        String text;
        if (view instanceof AnvilView anvilView) {
            text = anvilView.getRenameText();
        } else if (inventory instanceof AnvilInventory anvilInventory) {
            text = anvilInventory.getRenameText();
        } else {
            return null;
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        String prompt = MessageFormatter.plain(messages.getRaw(player, "collector.withdraw-prompt")).trim();
        if (value.equalsIgnoreCase(prompt)) {
            return null;
        }
        try {
            long amount = Long.parseLong(value.replace(",", ""));
            return amount > 0 ? amount : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SuppressWarnings("removal")
    private static void setFreeCost(Player player, Inventory inventory) {
        if (player.getOpenInventory() instanceof AnvilView view) {
            view.setRepairCost(0);
            view.setMaximumRepairCost(0);
        } else if (inventory instanceof AnvilInventory anvil) {
            anvil.setRepairCost(0);
            anvil.setMaximumRepairCost(0);
        }
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    static final class Holder implements InventoryHolder {
        private final Location location;
        private final Material material;
        private final Messages messages;
        private Inventory inventory;
        // Vanilla anvils clear their own rename-text field as part of taking
        // the result item, and that reset can land before this plugin's own
        // InventoryClickEvent handler runs -- so re-reading getRenameText()
        // at click time can see a blank field even right after
        // PrepareAnvilEvent computed and displayed a valid amount on the
        // confirm button. Cache the last amount PrepareAnvilEvent
        // successfully parsed so the click handler has a same-value
        // fallback instead of failing a withdrawal the player can see was
        // already accepted.
        private Long lastPreparedAmount;

        Holder(Location location, Material material, Messages messages) {
            this.location = location;
            this.material = material;
            this.messages = messages;
        }

        Location location() {
            return location;
        }

        Material material() {
            return material;
        }

        Messages messages() {
            return messages;
        }

        Long lastPreparedAmount() {
            return lastPreparedAmount;
        }

        void setLastPreparedAmount(Long amount) {
            this.lastPreparedAmount = amount;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
