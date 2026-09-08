package me.vertex.core.backpack;

import me.vertex.core.lang.Messages;
import me.vertex.core.staff.Death;
import me.vertex.core.storage.Storage;
import me.vertex.core.user.UserManager;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackpackManagerTest {

    private PluginMock plugin;
    private BackpackManager manager;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        UserManager userManager = new UserManager(plugin, new InMemoryStorage());
        Messages messages = new Messages(plugin, userManager);
        messages.load();
        manager = new BackpackManager(plugin, messages);
        manager.load();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void loadParsesBundledTiers() {
        assertTrue(manager.isEnabled());
        assertTrue(manager.tierIds().contains("t1_basic"));
        assertTrue(manager.tierIds().contains("t3_corrupted"));
        assertEquals(25.0, manager.getTier("t1_basic").dropBonusBasePercent());
        assertTrue(manager.getTier("t1_basic").dropBonusPerLevelPercent() >= 0.0);
    }

    @Test
    void createBackpackItemUsesConfiguredMaterialAndCustomModelData() {
        BackpackTier tier = manager.getTier("t1_basic");
        ItemStack item = manager.createBackpackItem(tier);

        assertEquals(tier.itemType(), item.getType());
        assertEquals(tier.customModelData(), item.getItemMeta().getCustomModelData());
    }

    @Test
    void actionBarValuesUseTheConfiguredNameAndReadableTier() {
        BackpackTier tier = manager.getTier("t1_basic");

        assertEquals("T1 Winter Backpack", manager.displayName(tier));
        assertEquals("T1 Basic", manager.tierLabel(tier));
    }

    @Test
    void itemCapacityCountsStackAmountsRatherThanOccupiedSlots() {
        assertEquals(1250L, manager.itemCapacityForLevel(1));
        assertEquals(1500L, manager.itemCapacityForLevel(2));
        assertEquals(64L, BackpackManager.storedItemCount(new ItemStack[] {new ItemStack(Material.DIAMOND, 64)}));
        assertEquals(65L, BackpackManager.storedItemCount(new ItemStack[] {
                new ItemStack(Material.DIAMOND, 64), new ItemStack(Material.DIRT, 1)}));
    }

    @Test
    void storeAutoCollectedAcceptsMoreDistinctMaterialsThanTheOldFortyFiveSlotCeiling() {
        // Regression test: storage used to be a fixed-size ItemStack[]
        // sized like a Bukkit inventory (at most 45 slots), so a player
        // mining many different ore types could exhaust that array long
        // before hitting the advertised item-count capacity -- the rest
        // silently landed on the ground instead of in the backpack. There
        // is no slot array anymore, so this must all fit.
        BackpackTier tier = manager.getTier("t1_basic");
        ItemStack item = manager.createBackpackItem(tier);
        BackpackManager.EquippedBackpack equipped = new BackpackManager.EquippedBackpack(item, tier, manager.readData(item));

        List<ItemStack> drops = new java.util.ArrayList<>();
        int distinctTypes = 60; // more than the old 45-slot ceiling
        for (Material material : Material.values()) {
            if (!material.isItem() || material.isAir()) {
                continue;
            }
            drops.add(new ItemStack(material, 1));
            if (drops.size() >= distinctTypes) {
                break;
            }
        }

        List<ItemStack> leftovers = manager.storeAutoCollected(equipped, drops);

        assertEquals(List.of(), leftovers, "every distinct material should fit -- capacity is items, not slots");
        BackpackData stored = manager.readData(item);
        assertEquals(distinctTypes, stored.contents().length, "one entry per distinct material, unbounded by slot count");
    }

    @Test
    void storeAutoCollectedMergesRepeatedDropsOfTheSameMaterialPastVanillaMaxStack() {
        BackpackTier tier = manager.getTier("t1_basic");
        ItemStack item = manager.createBackpackItem(tier);

        // 5 separate mining events, 60 coal each. The shipped base bonus
        // is a guaranteed 25%, so each event stores 75 -- well past a
        // single vanilla stack's
        // 64-item cap once merged. Re-reads data each time since
        // storeAutoCollected persists onto the item itself.
        for (int i = 0; i < 5; i++) {
            BackpackManager.EquippedBackpack equipped =
                    new BackpackManager.EquippedBackpack(item, tier, manager.readData(item));
            manager.storeAutoCollected(equipped, List.of(new ItemStack(Material.COAL, 60)));
        }

        BackpackData stored = manager.readData(item);
        assertEquals(1, stored.contents().length, "same material merges into one entry, not one per drop");
        // 5 x 60 coal, boosted 25% each time (60 -> 75) -- well past 64.
        assertEquals(5 * 75, stored.contents()[0].getAmount(), "an entry's amount isn't capped at 64");
    }

    @Test
    void storeAutoCollectedDropsOnlyTheExcessOnceCapacityIsReached() {
        BackpackTier tier = manager.getTier("t1_basic");
        ItemStack item = manager.createBackpackItem(tier);
        long capacity = manager.itemCapacityForLevel(1);
        BackpackManager.EquippedBackpack equipped =
                new BackpackManager.EquippedBackpack(item, tier, manager.readData(item));

        // The shipped t1 bonus is a deterministic 25% at level one.
        int requested = 2000;
        long boosted = requested + requested * 25L / 100L;
        List<ItemStack> leftovers = manager.storeAutoCollected(equipped,
                List.of(new ItemStack(Material.COBBLESTONE, requested)));

        BackpackData stored = manager.readData(item);
        long leftoverTotal = leftovers.stream().mapToLong(ItemStack::getAmount).sum();
        assertEquals(capacity, BackpackManager.storedItemCount(stored.contents()));
        assertEquals(boosted - capacity, leftoverTotal, "nothing is lost -- excess comes back as leftovers to drop");
        for (ItemStack leftover : leftovers) {
            assertTrue(leftover.getAmount() <= leftover.getMaxStackSize(),
                    "leftovers must be real, vanilla-sized stacks for dropping on the ground");
        }
    }

    @Test
    void configuredOresAndMobDropsAreEnabledForAutoCollection() {
        assertTrue(manager.autoStoresMining(Material.DIAMOND_ORE));
        assertFalse(manager.autoStoresMining(Material.STONE));
        assertTrue(manager.autoStoresMobDrops());
    }

    @Test
    void plainItemIsNotABackpack() {
        assertFalse(manager.isBackpack(new ItemStack(Material.DIRT)));
        assertNull(manager.readData(new ItemStack(Material.DIRT)));
    }

    @Test
    void writeThenReadRoundTripsTierAndLevelWithoutXp() {
        ItemStack item = new ItemStack(Material.BUNDLE);
        // All-null contents (no distinct materials stored yet) -- there is
        // no fixed slot count to preserve, so this compacts to empty.
        BackpackData data = new BackpackData("t1_basic", 3, new ItemStack[18]);

        manager.writeData(item, data);

        assertTrue(manager.isBackpack(item));
        BackpackData read = manager.readData(item);
        assertEquals("t1_basic", read.tierId());
        assertEquals(3, read.level());
        assertEquals(0, read.contents().length);
    }

    @Test
    void writeDataRemovesTheRetiredXpTag() {
        ItemStack item = new ItemStack(Material.BUNDLE);
        NamespacedKey legacyKey = new NamespacedKey(plugin, "backpack_xp");
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(legacyKey, PersistentDataType.LONG, 999_999L);
        item.setItemMeta(meta);

        manager.writeData(item, new BackpackData("t1_basic", 1, new ItemStack[9]));

        assertFalse(item.getItemMeta().getPersistentDataContainer().has(legacyKey, PersistentDataType.LONG));
    }

    @Test
    void writeDataRemovesTheLegacyExpirationTag() {
        ItemStack item = new ItemStack(Material.BUNDLE);
        NamespacedKey legacyKey = new NamespacedKey(plugin, "backpack_expires_at");
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(legacyKey, PersistentDataType.LONG, 1L);
        item.setItemMeta(meta);

        manager.writeData(item, new BackpackData("t1_basic", 1, new ItemStack[9]));

        assertFalse(item.getItemMeta().getPersistentDataContainer().has(legacyKey, PersistentDataType.LONG));
    }

    @Test
    void eachWrittenBackpackGetsItsOwnNonStackingIdentity() {
        ItemStack first = new ItemStack(Material.BUNDLE);
        ItemStack second = new ItemStack(Material.BUNDLE);
        BackpackData data = new BackpackData("t1_basic", 1, new ItemStack[18]);

        manager.writeData(first, data);
        manager.writeData(second, data);

        assertTrue(manager.isSingleBackpack(first));
        assertTrue(manager.isSingleBackpack(second));
        assertFalse(manager.isSameInstance(first, second));

        first.setAmount(2);
        assertFalse(manager.isSingleBackpack(first));
    }

    @Test
    void readDataClampsTheLevelAndCompactsNullEntries() {
        ItemStack item = new ItemStack(Material.BUNDLE);
        ItemStack[] sparseContents = new ItemStack[54];
        sparseContents[40] = new ItemStack(Material.DIAMOND);

        // No configured upper level cap -- 999 is a legitimate level.
        manager.writeData(item, new BackpackData("t1_basic", 999, sparseContents));
        BackpackData read = manager.readData(item);

        assertEquals(999, read.level());
        // 53 null placeholders are dropped; only the one real entry remains
        // -- there's no fixed slot array to preserve the length of.
        assertEquals(1, read.contents().length);
        assertEquals(Material.DIAMOND, read.contents()[0].getType());
    }

    @Test
    void writeDataStoresNonEmptyContentsAcrossTheRoundTrip() {
        ItemStack item = new ItemStack(Material.BUNDLE);
        // Null entries mixed in among the real one -- readData compacts
        // these out entirely rather than preserving array position, since
        // there's no fixed slot layout to preserve.
        ItemStack[] contents = new ItemStack[9];
        contents[2] = new ItemStack(Material.DIAMOND, 5);
        BackpackData data = new BackpackData("t1_basic", 1, contents);

        manager.writeData(item, data);
        BackpackData read = manager.readData(item);

        assertEquals(1, read.contents().length);
        assertEquals(Material.DIAMOND, read.contents()[0].getType());
        assertEquals(5, read.contents()[0].getAmount());
    }

    @Test
    void readDataReturnsNullWhenTheTierNoLongerExistsInConfig() {
        ItemStack item = new ItemStack(Material.BUNDLE);
        manager.writeData(item, new BackpackData("t1_basic", 1, new ItemStack[9]));

        BackpackManager freshManagerWithoutT1 = managerWithNoTiers();
        assertNull(freshManagerWithoutT1.readData(item));
    }

    private BackpackManager managerWithNoTiers() {
        UserManager userManager = new UserManager(plugin, new InMemoryStorage());
        Messages messages = new Messages(plugin, userManager);
        messages.load();
        // Never calling load() leaves the tiers map empty, simulating a
        // tier having been removed from backpacks.yml since the item was made.
        return new BackpackManager(plugin, messages);
    }

    @Test
    void upgradeCostHasNoTierConfiguredLevelCap() {
        BackpackTier tier = manager.getTier("t1_basic");
        assertTrue(manager.upgradeCost(tier, 1) >= 0);
        assertTrue(manager.upgradeCost(tier, 999_999) >= 0);
    }

    @Test
    void storingAndEmptyingContentsImmediatelyRebuildsTheLoreCount() {
        BackpackTier tier = manager.getTier("t1_basic");
        ItemStack item = manager.createBackpackItem(tier);
        BackpackManager.EquippedBackpack equipped =
                new BackpackManager.EquippedBackpack(item, tier, manager.readData(item));

        // 12, not 10: 25% of it is a whole number (3), so the boosted total
        // is deterministic instead of depending on applyBonus's fractional
        // random-extra-item roll.
        manager.storeAutoCollected(equipped, List.of(new ItemStack(Material.DIAMOND, 12)));

        String storedLore = plainLore(item);
        assertTrue(storedLore.contains("15/1,250"),
                "the lore must reflect the boosted number stored immediately");

        BackpackData data = manager.readData(item);
        manager.writeData(item, data.withContents(new ItemStack[0]));

        assertTrue(plainLore(item).contains("0/1,250"),
                "the lore must reflect an emptied Backpack immediately");
    }

    private static String plainLore(ItemStack item) {
        return item.getItemMeta().lore().stream()
                .map(component -> PlainTextComponentSerializer.plainText().serialize(component))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static final class InMemoryStorage implements Storage {
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
        public void saveDeath(UUID uuid, Death death) {
        }

        @Override
        public List<Death> loadDeaths(UUID uuid, int limit) {
            return List.of();
        }

        @Override
        public void close() {
        }
    }
}
