package me.vertex.core.bucket;

import me.vertex.core.factions.FactionsHook;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link SourceBucketManager} without touching FactionsUUID's live
 * {@code Board} singleton or a real {@code CombatManager}/{@code
 * BaseClaimManager} -- every such lookup is a plain per-test lambda here,
 * standing in for what {@code FactionsHook}/{@code CombatManager}/{@code
 * BaseClaimManager} report in production (mirrors {@code
 * ChunkBusterManagerTest}'s approach exactly). Every test flow runs down a
 * single fixed {@code x, z} column, so the injected lookups are keyed by Y
 * alone -- exactly how a real DOWNWARD flow behaves too, since FactionsUUID
 * claims apply to a whole chunk regardless of height.
 */
class SourceBucketManagerTest {

    private ServerMock server;
    private WorldMock world;
    private PluginMock plugin;
    private PlayerMock player;
    private FakeEconomy economy;

    /** Mutable per-test wiring for the injected lookups, read by the lambdas passed into the manager. */
    private final Map<Integer, Integer> factionIdByY = new HashMap<>();
    private int defaultFactionId = 5;
    private final Map<Integer, String> tagByY = new HashMap<>();
    private final Map<Integer, Boolean> baseClaimByY = new HashMap<>();
    private boolean defaultBaseClaim = true;
    private boolean combatTagged = false;

    private SourceBucketManager manager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("sourcebucket-test-world");
        player = server.addPlayer("Bucketeer");

        economy = new FakeEconomy();
        Bukkit.getServicesManager().register(Economy.class, economy, plugin, ServicePriority.Normal);
        economy.set(player.getUniqueId(), 1000.0);

        manager = new SourceBucketManager(plugin,
                loc -> factionIdByY.getOrDefault(loc.getBlockY(), defaultFactionId),
                loc -> tagByY.get(loc.getBlockY()),
                loc -> baseClaimByY.getOrDefault(loc.getBlockY(), defaultBaseClaim),
                uuid -> combatTagged);
        manager.load();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Location locationAt(int x, int y, int z) {
        return new Location(world, x, y, z);
    }

    private static SourceBucketType waterSingle(boolean baseClaimOnly, boolean combatAllowed, double perUseFee) {
        return new SourceBucketType("water:test-single", true, SourceBucketType.PlacedBlock.WATER,
                SourceBucketType.FlowPattern.SINGLE_SOURCE, 0, baseClaimOnly, combatAllowed, 100D, perUseFee,
                Material.WATER_BUCKET, null, "<aqua>Test Water Bucket", List.of(), false);
    }

    private static SourceBucketType waterDownward(int maxDepth, boolean baseClaimOnly, boolean combatAllowed,
            double perUseFee) {
        return new SourceBucketType("water:test-downward", true, SourceBucketType.PlacedBlock.WATER,
                SourceBucketType.FlowPattern.DOWNWARD, maxDepth, baseClaimOnly, combatAllowed, 100D, perUseFee,
                Material.WATER_BUCKET, null, "<aqua>Test Waterfall Bucket", List.of(), false);
    }

    private static SourceBucketType lavaDownward(int maxDepth) {
        return new SourceBucketType("lava:test-downward", true, SourceBucketType.PlacedBlock.LAVA,
                SourceBucketType.FlowPattern.DOWNWARD, maxDepth, false, false, 100D, 0D,
                Material.LAVA_BUCKET, null, "<gold>Test Lava Bucket", List.of(), false);
    }

    private static SourceBucketType solidDownward(SourceBucketType.PlacedBlock placedBlock, int maxDepth) {
        return new SourceBucketType(placedBlock.configKey() + ":test-downward", true, placedBlock,
                SourceBucketType.FlowPattern.DOWNWARD, maxDepth, false, false, 100D, 0D,
                Material.BUCKET, null, "Test solid bucket", List.of(), true);
    }

    private static SourceBucketType solidOutward(SourceBucketType.PlacedBlock placedBlock, int maxLength) {
        return new SourceBucketType(placedBlock.configKey() + ":test-outward", true, placedBlock,
                SourceBucketType.FlowPattern.OUTWARD, maxLength, false, false, 100D, 0D,
                Material.BUCKET, null, "Test solid bucket", List.of(), true);
    }

    // ---- Config loading ----

    @Test
    void loadParsesBundledVariants() {
        assertFalse(manager.enabledVariants().isEmpty());
        assertNotNull(manager.variant("water:source"));
        assertNotNull(manager.variant("water:downward"));
        assertNotNull(manager.variant("lava:source"));
        assertNotNull(manager.variant("lava:downward"));
        assertNotNull(manager.variant("obsidian:downward"));
        assertNotNull(manager.variant("obsidian:outward"));
        assertNotNull(manager.variant("cobblestone:downward"));
        assertNotNull(manager.variant("cobblestone:outward"));
        assertEquals(SourceBucketType.PlacedBlock.WATER, manager.variant("water:source").placedBlock());
        assertEquals(SourceBucketType.FlowPattern.SINGLE_SOURCE, manager.variant("water:source").flowPattern());
        assertEquals(SourceBucketType.PlacedBlock.LAVA, manager.variant("lava:downward").placedBlock());
        assertEquals(SourceBucketType.FlowPattern.DOWNWARD, manager.variant("lava:downward").flowPattern());
        assertEquals(-1, manager.variant("obsidian:downward").maxDistance());
        assertEquals(16, manager.variant("cobblestone:outward").maxDistance());
    }

    // ---- Item identity ----

    @Test
    void createdItemRoundTripsThroughVariantOf() {
        SourceBucketType variant = manager.variant("water:source");
        ItemStack item = manager.createItem(variant);
        assertEquals(variant, manager.variantOf(item));
    }

    @Test
    void createdItemNeverStacks() {
        ItemStack item = manager.createItem(manager.variant("water:source"));
        assertEquals(1, item.getItemMeta().getMaxStackSize());
    }

    @Test
    void unrecognizedItemHasNoVariant() {
        assertEquals(null, manager.variantOf(new ItemStack(Material.WATER_BUCKET)));
    }

    // ---- Single-source placement ----

    @Test
    void singleSourcePlacesExactlyOneBlock() {
        SourceBucketType variant = waterSingle(false, false, 0D);
        Location target = locationAt(0, 64, 0);

        SourceBucketManager.UseResult result = manager.use(player, target, variant);

        assertEquals(SourceBucketManager.UseResult.OK, result);
        assertEquals(Material.WATER, world.getBlockAt(target).getType());
        assertEquals(Material.AIR, world.getBlockAt(0, 63, 0).getType(), "single-source must not touch anything else");
    }

    // ---- Downward flow ----

    @Test
    void downwardFlowPlacesUpToMaxDepthThenStops() {
        SourceBucketType variant = waterDownward(3, false, false, 0D);

        manager.use(player, locationAt(0, 64, 0), variant);

        for (int depth = 0; depth <= 3; depth++) {
            assertEquals(Material.WATER, world.getBlockAt(0, 64 - depth, 0).getType(), "depth " + depth + " must be water");
        }
        assertEquals(Material.AIR, world.getBlockAt(0, 60, 0).getType(), "must stop at max-depth, not go further");
    }

    @Test
    void downwardFlowStopsAtAnObstructionWithoutOverwritingIt() {
        world.getBlockAt(0, 62, 0).setType(Material.STONE); // an obstruction two blocks below the placement point
        SourceBucketType variant = waterDownward(5, false, false, 0D);

        manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(Material.WATER, world.getBlockAt(0, 64, 0).getType());
        assertEquals(Material.WATER, world.getBlockAt(0, 63, 0).getType());
        assertEquals(Material.STONE, world.getBlockAt(0, 62, 0).getType(), "the obstruction itself must be untouched");
        assertEquals(Material.AIR, world.getBlockAt(0, 61, 0).getType(), "nothing past the obstruction should be reached");
    }

    @Test
    void downwardFlowIsHappyPlacingOverAnAlreadyMatchingLiquid() {
        world.getBlockAt(0, 62, 0).setType(Material.WATER); // already water -- not an obstruction for a water bucket
        SourceBucketType variant = waterDownward(3, false, false, 0D);

        manager.use(player, locationAt(0, 64, 0), variant);

        for (int depth = 0; depth <= 3; depth++) {
            assertEquals(Material.WATER, world.getBlockAt(0, 64 - depth, 0).getType());
        }
    }

    @Test
    void computeFlowPositionsIsReadOnly() {
        SourceBucketType variant = waterDownward(4, false, false, 0D);

        List<Location> positions = manager.computeFlowPositions(locationAt(0, 64, 0), variant);

        assertEquals(5, positions.size());
        assertEquals(Material.AIR, world.getBlockAt(0, 64, 0).getType(), "a dry-run computation must never mutate a block");
    }

    @Test
    void unlimitedDownwardSolidBucketContinuesUntilTheBottomObstruction() {
        SourceBucketType variant = solidDownward(SourceBucketType.PlacedBlock.OBSIDIAN, -1);
        Location origin = locationAt(0, 64, 0);

        List<Location> positions = manager.computeFlowPositions(origin, variant);
        assertFalse(positions.isEmpty());
        assertTrue(positions.size() > 8, "-1 must not use a normal finite max-depth");
        Location last = positions.getLast();
        Location belowLast = last.clone().add(0, -1, 0);
        assertTrue(last.getBlockY() == world.getMinHeight()
                        || !belowLast.getBlock().getType().isAir(),
                "the unlimited column must end at world bottom or the first solid bottom obstruction");

        assertEquals(SourceBucketManager.UseResult.OK, manager.use(player, origin, variant));
        assertEquals(Material.OBSIDIAN, world.getBlockAt(0, 64, 0).getType());
        assertEquals(Material.OBSIDIAN, last.getBlock().getType());
    }

    @Test
    void outwardSolidBucketPlacesExactlyConfiguredLineAlongClickedFace() {
        SourceBucketType variant = solidOutward(SourceBucketType.PlacedBlock.COBBLESTONE, 16);
        Location origin = locationAt(0, 64, 0);

        assertEquals(SourceBucketManager.UseResult.OK, manager.use(player, origin, BlockFace.EAST, variant));
        for (int x = 0; x < 16; x++) {
            assertEquals(Material.COBBLESTONE, world.getBlockAt(x, 64, 0).getType(), "line position " + x);
        }
        assertEquals(Material.AIR, world.getBlockAt(16, 64, 0).getType(), "the configured length includes the origin");
    }

    // ---- Faction-boundary crossing (relationship-independent) ----

    @Test
    void downwardFlowStopsAtAFactionBoundaryEvenAnAllyFaction() {
        // Ally-or-not is irrelevant: SourceBucketManager never queries a
        // relationship at all, only faction-id equality against the
        // origin -- the "always stop, no exceptions" rule from the spec.
        factionIdByY.put(64, 5);
        factionIdByY.put(63, 6); // a different (allied, in a real server) faction's claim
        SourceBucketType variant = waterDownward(5, false, false, 0D);

        manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(Material.WATER, world.getBlockAt(0, 64, 0).getType());
        assertEquals(Material.AIR, world.getBlockAt(0, 63, 0).getType(),
                "must stop the instant the claiming faction changes, even to an ally");
    }

    @Test
    void downwardFlowStopsAtAFactionBoundaryToAnEnemyFaction() {
        factionIdByY.put(64, 5);
        factionIdByY.put(63, 7); // a different (enemy, in a real server) faction's claim
        SourceBucketType variant = waterDownward(5, false, false, 0D);

        manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(Material.WATER, world.getBlockAt(0, 64, 0).getType());
        assertEquals(Material.AIR, world.getBlockAt(0, 63, 0).getType());
    }

    // ---- SafeZone / WarZone ----

    @Test
    void downwardFlowStopsAtASafeZoneBoundary() {
        tagByY.put(63, "SafeZone");
        SourceBucketType variant = waterDownward(5, false, false, 0D);

        manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(Material.WATER, world.getBlockAt(0, 64, 0).getType());
        assertEquals(Material.AIR, world.getBlockAt(0, 63, 0).getType());
    }

    @Test
    void downwardFlowStopsAtAWarZoneBoundaryCaseInsensitively() {
        tagByY.put(63, "WARZONE");
        SourceBucketType variant = waterDownward(5, false, false, 0D);

        manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(Material.AIR, world.getBlockAt(0, 63, 0).getType());
    }

    @Test
    void useIsBlockedEntirelyWhenTheOriginItselfIsASafeZone() {
        tagByY.put(64, "SafeZone");
        SourceBucketType variant = waterSingle(false, false, 0D);

        SourceBucketManager.UseResult result = manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(SourceBucketManager.UseResult.WRONG_ZONE, result);
        assertEquals(Material.AIR, world.getBlockAt(0, 64, 0).getType());
    }

    // ---- Never enters unclaimed land ----

    @Test
    void downwardFlowStopsBeforeEnteringUnclaimedLand() {
        factionIdByY.put(63, FactionsHook.NO_FACTION);
        SourceBucketType variant = waterDownward(5, false, false, 0D);

        manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(Material.WATER, world.getBlockAt(0, 64, 0).getType());
        assertEquals(Material.AIR, world.getBlockAt(0, 63, 0).getType(), "the flow must never enter unclaimed land");
    }

    @Test
    void useIsBlockedEntirelyInWilderness() {
        defaultFactionId = FactionsHook.NO_FACTION;
        SourceBucketType variant = waterSingle(false, false, 0D);

        SourceBucketManager.UseResult result = manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(SourceBucketManager.UseResult.WRONG_ZONE, result);
        assertEquals(Material.AIR, world.getBlockAt(0, 64, 0).getType());
    }

    // ---- Base-claim-only enforcement ----

    @Test
    void baseClaimOnlyVariantWorksInABaseClaim() {
        defaultBaseClaim = true;
        SourceBucketType variant = waterSingle(true, false, 0D);

        assertEquals(SourceBucketManager.UseResult.OK, manager.use(player, locationAt(0, 64, 0), variant));
    }

    @Test
    void baseClaimOnlyVariantIsBlockedInARaidClaim() {
        defaultBaseClaim = false; // claimed land, but not a Base Claim -- i.e. a Raid Claim
        SourceBucketType variant = waterSingle(true, false, 0D);

        SourceBucketManager.UseResult result = manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(SourceBucketManager.UseResult.WRONG_ZONE, result);
        assertEquals(Material.AIR, world.getBlockAt(0, 64, 0).getType());
    }

    @Test
    void nonBaseClaimOnlyVariantWorksInARaidClaimToo() {
        defaultBaseClaim = false;
        SourceBucketType variant = waterSingle(false, false, 0D);

        assertEquals(SourceBucketManager.UseResult.OK, manager.use(player, locationAt(0, 64, 0), variant));
    }

    @Test
    void downwardFlowStopsWhenBaseClaimOnlyDriftsOutOfTheBaseClaimRegion() {
        baseClaimByY.put(64, true);
        baseClaimByY.put(63, false); // same faction, but no longer inside the Base Claim region at this depth
        SourceBucketType variant = waterDownward(5, true, false, 0D);

        manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(Material.WATER, world.getBlockAt(0, 64, 0).getType());
        assertEquals(Material.AIR, world.getBlockAt(0, 63, 0).getType());
    }

    // ---- Charge-after-success economic ordering ----

    @Test
    void noChargeWhenPlacementValidationFails() {
        world.getBlockAt(0, 64, 0).setType(Material.STONE); // obstruction at the origin itself
        SourceBucketType variant = waterSingle(false, false, 50D);
        double before = economy.get(player.getUniqueId());

        SourceBucketManager.UseResult result = manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(SourceBucketManager.UseResult.FAILED_PLACEMENT, result);
        assertEquals(before, economy.get(player.getUniqueId()), "a failed placement must never be charged");
        assertEquals(Material.STONE, world.getBlockAt(0, 64, 0).getType());
    }

    @Test
    void noChargeWhenTheZoneCheckFails() {
        defaultFactionId = FactionsHook.NO_FACTION;
        SourceBucketType variant = waterSingle(false, false, 50D);
        double before = economy.get(player.getUniqueId());

        manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(before, economy.get(player.getUniqueId()));
    }

    @Test
    void insufficientFundsPlacesNothingAndChargesNothing() {
        economy.set(player.getUniqueId(), 1.0);
        SourceBucketType variant = waterSingle(false, false, 50D);

        SourceBucketManager.UseResult result = manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(SourceBucketManager.UseResult.INSUFFICIENT_FUNDS, result);
        assertEquals(1.0, economy.get(player.getUniqueId()));
        assertEquals(Material.AIR, world.getBlockAt(0, 64, 0).getType(), "nothing must be placed when funds are insufficient");
    }

    @Test
    void successfulUseChargesTheConfiguredFeeExactlyOnceAfterPlacement() {
        double before = economy.get(player.getUniqueId());
        SourceBucketType variant = waterSingle(false, false, 25D);

        SourceBucketManager.UseResult result = manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(SourceBucketManager.UseResult.OK, result);
        assertEquals(Material.WATER, world.getBlockAt(0, 64, 0).getType());
        assertEquals(before - 25D, economy.get(player.getUniqueId()), 0.0001);
    }

    @Test
    void freeVariantNeedsNoEconomyAtAll() {
        // perUseFee <= 0 must skip the economy checks entirely -- this must
        // succeed even with no economy provider registered at all.
        Bukkit.getServicesManager().unregisterAll(plugin);
        SourceBucketType variant = waterSingle(false, false, 0D);

        SourceBucketManager.UseResult result = manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(SourceBucketManager.UseResult.OK, result);
    }

    // ---- Combat gate, independently configurable per variant ----

    @Test
    void combatBlockedVariantRefusesUseWhileTagged() {
        combatTagged = true;
        SourceBucketType variant = waterSingle(false, false, 0D); // combatAllowed = false

        SourceBucketManager.UseResult result = manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(SourceBucketManager.UseResult.IN_COMBAT, result);
        assertEquals(Material.AIR, world.getBlockAt(0, 64, 0).getType());
    }

    @Test
    void combatAllowedVariantWorksWhileTaggedEvenThoughAnotherVariantWouldNot() {
        combatTagged = true;
        SourceBucketType blocked = waterSingle(false, false, 0D);
        SourceBucketType allowed = waterSingle(false, true, 0D);

        assertEquals(SourceBucketManager.UseResult.IN_COMBAT, manager.use(player, locationAt(0, 64, 0), blocked));
        assertEquals(SourceBucketManager.UseResult.OK, manager.use(player, locationAt(1, 64, 0), allowed));
    }

    // ---- Type-disabled ----

    @Test
    void disabledVariantIsRejectedWithoutTouchingTheWorld() {
        SourceBucketType variant = new SourceBucketType("water:test-disabled", false, SourceBucketType.PlacedBlock.WATER,
                SourceBucketType.FlowPattern.SINGLE_SOURCE, 0, false, false, 0D, 0D,
                Material.WATER_BUCKET, null, "<aqua>Test", List.of(), false);

        SourceBucketManager.UseResult result = manager.use(player, locationAt(0, 64, 0), variant);

        assertEquals(SourceBucketManager.UseResult.TYPE_DISABLED, result);
        assertEquals(Material.AIR, world.getBlockAt(0, 64, 0).getType());
    }

    // ---- Lava, sanity ----

    @Test
    void lavaDownwardFlowPlacesLavaNotWater() {
        manager.use(player, locationAt(5, 64, 5), lavaDownward(2));

        assertEquals(Material.LAVA, world.getBlockAt(5, 64, 5).getType());
        assertEquals(Material.LAVA, world.getBlockAt(5, 63, 5).getType());
        assertEquals(Material.LAVA, world.getBlockAt(5, 62, 5).getType());
    }

    /** Copied per this codebase's existing convention (see ShopManagerTest/CoinflipManagerTest/AuctionManagerTest). */
    private static final class FakeEconomy implements Economy {
        private final Map<UUID, Double> balances = new ConcurrentHashMap<>();

        void set(UUID uuid, double amount) {
            balances.put(uuid, amount);
        }

        double get(UUID uuid) {
            return balances.getOrDefault(uuid, 0.0);
        }

        @Override
        public boolean has(OfflinePlayer player, double amount) {
            return get(player.getUniqueId()) >= amount;
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
            if (get(player.getUniqueId()) < amount) {
                return new EconomyResponse(0, get(player.getUniqueId()), EconomyResponse.ResponseType.FAILURE, "insufficient funds");
            }
            balances.merge(player.getUniqueId(), -amount, Double::sum);
            return new EconomyResponse(amount, get(player.getUniqueId()), EconomyResponse.ResponseType.SUCCESS, null);
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
            balances.merge(player.getUniqueId(), amount, Double::sum);
            return new EconomyResponse(amount, get(player.getUniqueId()), EconomyResponse.ResponseType.SUCCESS, null);
        }

        @Override
        public String format(double amount) {
            return "$" + amount;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "FakeEconomy";
        }

        @Override
        public boolean hasBankSupport() {
            return false;
        }

        @Override
        public int fractionalDigits() {
            return 2;
        }

        @Override
        public String currencyNamePlural() {
            return "Dollars";
        }

        @Override
        public String currencyNameSingular() {
            return "Dollar";
        }

        @Override
        public boolean hasAccount(String playerName) {
            return true;
        }

        @Override
        public boolean hasAccount(OfflinePlayer player) {
            return true;
        }

        @Override
        public boolean hasAccount(String playerName, String worldName) {
            return true;
        }

        @Override
        public boolean hasAccount(OfflinePlayer player, String worldName) {
            return true;
        }

        @Override
        public double getBalance(String playerName) {
            return 0;
        }

        @Override
        public double getBalance(OfflinePlayer player) {
            return get(player.getUniqueId());
        }

        @Override
        public double getBalance(String playerName, String world) {
            return 0;
        }

        @Override
        public double getBalance(OfflinePlayer player, String world) {
            return get(player.getUniqueId());
        }

        @Override
        public boolean has(String playerName, double amount) {
            return false;
        }

        @Override
        public boolean has(String playerName, String worldName, double amount) {
            return false;
        }

        @Override
        public boolean has(OfflinePlayer player, String worldName, double amount) {
            return has(player, amount);
        }

        @Override
        public EconomyResponse withdrawPlayer(String playerName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
            return withdrawPlayer(player, amount);
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
            return depositPlayer(player, amount);
        }

        @Override
        public EconomyResponse createBank(String name, String player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse createBank(String name, OfflinePlayer player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse deleteBank(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse bankBalance(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse bankHas(String name, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse bankWithdraw(String name, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse bankDeposit(String name, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse isBankOwner(String name, String playerName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse isBankMember(String name, String playerName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse isBankMember(String name, OfflinePlayer player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> getBanks() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean createPlayerAccount(String playerName) {
            return true;
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player) {
            return true;
        }

        @Override
        public boolean createPlayerAccount(String playerName, String worldName) {
            return true;
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
            return true;
        }
    }
}
