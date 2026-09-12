package me.vertex.core.storage;

import org.bukkit.Material;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryWalTest {
    @TempDir Path folder;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void survivesReloadAndCanBeAcknowledged() throws Exception {
        DeliveryWal first = new DeliveryWal(folder.toFile());
        String batchId = UUID.randomUUID().toString();
        UUID owner = UUID.randomUUID();
        String itemId = UUID.randomUUID().toString();
        first.put(new DeliveryWal.WalBatch(batchId, owner,
                List.of(new DeliveryStorage.PreparedDelivery(itemId,
                        new ItemStack(Material.DIAMOND, 17))), "test-overflow"));

        DeliveryWal second = new DeliveryWal(folder.toFile());
        List<DeliveryWal.WalBatch> recovered = second.load();
        assertEquals(1, recovered.size());
        assertEquals(batchId, recovered.getFirst().batchId());
        assertEquals(owner, recovered.getFirst().owner());
        assertEquals(itemId, recovered.getFirst().items().getFirst().id());
        assertEquals(new ItemStack(Material.DIAMOND, 17), recovered.getFirst().items().getFirst().item());

        second.remove(batchId);
        assertTrue(new DeliveryWal(folder.toFile()).load().isEmpty());
    }
}
