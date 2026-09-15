package me.vertex.core.enchant.binds.menu;

import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.binds.BindActivationOpener;
import me.vertex.core.enchant.binds.BindManager;
import me.vertex.core.enchant.binds.PlayerBinds;
import me.vertex.core.lang.Messages;
import me.vertex.core.storage.InventoryAccess;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Click handling across every {@code /binds} GUI page. */
public final class BindsMenuListener implements Listener {

    private final EnchantManager enchants;
    private final BindManager binds;
    private final Messages messages;

    public BindsMenuListener(EnchantManager enchants, BindManager binds, Messages messages) {
        this.enchants = enchants;
        this.binds = binds;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (isBindHolder(event.getInventory().getHolder())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!InventoryAccess.ready(enchants.plugin(), player)) {
            event.setCancelled(true);
            return;
        }
        var top = event.getView().getTopInventory();
        var holder = top.getHolder();
        if (!isBindHolder(holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != top) {
            return;
        }
        PlayerBinds playerBinds = binds.get(player.getUniqueId());
        if (playerBinds == null) {
            return;
        }
        if (holder instanceof BindsHomeMenu.Holder) {
            handleHome(event, player, playerBinds);
        } else if (holder instanceof BindEditMenu.Holder editHolder) {
            handleEdit(event, player, playerBinds, editHolder);
        } else if (holder instanceof RuneSelectorMenu.Holder selectorHolder) {
            handleSelector(event, player, playerBinds, selectorHolder);
        } else if (holder instanceof PresetMenu.Holder) {
            handlePresets(event, player, playerBinds);
        } else if (holder instanceof PresetConfirmMenu.Holder confirmHolder) {
            handleConfirm(event, player, confirmHolder);
        }
    }

    private void handleHome(InventoryClickEvent event, Player player, PlayerBinds playerBinds) {
        Integer bindIndex = BindsHomeMenu.bindIndexAt(event.getSlot());
        if (bindIndex != null) {
            BindEditMenu.open(player, enchants, playerBinds, messages, bindIndex);
            return;
        }
        if (event.getSlot() == BindsHomeMenu.ACTIVATION_METHOD_SLOT) {
            BindActivationOpener next = binds.cycleActivationOpener(player);
            player.sendMessage(messages.get(player, "binds.activation-method-set", "method", next.displayName()));
            BindsHomeMenu.open(player, enchants, binds, playerBinds, messages);
            return;
        }
        if (event.getSlot() == BindsHomeMenu.PRESETS_SLOT) {
            PresetMenu.open(player, binds, playerBinds, messages);
            return;
        }
        if (event.getSlot() == BindsHomeMenu.HELP_SLOT) {
            for (String line : new String[] {"binds.help-line-1", "binds.help-line-2", "binds.help-line-3"}) {
                player.sendMessage(messages.get(player, line));
            }
            return;
        }
        if (event.getSlot() == BindsHomeMenu.UPDATE_PRESET_SLOT && playerBinds.activePresetIndex() != null) {
            PresetConfirmMenu.open(player, messages, PresetConfirmMenu.Action.UPDATE, playerBinds.activePresetIndex());
        }
    }

    private void handleEdit(InventoryClickEvent event, Player player, PlayerBinds playerBinds, BindEditMenu.Holder holder) {
        if (event.getSlot() == BindEditMenu.BACK_SLOT) {
            BindsHomeMenu.open(player, enchants, binds, playerBinds, messages);
            return;
        }
        Integer slotIndex = BindEditMenu.slotIndexAt(event.getSlot());
        if (slotIndex == null) {
            return;
        }
        int bindIndex = holder.bindIndex();
        boolean assigned = slotIndex < playerBinds.bind(bindIndex).size();
        if (!assigned) {
            RuneSelectorMenu.open(player, enchants, messages, bindIndex, slotIndex);
            return;
        }
        if (event.isShiftClick()) {
            binds.reorder(player, bindIndex, slotIndex, event.isLeftClick() ? -1 : 1);
            BindEditMenu.open(player, enchants, binds.get(player.getUniqueId()), messages, bindIndex);
        } else if (event.isRightClick()) {
            binds.removeSlot(player, bindIndex, slotIndex);
            BindEditMenu.open(player, enchants, binds.get(player.getUniqueId()), messages, bindIndex);
        } else {
            RuneSelectorMenu.open(player, enchants, messages, bindIndex, slotIndex);
        }
    }

    private void handleSelector(InventoryClickEvent event, Player player, PlayerBinds playerBinds, RuneSelectorMenu.Holder holder) {
        if (event.getSlot() == RuneSelectorMenu.BACK_SLOT) {
            BindEditMenu.open(player, enchants, playerBinds, messages, holder.bindIndex());
            return;
        }
        String enchantId = RuneSelectorMenu.enchantIdAt(holder, event.getSlot());
        if (enchantId == null) {
            return;
        }
        binds.setSlot(player, holder.bindIndex(), holder.slotIndex(), enchantId);
        BindEditMenu.open(player, enchants, binds.get(player.getUniqueId()), messages, holder.bindIndex());
    }

    private void handlePresets(InventoryClickEvent event, Player player, PlayerBinds playerBinds) {
        if (event.getSlot() == PresetMenu.BACK_SLOT) {
            BindsHomeMenu.open(player, enchants, binds, playerBinds, messages);
            return;
        }
        Integer presetIndex = PresetMenu.presetIndexAt(event.getSlot());
        if (presetIndex == null || presetIndex > binds.presetLimit(player)) {
            return;
        }
        boolean exists = playerBinds.presetIndexes().contains(presetIndex);
        if (event.isRightClick()) {
            if (exists) {
                PresetConfirmMenu.open(player, messages, PresetConfirmMenu.Action.DELETE, presetIndex);
            }
            return;
        }
        if (exists) {
            binds.loadPreset(player, presetIndex);
            player.sendMessage(messages.get(player, "binds.preset-loaded", "preset", String.valueOf(presetIndex)));
            BindsHomeMenu.open(player, enchants, binds, binds.get(player.getUniqueId()), messages);
        } else {
            binds.saveAsNewPreset(player, presetIndex);
            player.sendMessage(messages.get(player, "binds.preset-saved", "preset", String.valueOf(presetIndex)));
            PresetMenu.open(player, binds, binds.get(player.getUniqueId()), messages);
        }
    }

    private void handleConfirm(InventoryClickEvent event, Player player, PresetConfirmMenu.Holder holder) {
        if (event.getSlot() == PresetConfirmMenu.CANCEL_SLOT) {
            player.closeInventory();
            return;
        }
        if (event.getSlot() != PresetConfirmMenu.CONFIRM_SLOT) {
            return;
        }
        if (holder.action() == PresetConfirmMenu.Action.UPDATE) {
            binds.updateActivePreset(player);
            player.sendMessage(messages.get(player, "binds.preset-updated", "preset", String.valueOf(holder.presetIndex())));
            BindsHomeMenu.open(player, enchants, binds, binds.get(player.getUniqueId()), messages);
        } else {
            binds.deletePreset(player, holder.presetIndex());
            player.sendMessage(messages.get(player, "binds.preset-deleted", "preset", String.valueOf(holder.presetIndex())));
            PresetMenu.open(player, binds, binds.get(player.getUniqueId()), messages);
        }
    }

    private static boolean isBindHolder(Object holder) {
        return holder instanceof BindsHomeMenu.Holder || holder instanceof BindEditMenu.Holder
                || holder instanceof RuneSelectorMenu.Holder || holder instanceof PresetMenu.Holder
                || holder instanceof PresetConfirmMenu.Holder;
    }
}
