package me.vertex.core.trade;

import me.vertex.core.storage.Database;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for the trade-claim delete-before-delivery rule. */
class TradeStorageTest {

    @TempDir
    Path dataFolder;

    private Database database;
    private TradeStorage storage;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new TradeStorage(database);
        storage.init();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void aClaimCanOnlyBeTakenOnce() throws Exception {
        storage.insertClaim(owner, new ItemStack(Material.DIAMOND, 3));

        List<ItemStack> first = storage.takeClaims(owner);
        List<ItemStack> second = storage.takeClaims(owner);

        assertEquals(1, first.size());
        assertEquals(Material.DIAMOND, first.get(0).getType());
        assertEquals(3, first.get(0).getAmount());
        assertEquals(List.of(), second, "a second delivery path must not receive the same claim");
    }

    @Test
    void aLaterClaimIsNotDeletedWithAnEarlierClaimBatch() throws Exception {
        storage.insertClaim(owner, new ItemStack(Material.DIAMOND, 1));
        assertEquals(1, storage.takeClaims(owner).size());

        storage.insertClaim(owner, new ItemStack(Material.EMERALD, 2));
        List<ItemStack> later = storage.takeClaims(owner);

        assertEquals(1, later.size());
        assertEquals(Material.EMERALD, later.get(0).getType());
        assertEquals(2, later.get(0).getAmount());
    }
}
