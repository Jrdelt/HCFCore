package me.vertex.core.resetvault;

import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 54-slot raw editor GUI for administrators to view and modify any player's vault.
 * All modifications are raw/as-is and audit-logged.
 */
public final class AdminVaultMenu {

    private static final int PAGE_SIZE = 45;
    private static final int PREV_SLOT = 45;
    private static final int INFO_SLOT = 48;
    private static final int NEXT_SLOT = 53;

    private AdminVaultMenu() {}

    public static void open(
            Player adminPlayer,
            UUID targetUuid,
            String targetIgn,
            ResetVaultManager manager,
            Messages messages,
            int page
    ) {
        Holder holder = new Holder(targetUuid, targetIgn, manager, messages, Math.max(0, page));
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(adminPlayer, "reset-vault.admin.title",
                "player", targetIgn));
        holder.inventory = inventory;

        render(adminPlayer, holder, inventory);
        adminPlayer.openInventory(inventory);
    }

    public static void render(Player adminPlayer, Holder holder, Inventory inventory) {
        inventory.clear();
        UUID targetUuid = holder.targetUuid;
        ResetVaultManager manager = holder.manager;
        Messages messages = holder.messages;
        int page = holder.page;

        ResetVaultData data = manager.getVaultData(targetUuid);
        int totalItems = data.itemCount();
        int totalPages = Math.max(1, (totalItems + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= totalPages) {
            page = totalPages - 1;
            holder.page = page;
        }

        int start = page * PAGE_SIZE;

        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = start + slot;
            if (index < totalItems) {
                ItemStack item = data.items().get(index).clone();
                ItemMeta meta = item.getItemMeta();
                List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Component.empty());
                lore.add(messages.getGui(adminPlayer, "reset-vault.admin.click-to-remove")
                        .decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
                item.setItemMeta(meta);
                inventory.setItem(slot, item);
            } else {
                ItemStack empty = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
                ItemMeta meta = empty.getItemMeta();
                meta.displayName(messages.getGui(adminPlayer, "reset-vault.admin.empty-slot")
                        .decoration(TextDecoration.ITALIC, false));
                empty.setItemMeta(meta);
                inventory.setItem(slot, empty);
            }
        }

        // Bottom row
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fMeta = filler.getItemMeta();
        fMeta.displayName(Component.text(" "));
        filler.setItemMeta(fMeta);
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        if (page > 0) {
            ItemStack prev = new ItemStack(Material.RED_DYE);
            ItemMeta pMeta = prev.getItemMeta();
            pMeta.displayName(messages.getGui(adminPlayer, "reset-vault.gui.prev-page", "page", String.valueOf(page))
                    .decoration(TextDecoration.ITALIC, false));
            prev.setItemMeta(pMeta);
            inventory.setItem(PREV_SLOT, prev);
        }

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(messages.getGui(adminPlayer, "reset-vault.admin.info-name",
                "player", holder.targetIgn).decoration(TextDecoration.ITALIC, false));
        infoMeta.lore(messages.getGuiList(adminPlayer, "reset-vault.admin.info-lore",
                "player", holder.targetIgn,
                "uuid", targetUuid.toString(),
                "count", String.valueOf(totalItems),
                "bonus", String.valueOf(data.permanentBonusSlots())).stream()
                .map(l -> l.decoration(TextDecoration.ITALIC, false))
                .toList());
        info.setItemMeta(infoMeta);
        inventory.setItem(INFO_SLOT, info);

        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.GREEN_DYE);
            ItemMeta nMeta = next.getItemMeta();
            nMeta.displayName(messages.getGui(adminPlayer, "reset-vault.gui.next-page", "page", String.valueOf(page + 2))
                    .decoration(TextDecoration.ITALIC, false));
            next.setItemMeta(nMeta);
            inventory.setItem(NEXT_SLOT, next);
        }
    }

    public static final class Holder implements InventoryHolder {
        final UUID targetUuid;
        final String targetIgn;
        final ResetVaultManager manager;
        final Messages messages;
        int page;
        Inventory inventory;

        public Holder(UUID targetUuid, String targetIgn, ResetVaultManager manager, Messages messages, int page) {
            this.targetUuid = targetUuid;
            this.targetIgn = targetIgn;
            this.manager = manager;
            this.messages = messages;
            this.page = page;
        }

        public UUID targetUuid() { return targetUuid; }
        public String targetIgn() { return targetIgn; }
        public int page() { return page; }
        public void setPage(int page) { this.page = page; }
        public ResetVaultManager manager() { return manager; }
        public Messages messages() { return messages; }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
