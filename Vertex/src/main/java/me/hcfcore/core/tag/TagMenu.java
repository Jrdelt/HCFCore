package me.hcfcore.core.tag;

import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TagMenu {

    static final int PAGE_SIZE = 45;
    static final int SLOT_FILTER = 45;
    static final int SLOT_SORT = 46;
    static final int SLOT_SEARCH = 47;
    static final int SLOT_PREV_PAGE = 48;
    static final int SLOT_NICKNAME = 49;
    static final int SLOT_NEXT_PAGE = 50;
    static final int SLOT_PAGE_INDICATOR = 52;
    static final int SLOT_CLOSE = 53;

    private TagMenu() {
    }

    public static void open(Player player, TagManager manager, Messages messages, TagMenuState requestedState) {
        List<TagManager.Tag> visible = visibleTags(player, manager, requestedState);
        int totalPages = Math.max(1, (int) Math.ceil(visible.size() / (double) PAGE_SIZE));
        int page = Math.max(0, Math.min(requestedState.page(), totalPages - 1));
        TagMenuState state = requestedState.withPage(page);

        Holder holder = new Holder(manager, messages, state);
        Component title = messages.get(player, titleKey(state.filter()), "count", String.valueOf(visible.size()));
        Inventory inventory = Bukkit.createInventory(holder, 54, title);
        holder.inventory = inventory;

        int start = page * PAGE_SIZE;
        int end = Math.min(visible.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            inventory.setItem(i - start, tagIcon(player, manager, messages, visible.get(i)));
        }

        inventory.setItem(SLOT_FILTER, filterButton(player, manager, messages, state));
        inventory.setItem(SLOT_SORT, sortButton(player, messages, state));
        inventory.setItem(SLOT_SEARCH, searchButton(player, messages, state));
        inventory.setItem(SLOT_PREV_PAGE, pageButton(messages, player, "tags.previous-page",
                Material.RED_DYE, page > 0));
        inventory.setItem(SLOT_NICKNAME, nicknameButton(player, manager, messages));
        inventory.setItem(SLOT_NEXT_PAGE, pageButton(messages, player, "tags.next-page",
                Material.LIME_DYE, page < totalPages - 1));
        inventory.setItem(SLOT_PAGE_INDICATOR, plainButton(Material.PAPER,
                messages.get(player, "tags.page-indicator", "page", String.valueOf(page + 1),
                        "total", String.valueOf(totalPages))));
        inventory.setItem(SLOT_CLOSE, plainButton(Material.BARRIER, messages.get(player, "tags.close")));

        player.openInventory(inventory);
    }

    private static List<TagManager.Tag> visibleTags(Player player, TagManager manager, TagMenuState state) {
        List<TagManager.Tag> sorted = manager.getSorted(state.sort(), state.ascending());
        return sorted.stream()
                .filter(tag -> matchesFilter(player, manager, tag, state.filter()))
                .filter(tag -> matchesSearch(tag, state.searchQuery()))
                .toList();
    }

    private static boolean matchesFilter(Player player, TagManager manager, TagManager.Tag tag, TagManager.Filter filter) {
        return switch (filter) {
            case YOUR -> manager.isUnlocked(player, tag);
            case UNOWNED -> !manager.isUnlocked(player, tag);
            case ALL -> true;
        };
    }

    private static boolean matchesSearch(TagManager.Tag tag, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return tag.display().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static String titleKey(TagManager.Filter filter) {
        return switch (filter) {
            case YOUR -> "tags.title-your";
            case UNOWNED -> "tags.title-unowned";
            case ALL -> "tags.title-all";
        };
    }

    private static ItemStack tagIcon(Player player, TagManager manager, Messages messages, TagManager.Tag tag) {
        boolean unlocked = manager.isUnlocked(player, tag);
        ItemStack item = new ItemStack(unlocked ? Material.NAME_TAG : Material.IRON_BARS);
        ItemMeta meta = item.getItemMeta();

        String color = tag.color() != null && !tag.color().isBlank() ? tag.color() : "<white>";
        meta.displayName(MessageFormatter.deserialize((unlocked ? "" : "<gray>") + color + tag.display()));

        boolean equipped = tag.id().equalsIgnoreCase(manager.getPlayerTag(player.getUniqueId()));
        List<Component> lore = new ArrayList<>();
        lore.add(messages.get(player, equipped ? "tags.equipped" : "tags.unequipped"));
        lore.add(Component.text("Rarity: " + tag.rarity(), NamedTextColor.GRAY));
        lore.add(Component.text("Players: " + manager.playerCount(tag.id()), NamedTextColor.GRAY));
        lore.add(Component.text("Created: " + TagManager.formatMonth(tag.createdAt()), NamedTextColor.GRAY));
        lore.add(Component.text("Uses: " + manager.uses(tag.id()), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(messages.get(player, unlocked ? "tags.click-select" : "tags.locked"));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(new NamespacedKey(manager.plugin(), "tag_id"),
                PersistentDataType.STRING, tag.id());
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filterButton(Player player, TagManager manager, Messages messages, TagMenuState state) {
        List<Component> lore = new ArrayList<>();
        lore.add(messages.get(player, "tags.filter-heading", "filter",
                messages.getRaw(player, filterLabelKey(state.filter()))));
        lore.add(Component.empty());
        for (TagManager.Filter option : TagManager.Filter.values()) {
            long count = countFor(player, manager, option);
            Component label = messages.get(player, filterLabelKey(option))
                    .append(Component.text(" (" + count + ")"));
            NamedTextColor color = option == state.filter() ? NamedTextColor.GREEN : NamedTextColor.GRAY;
            lore.add(Component.text(option == state.filter() ? "→ " : "  ", color).append(label.color(color)));
        }
        lore.add(Component.empty());
        lore.add(messages.get(player, "tags.filter-hint"));
        return button(Material.HOPPER, Component.text("Filter", NamedTextColor.AQUA), lore);
    }

    private static String filterLabelKey(TagManager.Filter filter) {
        return switch (filter) {
            case YOUR -> "tags.filter-label-your";
            case UNOWNED -> "tags.filter-label-unowned";
            case ALL -> "tags.filter-label-all";
        };
    }

    private static long countFor(Player player, TagManager manager, TagManager.Filter filter) {
        return manager.getSorted(TagManager.Sort.ALPHABETICAL, true).stream()
                .filter(tag -> matchesFilter(player, manager, tag, filter))
                .count();
    }

    private static ItemStack sortButton(Player player, Messages messages, TagMenuState state) {
        List<Component> lore = new ArrayList<>();
        lore.add(messages.get(player, "tags.sort-heading", "sort", messages.getRaw(player, sortLabelKey(state.sort()))));
        lore.add(Component.empty());
        for (TagManager.Sort option : TagManager.Sort.values()) {
            NamedTextColor color = option == state.sort() ? NamedTextColor.GREEN : NamedTextColor.GRAY;
            lore.add(Component.text(option == state.sort() ? "→ " : "  ", color)
                    .append(messages.get(player, sortLabelKey(option)).color(color)));
        }
        lore.add(Component.empty());
        lore.add(messages.get(player, "tags.sort-hint-cycle"));
        lore.add(messages.get(player, "tags.sort-hint-direction"));
        return button(Material.HOPPER, Component.text("Sort", NamedTextColor.AQUA), lore);
    }

    private static String sortLabelKey(TagManager.Sort sort) {
        return switch (sort) {
            case ALPHABETICAL -> "tags.sort-alphabetical";
            case AGE -> "tags.sort-age";
            case RARITY -> "tags.sort-rarity";
        };
    }

    private static ItemStack searchButton(Player player, Messages messages, TagMenuState state) {
        List<Component> lore = new ArrayList<>();
        lore.add(messages.get(player, "tags.search-heading"));
        lore.add(Component.empty());
        if (state.searchQuery() != null && !state.searchQuery().isBlank()) {
            lore.add(Component.text("\"" + state.searchQuery() + "\"", NamedTextColor.WHITE));
            lore.add(Component.empty());
        }
        lore.add(messages.get(player, "tags.search-hint-left"));
        lore.add(messages.get(player, "tags.search-hint-right"));
        return button(Material.COMPASS, Component.text("Search", NamedTextColor.GOLD), lore);
    }

    private static ItemStack pageButton(Messages messages, Player player, String key, Material material, boolean enabled) {
        ItemStack item = new ItemStack(enabled ? material : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.get(player, key));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack nicknameButton(Player player, TagManager manager, Messages messages) {
        List<Component> lore = new ArrayList<>();
        lore.add(messages.get(player, "tags.nickname-heading"));
        lore.add(Component.empty());
        boolean enabled = manager.isNicknameMatchEnabled(player.getUniqueId());
        lore.add(messages.get(player, enabled ? "tags.nickname-status-on" : "tags.nickname-status-off"));
        lore.add(Component.empty());

        String tagId = manager.getPlayerTag(player.getUniqueId());
        TagManager.Tag equippedTag = tagId == null ? null : manager.get(tagId);
        String color = equippedTag != null && equippedTag.color() != null && !equippedTag.color().isBlank()
                ? equippedTag.color() : null;
        if (color != null) {
            String sample = messages.getRaw(player, "tags.nickname-sample-message");
            lore.add(messages.get(player, "tags.nickname-preview-heading"));
            lore.add(MessageFormatter.deserialize(color + player.getName()
                    + "<gray>: " + sample));
            if (color.contains("gradient")) {
                lore.add(MessageFormatter.deserialize(GradientColor.reverse(color) + player.getName()
                        + "<gray>: " + sample)
                        .append(messages.get(player, "tags.nickname-reversed-suffix")));
            }
            lore.add(Component.empty());
        }
        lore.add(messages.get(player, "tags.nickname-hint-toggle"));
        if (color != null && color.contains("gradient")) {
            lore.add(messages.get(player, "tags.nickname-hint-reverse"));
        }
        return button(Material.NAME_TAG, Component.text("Nickname Match", NamedTextColor.LIGHT_PURPLE), lore);
    }

    private static ItemStack plainButton(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        item.setItemMeta(meta);
        return item;
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
