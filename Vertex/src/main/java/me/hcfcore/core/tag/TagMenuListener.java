package me.hcfcore.core.tag;

import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.Inventory;
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

    /**
     * Keeps the search anvil's result slot free and up to date as the
     * player types. Renaming in an anvil normally costs XP levels, which
     * greys the result out and leaves no way to click through and commit
     * the search.
     */
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getInventory().getHolder() instanceof TagSearchMenu.Holder holder)) {
            return;
        }
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        event.getView().setRepairCost(0);
        event.getView().setMaximumRepairCost(0);
        event.setResult(TagSearchMenu.confirmButton(player, holder.messages(),
                TagSearchMenu.readQuery(player, holder.messages(), event.getInventory())));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Object rawHolder = event.getInventory().getHolder();
        if (rawHolder instanceof TagSearchMenu.Holder searchHolder) {
            // Cancelled regardless: the confirm button is a button, never
            // an item the player takes out of the anvil.
            event.setCancelled(true);
            if (event.getRawSlot() == TagSearchMenu.SLOT_RESULT
                    && event.getWhoClicked() instanceof Player player) {
                commitSearch(player, event.getInventory(), searchHolder);
            }
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
        // Clicking confirm already applied the search and is reopening the
        // tags menu, which is what closed this one -- don't apply it twice.
        if (holder.committed()) {
            return;
        }
        commitSearch(player, event.getInventory(), holder);
    }

    /**
     * Applies whatever is currently typed in the search anvil and returns
     * the player to the tags menu, filtered to it.
     */
    private void commitSearch(Player player, Inventory inventory, TagSearchMenu.Holder holder) {
        holder.markCommitted();
        String query = TagSearchMenu.readQuery(player, holder.messages(), inventory);
        TagMenuState nextState = holder.state().withSearchQuery(query);
        Bukkit.getScheduler().runTask(plugin, () -> TagMenu.open(player, holder.manager(), holder.messages(), nextState));
    }

    private void handleClick(Player player, InventoryClickEvent event, TagMenu.Holder holder) {
        TagMenuState state = holder.state();
        TagManager manager = holder.manager();
        int slot = event.getRawSlot();

        if (TagMenu.isTagSlot(slot)) {
            selectTag(player, manager, holder.messages(), event.getCurrentItem());
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
                        case AGE -> TagManager.Sort.RARITY;
                        case RARITY -> TagManager.Sort.ALPHABETICAL;
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
            default -> {
            }
        }
    }

    private void selectTag(Player player, TagManager manager, Messages messages, ItemStack clicked) {
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
            player.sendMessage(equipStatusMessage(messages, player, tag, "tags.unequipped"));
        } else {
            manager.select(player.getUniqueId(), tag.id());
            player.sendMessage(equipStatusMessage(messages, player, tag, "tags.equipped"));
        }
        player.closeInventory();
    }

    /**
     * `tag.display()` is admin-authored (tags.yml) and already carries its
     * own color/gradient, so this is built by concatenating it directly
     * with the raw (undeserialized) status template rather than through
     * Messages' escaped-placeholder substitution -- that escaping exists
     * to guard untrusted values, and would otherwise render the tag's own
     * color tags as literal text.
     */
    private static Component equipStatusMessage(Messages messages, Player player, TagManager.Tag tag, String statusKey) {
        return MessageFormatter.deserialize(tag.display() + " " + messages.getRaw(player, statusKey));
    }
}
