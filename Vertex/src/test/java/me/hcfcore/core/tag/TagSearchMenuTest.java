package me.hcfcore.core.tag;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.UserManager;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.AnvilInventoryMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagSearchMenuTest {

    private ServerMock server;
    private PluginMock plugin;
    private TagManager tagManager;
    private Messages messages;
    private TagMenuListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        messages = new Messages(plugin, new UserManager(plugin, new NoOpStorage()));
        messages.load();
        // A fixture rather than the bundled tags.yml, whose tag list is
        // admin content that moves independently of this test.
        writeTagsYaml("""
                tags:
                  berserker:
                    display: "<gradient:#ff512f:#f09819>Berserker</gradient>"
                    permission: ''
                    created-at: 1000
                  frostbite:
                    display: "<gradient:#2980b9:#6dd5fa>Frostbite</gradient>"
                    permission: ''
                    created-at: 1000
                  venom:
                    display: "<gradient:#00b09b:#96c93d>Venom</gradient>"
                    permission: ''
                    created-at: 1000
                """);
        tagManager = new TagManager(plugin);
        tagManager.load();
        listener = new TagMenuListener(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void resultSlotIsFreeAndHoldsAClickableConfirmButton() {
        // Regression test: the anvil charged XP levels to take the renamed
        // result, so the slot was greyed out and there was no way to click
        // through and commit a search.
        PlayerMock player = server.addPlayer("Alice");
        TagSearchMenu.open(player, tagManager, messages, TagMenuState.initial());

        AnvilInventoryMock anvil = openAnvil(player);
        assertEquals(0, anvil.getRepairCost(), "searching is not a repair and must not cost levels");
        assertEquals(0, anvil.getMaximumRepairCost());
        assertNotNull(anvil.getItem(TagSearchMenu.SLOT_RESULT),
                "the result slot needs an item for the player to click");
    }

    @Test
    void clickingConfirmFiltersTheTagsMenuToTheTypedName() {
        PlayerMock player = server.addPlayer("Bob");
        TagSearchMenu.open(player, tagManager, messages, TagMenuState.initial());
        AnvilInventoryMock anvil = openAnvil(player);
        anvil.setRenameText("venom");

        clickResultSlot(player, anvil);

        TagMenu.Holder holder = openTagMenu(player);
        assertEquals("venom", holder.state().searchQuery());
        assertEquals(1, tagIconCount(holder.getInventory()), "only Venom should survive the filter");
    }

    @Test
    void searchMatchesTheVisibleNameRatherThanTheColorMarkup() {
        // Regression test: matching ran against the raw `display` string,
        // which for every bundled tag is "<gradient:#hex:#hex>Name</...>".
        // So markup queries matched everything and hex digits matched
        // tags whose names don't contain them at all.
        PlayerMock player = server.addPlayer("Carol");

        assertEquals(0, searchResultCount(player, "gradient"),
                "\"gradient\" is markup, not a tag name");
        assertEquals(0, searchResultCount(player, "ff512f"),
                "a hex color stop is markup, not a tag name");
        assertEquals(3, searchResultCount(player, ""), "an empty search filters nothing out");
        assertEquals(1, searchResultCount(player, "frost"), "a real name prefix still matches");
    }

    @Test
    void untouchedPromptIsNotTreatedAsASearch() {
        // Regression test: the anvil seeds its rename field from the input
        // item's display name, so a player who opened search and closed it
        // without typing read back as having searched for the prompt text
        // ("Type a tag name...") -- filtering every tag away.
        PlayerMock player = server.addPlayer("Dana");
        TagSearchMenu.open(player, tagManager, messages, TagMenuState.initial());
        AnvilInventoryMock anvil = openAnvil(player);
        anvil.setRenameText(messages.getRaw(player, "tags.search-prompt"));

        clickResultSlot(player, anvil);

        TagMenu.Holder holder = openTagMenu(player);
        assertNull(holder.state().searchQuery(), "the untouched placeholder is not a query");
        assertEquals(3, tagIconCount(holder.getInventory()), "every tag should still be listed");
    }

    @Test
    void confirmAppliesTheSearchExactlyOnce() {
        // Committing reopens the tags menu, which closes the anvil and
        // fires the close handler -- the same query must not be applied a
        // second time on top of it.
        PlayerMock player = server.addPlayer("Eve");
        TagSearchMenu.open(player, tagManager, messages, TagMenuState.initial());
        AnvilInventoryMock anvil = openAnvil(player);
        anvil.setRenameText("venom");
        TagSearchMenu.Holder searchHolder =
                assertInstanceOf(TagSearchMenu.Holder.class, anvil.getHolder());

        clickResultSlot(player, anvil);
        assertTrue(searchHolder.committed(), "the click should mark the search committed");

        // Simulate the close that reopening the tags menu causes.
        listener.onClose(new org.bukkit.event.inventory.InventoryCloseEvent(player.getOpenInventory()));
        ((BukkitSchedulerMock) server.getScheduler()).performTicks(2);

        TagMenu.Holder holder = openTagMenu(player);
        assertEquals("venom", holder.state().searchQuery());
    }

    /** Closing without clicking confirm still applies what was typed. */
    @Test
    void closingWithoutConfirmingStillAppliesTheTypedName() {
        PlayerMock player = server.addPlayer("Frank");
        TagSearchMenu.open(player, tagManager, messages, TagMenuState.initial());
        AnvilInventoryMock anvil = openAnvil(player);
        anvil.setRenameText("frostbite");

        listener.onClose(new org.bukkit.event.inventory.InventoryCloseEvent(player.getOpenInventory()));
        ((BukkitSchedulerMock) server.getScheduler()).performTicks(2);

        TagMenu.Holder holder = openTagMenu(player);
        assertEquals("frostbite", holder.state().searchQuery());
        assertEquals(1, tagIconCount(holder.getInventory()));
    }

    private int searchResultCount(PlayerMock player, String query) {
        TagMenu.open(player, tagManager, messages,
                new TagMenuState(TagManager.Sort.ALPHABETICAL, true, TagManager.Filter.ALL, 0,
                        query.isBlank() ? null : query));
        return tagIconCount(player.getOpenInventory().getTopInventory());
    }

    private static AnvilInventoryMock openAnvil(PlayerMock player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        assertEquals(InventoryType.ANVIL, top.getType());
        return assertInstanceOf(AnvilInventoryMock.class, top);
    }

    private void clickResultSlot(PlayerMock player, AnvilInventoryMock anvil) {
        listener.onClick(new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.RESULT, TagSearchMenu.SLOT_RESULT,
                ClickType.LEFT, InventoryAction.PICKUP_ALL));
        ((BukkitSchedulerMock) server.getScheduler()).performTicks(2);
    }

    private static TagMenu.Holder openTagMenu(PlayerMock player) {
        return assertInstanceOf(TagMenu.Holder.class,
                player.getOpenInventory().getTopInventory().getHolder(),
                "the player should be back in the tags menu");
    }

    /** How many tag icons the tags menu is currently showing. */
    private static int tagIconCount(Inventory inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (TagMenu.isTagSlot(slot) && item != null && item.getType() == Material.NAME_TAG) {
                count++;
            }
        }
        return count;
    }

    private void writeTagsYaml(String content) {
        try {
            File file = new File(plugin.getDataFolder(), "tags.yml");
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class NoOpStorage implements Storage {
        @Override
        public void init() {
        }

        @Override
        public Map<String, Long> loadCooldowns(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveCooldown(UUID uuid, String kitName, long availableAt) {
        }

        @Override
        public Map<String, Long> loadAbilityCooldowns(UUID uuid) {
            return Map.of();
        }

        @Override
        public void saveAbilityCooldown(UUID uuid, String abilityId, long availableAt) {
        }

        @Override
        public String loadLocale(UUID uuid) {
            return null;
        }

        @Override
        public void saveLocale(UUID uuid, String locale) {
        }
        @Override
        public void saveDeath(UUID uuid, me.hcfcore.core.staff.Death death) {
        }

        @Override
        public java.util.List<me.hcfcore.core.staff.Death> loadDeaths(UUID uuid, int limit) {
            return java.util.List.of();
        }

        @Override
        public void close() {
        }
    }
}
