package me.hcfcore.core.tag;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class TagMenuListener implements Listener {

    private final Plugin plugin;

    public TagMenuListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Object holder = event.getInventory().getHolder();
        if (holder instanceof TagMenu.Holder || holder instanceof TagSearchMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Object rawHolder = event.getInventory().getHolder();
        if (rawHolder instanceof TagSearchMenu.Holder) {
            event.setCancelled(true);
            return;
        }
        if (!(rawHolder instanceof TagMenu.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        handleClick(player, event, holder);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof TagSearchMenu.Holder holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        String query = TagSearchMenu.readQuery(event.getInventory());
        TagMenuState nextState = holder.state().withSearchQuery(query == null || query.isBlank() ? null : query.trim());
        Bukkit.getScheduler().runTask(plugin, () -> TagMenu.open(player, holder.manager(), holder.messages(), nextState));
    }

    private void handleClick(Player player, InventoryClickEvent event, TagMenu.Holder holder) {
        TagMenuState state = holder.state();
        TagManager manager = holder.manager();
        int slot = event.getRawSlot();

        if (TagMenu.isTagSlot(slot)) {
            selectTag(player, manager, event.getCurrentItem());
            return;
        }

        switch (slot) {
            case TagMenu.SLOT_FILTER -> {
                TagManager.Filter next = switch (state.filter()) {
                    case YOUR -> TagManager.Filter.UNOWNED;
                    case UNOWNED -> TagManager.Filter.ALL;
                    case ALL -> TagManager.Filter.YOUR;
                };
                TagMenu.open(player, manager, holder.messages(), state.withFilter(next));
            }
            case TagMenu.SLOT_SORT -> {
                if (event.getClick().isShiftClick()) {
                    TagMenu.open(player, manager, holder.messages(), state.withAscending(!state.ascending()));
                } else {
                    TagManager.Sort next = switch (state.sort()) {
                        case ALPHABETICAL -> TagManager.Sort.AGE;
                        case AGE -> TagManager.Sort.ALPHABETICAL;
                    };
                    TagMenu.open(player, manager, holder.messages(), state.withSort(next));
                }
            }
            case TagMenu.SLOT_SEARCH -> {
                if (event.isRightClick()) {
                    TagMenu.open(player, manager, holder.messages(), state.withSearchQuery(null));
                } else {
                    TagSearchMenu.open(player, manager, holder.messages(), state);
                }
            }
            case TagMenu.SLOT_PREV_PAGE -> {
                if (state.page() > 0) {
                    TagMenu.open(player, manager, holder.messages(), state.withPage(state.page() - 1));
                }
            }
            case TagMenu.SLOT_NEXT_PAGE -> TagMenu.open(player, manager, holder.messages(), state.withPage(state.page() + 1));
            case TagMenu.SLOT_NICKNAME -> {
                if (event.getClick().isShiftClick()) {
                    manager.setNicknameReversed(player.getUniqueId(), !manager.isNicknameReversed(player.getUniqueId()));
                } else {
                    manager.setNicknameMatchEnabled(player.getUniqueId(), !manager.isNicknameMatchEnabled(player.getUniqueId()));
                }
                TagMenu.open(player, manager, holder.messages(), state);
            }
            case TagMenu.SLOT_CLOSE -> player.closeInventory();
            default -> {
            }
        }
    }

    private void selectTag(Player player, TagManager manager, ItemStack clicked) {
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String tagId = clicked.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(manager.plugin(), "tag_id"), PersistentDataType.STRING);
        TagManager.Tag tag = tagId == null ? null : manager.get(tagId);
        if (tag == null || !manager.isUnlocked(player, tag)) {
            return;
        }
        boolean alreadyEquipped = tag.id().equalsIgnoreCase(manager.getPlayerTag(player.getUniqueId()));
        if (alreadyEquipped) {
            manager.unselect(player.getUniqueId());
        } else {
            manager.select(player.getUniqueId(), tag.id());
        }
        player.closeInventory();
    }
}
