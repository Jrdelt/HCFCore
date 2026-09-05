package me.hcfcore.core.kit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmorClassTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void isMageIsTrueForTheMixedGoldChainmailSet() {
        PlayerMock player = server.addPlayer("Alice");
        wear(player, Material.GOLDEN_BOOTS, Material.CHAINMAIL_LEGGINGS,
                Material.CHAINMAIL_CHESTPLATE, Material.GOLDEN_HELMET);

        assertTrue(ArmorClass.isMage(player));
        assertFalse(ArmorClass.isBard(player), "the mixed mage set must not also read as the full-gold bard set");
    }

    @Test
    void isMageIsFalseForTheFullGoldBardSet() {
        PlayerMock player = server.addPlayer("Bob");
        wear(player, Material.GOLDEN_BOOTS, Material.GOLDEN_LEGGINGS,
                Material.GOLDEN_CHESTPLATE, Material.GOLDEN_HELMET);

        assertFalse(ArmorClass.isMage(player));
        assertTrue(ArmorClass.isBard(player));
    }

    @Test
    void isMageIsFalseWithAnyPieceMissing() {
        PlayerMock player = server.addPlayer("Carol");
        wear(player, Material.GOLDEN_BOOTS, Material.CHAINMAIL_LEGGINGS,
                Material.CHAINMAIL_CHESTPLATE, null);

        assertFalse(ArmorClass.isMage(player));
    }

    /** Sets armor in Bukkit's boots-to-helmet order. */
    private static void wear(PlayerMock player, Material boots, Material leggings,
                              Material chestplate, Material helmet) {
        player.getInventory().setArmorContents(new ItemStack[]{
                boots == null ? null : new ItemStack(boots),
                leggings == null ? null : new ItemStack(leggings),
                chestplate == null ? null : new ItemStack(chestplate),
                helmet == null ? null : new ItemStack(helmet),
        });
    }
}
