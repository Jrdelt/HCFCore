package me.vertex.core.enchant;

import me.vertex.core.backpack.BackpackManager;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.lang.Messages;
import me.vertex.core.mine.MineManager;
import me.vertex.core.user.UserManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * War Chest's proc must fire only for its own equipped backpack, inside an
 * eligible mine, respect its shared successful-proc cooldown, and never
 * spawn any orb/pickup entity -- the booster is granted directly.
 */
class WarChestListenerTest {

    private PluginMock plugin;
    private EnchantManager manager;
    private BackpackManager backpacks;
    private MineManager mines;
    private WarChestListener listener;
    private World world;

    @BeforeEach
    void setUp() throws IOException {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        manager = new EnchantManager(plugin, new TrackedItemIds(plugin));
        manager.load();
        backpacks = new BackpackManager(plugin, new Messages(plugin, new UserManager(plugin, null)));
        backpacks.load();
        mines = new MineManager(plugin, new Messages(plugin, new UserManager(plugin, null)));
        listener = new WarChestListener(manager, backpacks, new Messages(plugin, new UserManager(plugin, null)));
        listener.setMineManager(mines);
        world = MockBukkit.getMock().addSimpleWorld("world");

        // A single-region mine so regionAt(...) resolves for any block in it.
        Files.writeString(plugin.getDataFolder().toPath().resolve("mines.yml"), """
                mines:
                  test_mine:
                    world: world
                    minimum: {x: -10, y: 0, z: -10}
                    maximum: {x: 10, y: 200, z: 10}
                    ores:
                      STONE: { weight: 100, drop: 1 }
                """, StandardCharsets.UTF_8);
        mines.load();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemStack warChestItem(int level) {
        var tier = backpacks.getTier("war_chest");
        assertNotNull(tier, "war_chest backpack tier must be configured");
        return backpacks.createBackpackItem(tier, level);
    }

    @Test
    void grantsABoosterOnAnEligibleBreakAndNeverSpawnsAnyEntity() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.getInventory().setItemInOffHand(warChestItem(5));
        player.setLocation(new Location(world, 0, 64, 0));

        long nonPlayerEntitiesBefore = world.getEntities().stream().filter(e -> !(e instanceof org.bukkit.entity.Player)).count();
        BlockBreakEvent event = new BlockBreakEvent(world.getBlockAt(0, 63, 0), player);
        listener.onBlockBreak(event);

        boolean hasBooster = player.hasPotionEffect(PotionEffectType.SPEED) || player.hasPotionEffect(PotionEffectType.STRENGTH);
        // Level 5's proc chance is 5%; run enough independent attempts (fresh
        // players/cooldowns each time) that at least one should land.
        if (!hasBooster) {
            for (int i = 0; i < 200 && !hasBooster; i++) {
                PlayerMock retry = MockBukkit.getMock().addPlayer();
                retry.getInventory().setItemInOffHand(warChestItem(5));
                retry.setLocation(new Location(world, 0, 64, 0));
                listener.onBlockBreak(new BlockBreakEvent(world.getBlockAt(0, 63, 0), retry));
                hasBooster = retry.hasPotionEffect(PotionEffectType.SPEED) || retry.hasPotionEffect(PotionEffectType.STRENGTH);
            }
        }
        assertTrue(hasBooster, "at least one of 200 independent 5% rolls should have granted a booster");
        long nonPlayerEntitiesAfter = world.getEntities().stream().filter(e -> !(e instanceof org.bukkit.entity.Player)).count();
        assertEquals(nonPlayerEntitiesBefore, nonPlayerEntitiesAfter, "War Chest must never spawn an orb/pickup entity");
    }

    @Test
    void doesNothingWithoutAWarChestEquipped() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.setLocation(new Location(world, 0, 64, 0));
        listener.onBlockBreak(new BlockBreakEvent(world.getBlockAt(0, 63, 0), player));
        assertNull(player.getPotionEffect(PotionEffectType.SPEED));
        assertNull(player.getPotionEffect(PotionEffectType.STRENGTH));
    }
}
