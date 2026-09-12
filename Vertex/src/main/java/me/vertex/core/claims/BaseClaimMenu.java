package me.vertex.core.claims;

import me.vertex.core.factions.FactionData;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.grace.DurationParser;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/** Three-slot Base Claim overview and two-stage removal confirmation. */
public final class BaseClaimMenu {
    public static final String MENU_ID = "baseclaim";
    public static final List<Integer> SLOT_POSITIONS = List.of(11, 13, 15);

    private BaseClaimMenu() { }

    public static void open(Player viewer, Plugin plugin, BaseClaimManager manager, Messages messages) {
        FactionData faction = FactionsHook.getFaction(viewer).orElse(null);
        if (faction == null) return;
        Holder holder = new Holder(faction.id(), View.OVERVIEW, 0);
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(viewer, "baseclaim.gui.title"));
        holder.inventory = inventory;
        fill(inventory, item(viewer, messages, Material.BLACK_STAINED_GLASS_PANE,
                "baseclaim.gui.filler", List.of()));
        int unlocked = manager.unlockedSlots(faction.id());
        for (int index = 1; index <= BaseClaimManager.MAX_SLOTS; index++) {
            BaseClaimManager.Region region = manager.region(faction.id(), index);
            Material material;
            String name;
            List<net.kyori.adventure.text.Component> lore;
            if (index > unlocked) {
                material = Material.BLACK_STAINED_GLASS;
                name = "baseclaim.gui.locked-name";
                lore = messages.getGuiList(viewer, "baseclaim.gui.locked-lore", "slot", String.valueOf(index));
            } else if (region == null) {
                material = Material.LIME_STAINED_GLASS;
                name = "baseclaim.gui.empty-name";
                lore = messages.getGuiList(viewer, "baseclaim.gui.empty-lore", "slot", String.valueOf(index));
            } else {
                material = Material.BEACON;
                name = "baseclaim.gui.used-name";
                long ageSeconds = Math.max(0L, (System.currentTimeMillis() - region.createdAtMillis()) / 1_000L);
                lore = messages.getGuiList(viewer, "baseclaim.gui.used-lore",
                        "slot", String.valueOf(index),
                        "shard", region.anchor().shardId().isBlank()
                                ? plugin.getConfig().getString("network.shard-id", "local")
                                : region.anchor().shardId(),
                        "world", region.anchor().localWorld(),
                        "x", String.valueOf(region.anchor().x()), "z", String.valueOf(region.anchor().z()),
                        "chunks", String.valueOf(region.members().size()),
                        "age", DurationParser.format(ageSeconds));
            }
            inventory.setItem(SLOT_POSITIONS.get(index - 1), item(viewer, messages, material, name, lore));
        }
        viewer.openInventory(inventory);
    }

    public static void openConfirmation(Player viewer, Messages messages, int factionId, int slot, int stage) {
        View view = stage == 1 ? View.CONFIRM_ONE : View.CONFIRM_TWO;
        Holder holder = new Holder(factionId, view, slot);
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(viewer,
                stage == 1 ? "baseclaim.gui.confirm-one-title" : "baseclaim.gui.confirm-two-title"));
        holder.inventory = inventory;
        fill(inventory, item(viewer, messages, Material.BLACK_STAINED_GLASS_PANE,
                "baseclaim.gui.filler", List.of()));
        inventory.setItem(11, item(viewer, messages, Material.LIME_STAINED_GLASS_PANE,
                stage == 1 ? "baseclaim.gui.continue-name" : "baseclaim.gui.remove-name",
                messages.getGuiList(viewer, stage == 1 ? "baseclaim.gui.continue-lore"
                        : "baseclaim.gui.remove-lore")));
        inventory.setItem(15, item(viewer, messages, Material.RED_STAINED_GLASS_PANE,
                "baseclaim.gui.cancel-name", messages.getGuiList(viewer, "baseclaim.gui.cancel-lore")));
        viewer.openInventory(inventory);
    }

    private static void fill(Inventory inventory, ItemStack item) {
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, item);
    }

    private static ItemStack item(Player player, Messages messages, Material material, String name,
            List<net.kyori.adventure.text.Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.getGui(player, name));
        if (!lore.isEmpty()) meta.lore(new ArrayList<>(lore));
        item.setItemMeta(meta);
        return item;
    }

    enum View { OVERVIEW, CONFIRM_ONE, CONFIRM_TWO }

    public static final class Holder implements InventoryHolder {
        private final int factionId;
        private final View view;
        private final int slotIndex;
        private Inventory inventory;
        Holder(int factionId, View view, int slotIndex) {
            this.factionId = factionId; this.view = view; this.slotIndex = slotIndex;
        }
        int factionId() { return factionId; }
        View view() { return view; }
        int slotIndex() { return slotIndex; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
