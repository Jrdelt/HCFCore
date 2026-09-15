package me.vertex.core.enchant.binds.menu;

import me.vertex.core.enchant.EnchantDefinition;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneFormatting;
import me.vertex.core.enchant.binds.BindActivationOpener;
import me.vertex.core.enchant.binds.BindManager;
import me.vertex.core.enchant.binds.PlayerBinds;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code /binds}' home GUI: a compact 3-row layout. Row 1 is reserved for
 * future features (stained-glass filler for now); row 2 holds the 7
 * bind-key icons, centered; row 3 holds the Activation Method, Presets,
 * Help, and (when the live layout has diverged from its loaded preset)
 * Update Preset buttons.
 */
public final class BindsHomeMenu {

    private static final Map<Integer, Integer> BIND_SLOTS = Map.ofEntries(
            Map.entry(1, 10), Map.entry(2, 11), Map.entry(3, 12), Map.entry(4, 13),
            Map.entry(5, 14), Map.entry(6, 15), Map.entry(7, 16));
    public static final int ACTIVATION_METHOD_SLOT = 19;
    public static final int PRESETS_SLOT = 21;
    public static final int HELP_SLOT = 23;
    public static final int UPDATE_PRESET_SLOT = 25;

    private BindsHomeMenu() {
    }

    public static void open(Player player, EnchantManager manager, BindManager bindManager, PlayerBinds binds, Messages messages) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(player, "binds.home-title"));
        holder.inventory = inventory;
        for (int slot = 0; slot < 27; slot++) {
            inventory.setItem(slot, filler());
        }
        for (Map.Entry<Integer, Integer> entry : BIND_SLOTS.entrySet()) {
            inventory.setItem(entry.getValue(), bindIcon(player, manager, binds, messages, entry.getKey()));
        }
        inventory.setItem(ACTIVATION_METHOD_SLOT, activationMethodButton(player, bindManager, messages));
        inventory.setItem(PRESETS_SLOT, button(Material.BOOK,
                messages.getGui(player, "binds.presets-button"),
                presetLore(player, binds, messages)));
        inventory.setItem(HELP_SLOT, button(Material.KNOWLEDGE_BOOK,
                messages.getGui(player, "binds.help-button"),
                List.of(messages.getGui(player, "binds.help-lore"))));
        if (binds.dirty() && binds.activePresetIndex() != null) {
            inventory.setItem(UPDATE_PRESET_SLOT, button(Material.ANVIL,
                    messages.getGui(player, "binds.update-preset-button"),
                    List.of(messages.getGui(player, "binds.update-preset-lore"))));
        }
        player.openInventory(inventory);
    }

    private static ItemStack activationMethodButton(Player player, BindManager bindManager, Messages messages) {
        BindActivationOpener current = bindManager.activationOpener(player);
        List<Component> lore = new ArrayList<>();
        lore.add(messages.getGui(player, "binds.activation-method-lore"));
        lore.add(Component.empty());
        for (BindActivationOpener opener : BindActivationOpener.values()) {
            boolean selected = opener == current;
            Component line = Component.text(opener.displayName(), selected ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.UNDERLINED, selected);
            lore.add(line);
        }
        return button(Material.COMPARATOR, messages.getGui(player, "binds.activation-method"), lore);
    }

    private static List<Component> presetLore(Player player, PlayerBinds binds, Messages messages) {
        List<Component> lore = new ArrayList<>();
        if (binds.activePresetIndex() != null) {
            lore.add(messages.getGui(player, "binds.active-preset", "preset", String.valueOf(binds.activePresetIndex())));
        } else {
            lore.add(messages.getGui(player, "binds.no-active-preset"));
        }
        return lore;
    }

    private static ItemStack bindIcon(Player player, EnchantManager manager, PlayerBinds binds, Messages messages, int bindIndex) {
        List<String> runeIds = binds.bind(bindIndex);
        ItemStack item = new ItemStack(runeIds.isEmpty() ? Material.GRAY_DYE : Material.NAME_TAG, Math.max(1, runeIds.size()));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(RuneFormatting.plain("ʙɪɴᴅ " + bindIndex, runeIds.isEmpty() ? NamedTextColor.DARK_GRAY : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (runeIds.isEmpty()) {
            lore.add(messages.getGui(player, "binds.bind-empty"));
        } else {
            lore.add(messages.getGui(player, "binds.bind-order", "runes", runeIds.stream()
                    .map(id -> displayName(manager, id)).collect(Collectors.joining(" > "))));
        }
        lore.add(messages.getGui(player, "binds.bind-edit-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String displayName(EnchantManager manager, String enchantId) {
        EnchantDefinition definition = manager.definition(enchantId);
        return RuneFormatting.smallCaps(definition == null ? enchantId : definition.displayName());
    }

    private static ItemStack filler() {
        return button(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static Integer bindIndexAt(int slot) {
        return BIND_SLOTS.entrySet().stream().filter(entry -> entry.getValue() == slot)
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
