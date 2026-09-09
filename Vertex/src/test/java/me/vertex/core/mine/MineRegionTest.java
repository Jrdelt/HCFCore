package me.vertex.core.mine;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineRegionTest {

    private static MineRegion region() {
        return new MineRegion(
                "test", "Test", "mine", 0, 0, 0, 10, 10, 10,
                MineRegion.PvpMode.ENABLED, Material.STONE, 30,
                Set.of(Material.STONE, Material.DEEPSLATE),
                MineOreTable.of(List.of(
                        new MineOreTable.Entry(Material.STONE, 60D, 1),
                        new MineOreTable.Entry(Material.COAL_ORE, 40D, 1))));
    }

    @Test
    void deepslateVariantOfAConfiguredOreIsMineable() {
        MineRegion region = region();

        assertTrue(region.isMineBlock(Material.DEEPSLATE_COAL_ORE));
        assertTrue(region.isOre(Material.DEEPSLATE_COAL_ORE));
        MineOreTable.Entry entry = region.oreEntry(Material.DEEPSLATE_COAL_ORE);
        assertNotNull(entry);
        assertEquals(Material.COAL_ORE, entry.material());
    }

    @Test
    void unconfiguredOreRemainsProtectedStructure() {
        MineRegion region = region();

        assertFalse(region.isMineBlock(Material.DEEPSLATE_DIAMOND_ORE));
        assertFalse(region.isOre(Material.DEEPSLATE_DIAMOND_ORE));
    }
}
