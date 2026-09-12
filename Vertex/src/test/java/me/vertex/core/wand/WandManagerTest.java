package me.vertex.core.wand;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WandManagerTest {

    private PluginMock plugin;
    private WandManager wands;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        wands = new WandManager(plugin);
        wands.load();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void loadsTheShippedTiers() {
        assertNotNull(wands.tier("iron"));
        assertNotNull(wands.tier("gold"));
        assertNotNull(wands.tier("diamond"));
        assertNotNull(wands.tier("tnt"));
        assertEquals(WandType.SELL, wands.tier("iron").type());
        assertEquals(WandType.TNT, wands.tier("tnt").type());
    }

    @Test
    void recognisesItsOwnWandsAndNothingElse() {
        ItemStack wand = wands.createWand(wands.tier("gold"));
        assertEquals("gold", wands.tierOf(wand).id());
        assertNull(wands.tierOf(new ItemStack(Material.GOLDEN_HOE)), "a plain hoe is not a wand");
        assertNull(wands.tierOf(null));
    }

    /** Uses live on the item, so they survive anything the item survives. */
    @Test
    void tracksUsesOnTheItem() {
        WandTier tier = wands.tier("iron");
        ItemStack wand = wands.createWand(tier, 3);
        assertEquals(3, wands.usesLeft(wand));

        assertFalse(wands.consumeUse(wand, tier));
        assertEquals(2, wands.usesLeft(wand));
        assertFalse(wands.consumeUse(wand, tier));
        assertTrue(wands.consumeUse(wand, tier), "spending the last use should report the wand as finished");
        assertEquals(0, wands.usesLeft(wand));
    }

    @Test
    void showsRemainingUsesInTheLore() {
        WandTier tier = wands.tier("iron");
        ItemStack wand = wands.createWand(tier, 7);
        assertTrue(wands.tierOf(wand) != null);

        String lore = String.valueOf(wand.getItemMeta().lore());
        assertTrue(lore.contains("7"), "the lore should show the remaining uses");
    }

    @Test
    void sellsPlainStacks() {
        assertTrue(wands.isSellable(new ItemStack(Material.COBBLESTONE, 64)));
    }

    /**
     * The important one: a named or enchanted item merely sharing a material
     * with a shop entry is not that entry, and must never be sold at its price.
     */
    @Test
    void refusesItemsCarryingCustomData() {
        ItemStack named = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = named.getItemMeta();
        meta.displayName(Component.text("Excalibur"));
        named.setItemMeta(meta);
        assertFalse(wands.isSellable(named), "a named item must not be sold as a plain one");

        ItemStack enchanted = new ItemStack(Material.DIAMOND_SWORD);
        enchanted.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        assertFalse(wands.isSellable(enchanted));
    }

    /** A wand is itself a custom item, so a wand can never sell another wand. */
    @Test
    void refusesVertexOwnItems() {
        assertFalse(wands.isSellable(wands.createWand(wands.tier("diamond"))));
    }

    @Test
    void refusesMaterialsOnTheNeverSellList() {
        assertFalse(wands.isSellable(new ItemStack(Material.SPAWNER)));
        assertTrue(wands.isPlainStack(new ItemStack(Material.SPAWNER)),
                "it is a plain stack -- it is the never-sell list that blocks it");
    }

    @Test
    void refusesEmptyAndAirStacks() {
        assertFalse(wands.isSellable(null));
        assertFalse(wands.isSellable(new ItemStack(Material.AIR)));
    }

    @Test
    void readsTheConfiguredTntConversion() {
        assertEquals(5, wands.gunpowderPerTnt());
        assertEquals(0, wands.sandPerTnt(), "sand is not required by default, so it costs none");
    }

    /**
     * Chunk Collectors tag mob drops for their own bookkeeping. That tag is
     * item NBT, so treating any tagged item as custom made wands refuse
     * ordinary loot -- a chest of grinder drops sold nothing, and a TNT Wand
     * would not touch creeper gunpowder, which is the main source of it.
     */
    @Test
    void sellsOrdinaryLootCarryingVertexInternalMarkers() {
        ItemStack bone = new ItemStack(Material.BONE, 12);
        ItemMeta meta = bone.getItemMeta();
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "mob_drop"), PersistentDataType.BYTE, (byte) 1);
        bone.setItemMeta(meta);

        assertTrue(wands.isSellable(bone), "a tagged mob drop is still a plain bone");
        assertTrue(wands.isPlainStack(bone));
    }

    @Test
    void tntWandStillSeesTaggedGunpowder() {
        ItemStack gunpowder = new ItemStack(Material.GUNPOWDER, 64);
        ItemMeta meta = gunpowder.getItemMeta();
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "mob_drop"), PersistentDataType.BYTE, (byte) 1);
        gunpowder.setItemMeta(meta);

        assertTrue(wands.isPlainStack(gunpowder), "creeper gunpowder must be convertible");
    }

    /**
     * The exemption is an allowlist, not "anything Vertex tagged" -- Vertex's
     * own items are identified by their own keys, so a blanket exemption
     * would let a wand sell another wand.
     */
    @Test
    void stillRefusesItemsCarryingUnknownData() {
        ItemStack odd = new ItemStack(Material.COBBLESTONE, 8);
        ItemMeta meta = odd.getItemMeta();
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "some_other_marker"), PersistentDataType.BYTE, (byte) 1);
        odd.setItemMeta(meta);

        assertFalse(wands.isSellable(odd), "an unrecognised tag still means hands off");
    }

    @Test
    void doesNotSellFilledContainerItemsOrEraseTheirContentsWhileChecking() {
        ItemStack barrel = new ItemStack(Material.BARREL);
        org.bukkit.inventory.meta.BlockStateMeta meta = (org.bukkit.inventory.meta.BlockStateMeta) barrel.getItemMeta();
        org.bukkit.block.Barrel state = (org.bukkit.block.Barrel) meta.getBlockState();
        state.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));
        meta.setBlockState(state);
        barrel.setItemMeta(meta);
        ItemStack before = barrel.clone();
        assertFalse(wands.isSellable(barrel));
        assertEquals(before, barrel);
    }

    @Test
    void harmlessMarkerDoesNotHideOtherVanillaMetadata() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        org.bukkit.inventory.meta.Damageable meta = (org.bukkit.inventory.meta.Damageable) sword.getItemMeta();
        meta.setDamage(10);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "mob_drop"), PersistentDataType.BYTE, (byte) 1);
        sword.setItemMeta(meta);
        assertFalse(wands.isPlainStack(sword));
        assertTrue(sword.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin, "mob_drop")));
    }

}
