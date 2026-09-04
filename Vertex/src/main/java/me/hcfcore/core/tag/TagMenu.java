package me.hcfcore.core.tag;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.luckperms.LuckPermsHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.Configuration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TagMenu {

    static final int GRID_ROWS = 4;
    static final int GRID_COLUMNS = 9;
    static final int PAGE_SIZE = GRID_ROWS * GRID_COLUMNS;
    static final int SLOT_NICKNAME = 4;
    static final int SLOT_FILTER = 45;
    static final int SLOT_PREV_PAGE = 48;
    static final int SLOT_SEARCH = 49;
    static final int SLOT_NEXT_PAGE = 50;
    static final int SLOT_SORT = 53;

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
            inventory.setItem(tagSlot(i - start), tagIcon(player, manager, messages, visible.get(i)));
        }

        inventory.setItem(SLOT_FILTER, filterButton(player, manager, messages, state));
        inventory.setItem(SLOT_SORT, sortButton(player, messages, state));
        inventory.setItem(SLOT_SEARCH, searchButton(player, messages, state));
        inventory.setItem(SLOT_PREV_PAGE, pageButton(messages, player, "tags.previous-page",
                Material.RED_DYE, page > 0));
        inventory.setItem(SLOT_NICKNAME, nicknameButton(player, manager, messages));
        inventory.setItem(SLOT_NEXT_PAGE, pageButton(messages, player, "tags.next-page",
                Material.LIME_DYE, page < totalPages - 1));

        player.openInventory(inventory);
    }

    /**
     * Tags fill the full width of rows 1-4 (rows 2-5 as a player counts
     * them) -- row 0 is the header (nickname-match button) and row 5 is
     * the control row, but nothing insets the columns.
     */
    private static int tagSlot(int index) {
        int row = index / GRID_COLUMNS;
        int column = index % GRID_COLUMNS;
        return (row + 1) * 9 + column;
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

    /**
     * Matches on the tag's visible name (and its id), not on its raw
     * `display` string. Every bundled tag is authored as
     * "&lt;gradient:#hex:#hex&gt;Name&lt;/gradient&gt;", so searching the raw
     * string matched the markup instead of the name -- "a" or "f" hit the
     * hex digits of every tag, "grad" hit all of them, and a query could
     * never be trusted to mean the name the player was reading.
     */
    private static boolean matchesSearch(TagManager.Tag tag, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return MessageFormatter.plain(tag.display()).toLowerCase(Locale.ROOT).contains(needle)
                || tag.id().toLowerCase(Locale.ROOT).contains(needle);
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
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(noItalic(MessageFormatter.deserialize(tag.display())));

        boolean equipped = tag.id().equalsIgnoreCase(manager.getPlayerTag(player.getUniqueId()));
        List<Component> lore = new ArrayList<>();
        lore.add(messages.get(player, equipped ? "tags.equipped" : "tags.unequipped"));
        if (tag.lore() != null && !tag.lore().isEmpty()) {
            lore.add(Component.empty());
            for (String line : tag.lore()) {
                lore.add(MessageFormatter.deserialize(line));
            }
        }
        lore.add(Component.empty());
        lore.add(messages.get(player, "tags.info-created", "date", TagManager.formatCreated(tag.createdAt())));
        lore.add(messages.get(player, "tags.info-owners", "count", String.valueOf(manager.owners(tag.id()))));
        lore.add(Component.empty());
        String hintKey = !unlocked ? "tags.locked" : (equipped ? "tags.click-unselect" : "tags.click-select");
        lore.add(messages.get(player, hintKey));
        meta.lore(lore.stream().map(TagMenu::noItalic).toList());
        meta.getPersistentDataContainer().set(new NamespacedKey(manager.plugin(), "tag_id"),
                PersistentDataType.STRING, tag.id());
        item.setItemMeta(meta);
        return item;
    }

    /**
     * The inverse of tagSlot(): whether a raw inventory slot falls inside
     * the inset tag grid (rather than the border or the control row), for
     * the click listener to tell tag clicks apart from everything else.
     */
    static boolean isTagSlot(int slot) {
        if (slot < 0 || slot > 53) {
            return false;
        }
        int row = slot / 9;
        return row >= 1 && row <= GRID_ROWS;
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
        return button(Material.CLOCK, Component.text("Sort", NamedTextColor.AQUA), lore);
    }

    private static String sortLabelKey(TagManager.Sort sort) {
        return switch (sort) {
            case ALPHABETICAL -> "tags.sort-alphabetical";
            case AGE -> "tags.sort-age";
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
        meta.displayName(noItalic(messages.get(player, key)));
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
        String color = equippedTag == null ? null : GradientColor.extractLeadingColor(equippedTag.display());
        if (color != null) {
            Component chatPrefix = chatPreviewPrefix(player, manager, equippedTag);
            String sample = messages.getRaw(player, "tags.nickname-sample-message");
            lore.add(messages.get(player, "tags.nickname-preview-heading"));
            lore.add(chatPrefix.append(MessageFormatter.deserialize(color + player.getName()
                    + "<gray>: " + sample)));
            if (color.contains("gradient")) {
                lore.add(chatPrefix.append(MessageFormatter.deserialize(GradientColor.reverse(color) + player.getName()
                        + "<gray>: " + sample))
                        .append(messages.get(player, "tags.nickname-reversed-suffix")));
            }
            lore.add(Component.empty());
        }
        lore.add(messages.get(player, "tags.nickname-hint-toggle"));
        if (color != null && color.contains("gradient")) {
            lore.add(messages.get(player, "tags.nickname-hint-reverse"));
        }
        return skullButton(player, Component.text("Nickname Match", NamedTextColor.LIGHT_PURPLE), lore);
    }

    /**
     * The faction/tag/rank part of the real chat line, ahead of the name --
     * built the same way {@code ChatFormatterListener} does, so this
     * preview is exactly what the player's chat will actually look like,
     * not a fixed illustrative example.
     */
    private static Component chatPreviewPrefix(Player player, TagManager manager, TagManager.Tag equippedTag) {
        Configuration config = manager.plugin().getConfig();
        Component result = Component.empty();

        String faction = FactionsHook.getFactionTag(player);
        if (!"None".equalsIgnoreCase(faction)) {
            String template = config.getString("chat.faction-format", "<gold>[{faction}]</gold> ");
            result = result.append(MessageFormatter.deserialize(
                    template.replace("{faction}", MiniMessage.miniMessage().escapeTags(faction))));
        }

        if (equippedTag != null) {
            result = result.append(MessageFormatter.deserialize(equippedTag.display() + " "));
        }

        String rank = LuckPermsHook.getPrimaryGroupDisplayName(player);
        String prefix = LuckPermsHook.getPrefix(player);
        if ((rank != null && !rank.isBlank()) || prefix != null) {
            String template = config.getString("chat.rank-format", "<light_purple>[{rank}]</light_purple> ")
                    .replace("{prefix}", prefix == null ? "" : prefix)
                    .replace("{rank}", MiniMessage.miniMessage().escapeTags(rank == null ? "" : rank));
            result = result.append(MessageFormatter.deserialize(template));
        }

        return result;
    }

    private static ItemStack skullButton(Player owner, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(owner);
        meta.displayName(noItalic(name));
        meta.lore(lore.stream().map(TagMenu::noItalic).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(name));
        meta.lore(lore.stream().map(TagMenu::noItalic).toList());
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Item display names and lore render italic by default when their
     * italic decoration is unset -- explicitly turning it off here (once,
     * centrally) keeps every button/lore line in this menu upright.
     */
    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
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
