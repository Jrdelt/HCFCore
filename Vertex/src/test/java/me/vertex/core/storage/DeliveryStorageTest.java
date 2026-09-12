package me.vertex.core.storage;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryStorageTest {
    @TempDir Path dataFolder;
    private Database database;
    private DeliveryStorage storage;
    private final UUID owner=UUID.randomUUID();

    @BeforeEach void setUp()throws Exception{
        MockBukkit.mock();database=new Database(new YamlConfiguration(),dataFolder.toFile());
        storage=new DeliveryStorage(database);storage.init();
    }

    @AfterEach void tearDown(){if(database!=null)database.close();MockBukkit.unmock();}

    @Test void reservationMustBeAcknowledgedBeforeRowsDisappear()throws Exception{
        storage.enqueue(owner,List.of(new ItemStack(Material.DIAMOND,3)),"test");
        DeliveryStorage.Reservation reservation=storage.reserve(owner);
        assertEquals(1,reservation.rows().size());
        assertEquals(3,reservation.rows().getFirst().item().getAmount());
        assertEquals(1,storage.delivering(owner).size());
        assertEquals(1,storage.complete(owner,reservation.token()));
        assertTrue(storage.delivering(owner).isEmpty());
        assertTrue(storage.reserve(owner).rows().isEmpty());
    }

    @Test void releasedReservationCanBeClaimedAgain()throws Exception{
        storage.enqueue(owner,List.of(new ItemStack(Material.EMERALD,2)),"test");
        DeliveryStorage.Reservation first=storage.reserve(owner);
        storage.release(owner,first.token());
        DeliveryStorage.Reservation second=storage.reserve(owner);
        assertEquals(1,second.rows().size());
        assertEquals(Material.EMERALD,second.rows().getFirst().item().getType());
    }

    @Test void retryingTheSamePreparedBatchDoesNotDuplicateIt()throws Exception{
        DeliveryStorage.PreparedDelivery item=new DeliveryStorage.PreparedDelivery(
                UUID.randomUUID().toString(),new ItemStack(Material.NETHERITE_INGOT,4));
        storage.enqueuePrepared(owner,List.of(item),"retry-test");
        storage.enqueuePrepared(owner,List.of(item),"retry-test");
        DeliveryStorage.Reservation reservation=storage.reserve(owner);
        assertEquals(1,reservation.rows().size());
        assertEquals(4,reservation.rows().getFirst().item().getAmount());
    }
}
