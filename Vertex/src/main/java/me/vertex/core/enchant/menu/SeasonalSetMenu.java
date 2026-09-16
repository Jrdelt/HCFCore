package me.vertex.core.enchant.menu;

import me.vertex.core.backpack.BackpackManager;
import me.vertex.core.enchant.EnchantDefinition;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneFormatting;
import me.vertex.core.enchant.RuneTier;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * One seasonal set's detail view: a double-chest-sized (54 slot), black-
 * stained-glass-bordered grid showing every piece in the set -- armor,
 * weapons, and tools -- centered in the middle rows. Each icon is that
 * piece's actual identified Rune item (level I) for its real material/
 * custom model data, via the exact same {@link EnchantManager#createEnchantItem}
 * every other menu uses -- but its lore is replaced here with just this one
 * ability's name/level and a short, word-wrapped description, deliberately
 * dropping the full identified-item stat block (proc chance, application
 * success/fail odds, "applies to") and any other enchant that item's
 * underlying catalog piece happens to also carry: a set preview is a
 * "what is this ability" card, not the same tooltip a real owned copy shows.
 * A piece whose {@code runes.yml} entry sets {@code hidden: true} (directly,
 * or inherited from its {@code sets:} group) is skipped here -- staged
 * content stays fully usable through every admin command, just not shown in
 * this player-facing preview until released.
 */
public final class SeasonalSetMenu {

    private static final List<Integer> CONTENT_SLOTS = List.of(
            19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43);
    public static final int BACK_SLOT = 49;

    private SeasonalSetMenu() {
    }

    /** Fallen set's backpack tier id, mirroring the {@code fallen_crate} entry in {@code backpacks.yml}. */
    private static final String FALLEN_BACKPACK_TIER = "fallen_crate";

    public static void open(Player player, EnchantManager manager, BackpackManager backpackManager,
            Messages messages) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 54,
                messages.getGui(player, "rune.seasonal-set-title", "name", "Fallen Set"));
        holder.inventory = inventory;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler());
        }
        int index = 0;
        for (String id : manager.seasonalIds()) {
            if (index >= CONTENT_SLOTS.size()) {
                break;
            }
            EnchantDefinition definition = manager.definition(id);
            if (definition == null || definition.isHidden()) {
                continue;
            }
            ItemStack item = manager.createEnchantItem(id, 1, RuneTier.SEASONAL);
            if (item != null && item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                meta.displayName(RuneFormatting.seasonalTitle(definition.displayName(), 1));
                List<Component> lore = new java.util.ArrayList<>();
                for (String line : RuneFormatting.wrap(definition.description(), 20)) {
                    lore.add(RuneFormatting.plain(line, NamedTextColor.GRAY));
                }
                lore.add(Component.empty());
                lore.add(messages.getGui(player, "rune.seasonal-item-catalog-hint"));
                meta.lore(lore);
                item.setItemMeta(meta);
            }
            inventory.setItem(CONTENT_SLOTS.get(index), item);
            index++;
        }
        if (index < CONTENT_SLOTS.size() && backpackManager.isTier(FALLEN_BACKPACK_TIER)) {
            ItemStack backpack = backpackManager.createBackpackItem(FALLEN_BACKPACK_TIER, 1);
            inventory.setItem(CONTENT_SLOTS.get(index), backpack);
        }
        inventory.setItem(BACK_SLOT, button(Material.ARROW, messages.getGui(player, "rune.seasonal-back"),
                List.of(messages.getGui(player, "rune.seasonal-back-lore"))));
        player.openInventory(inventory);
    }

    public static boolean isContentSlot(int slot) {
        return CONTENT_SLOTS.contains(slot);
    }

    private static ItemStack filler() {
        return button(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        private Holder() {
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
