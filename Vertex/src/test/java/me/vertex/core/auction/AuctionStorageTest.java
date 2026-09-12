package me.vertex.core.auction;

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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionStorageTest {
    @TempDir Path dataFolder;
    private Database database;private AuctionStorage storage;
    @BeforeEach void setUp()throws Exception{MockBukkit.mock();database=new Database(new YamlConfiguration(),
            dataFolder.toFile());storage=new AuctionStorage(database);storage.init();}
    @AfterEach void tearDown(){if(database!=null)database.close();MockBukkit.unmock();}

    @Test void onlyEscrowedCreationIntentCanBecomeAListing()throws Exception{
        AuctionStorage.CreationIntent intent=storage.insertCreationIntent(UUID.randomUUID(),
                new ItemStack(Material.DIAMOND,2),500,AuctionCurrency.MONEY,10,20);
        assertEquals(-1,storage.activateCreationIntent(intent));
        assertEquals("PREPARED",storage.loadCreationIntents().getFirst().state());
        assertTrue(storage.setCreationIntentState(intent.key(),"PREPARED","DEBITING"));
        assertTrue(storage.setCreationIntentState(intent.key(),"DEBITING","ESCROWED"));
        assertTrue(storage.activateCreationIntent(intent)>0);
        assertTrue(storage.loadCreationIntents().isEmpty());
        assertEquals(1,storage.loadAllListings().size());
    }

    @Test void failedCreationMovesItemToClaimBeforeDeletingIntent()throws Exception{
        UUID seller=UUID.randomUUID();
        AuctionStorage.CreationIntent intent=storage.insertCreationIntent(seller,
                new ItemStack(Material.EMERALD,7),500,AuctionCurrency.MONEY,10,20,"DEBITING");
        assertTrue(storage.refundCreationIntent(intent.key(),30));
        assertTrue(storage.loadCreationIntents().isEmpty());
        assertEquals(7,storage.loadClaims(seller).getFirst().item().getAmount());
    }

    @Test void staffResolutionIsAtomicAuditedAndIdempotent()throws Exception{
        UUID seller=UUID.randomUUID(),staff=UUID.randomUUID();
        AuctionStorage.CreationIntent intent=storage.insertCreationIntent(seller,
                new ItemStack(Material.DIAMOND,3),250,AuctionCurrency.MONEY,10,20,"DEBITING");
        AuctionStorage.IntentResolution first=storage.resolveCreationIntent(intent.key(),true,staff,"Admin",30);
        assertEquals(AuctionStorage.IntentResolutionStatus.ACTIVATED,first.status());
        assertEquals(1,storage.loadAllListings().size());
        assertEquals("DEBITED",storage.loadIntentAudit(intent.key()).orElseThrow().decision());
        assertEquals(AuctionStorage.IntentResolutionStatus.ALREADY_RESOLVED,
                storage.resolveCreationIntent(intent.key(),true,staff,"Admin",40).status());
        assertEquals(AuctionStorage.IntentResolutionStatus.CONFLICT,
                storage.resolveCreationIntent(intent.key(),false,staff,"Admin",50).status());
        assertEquals(1,storage.loadAllListings().size());
    }

    @Test void notDebitedResolutionReturnsItemToClaims()throws Exception{
        UUID seller=UUID.randomUUID();
        AuctionStorage.CreationIntent intent=storage.insertCreationIntent(seller,
                new ItemStack(Material.EMERALD,4),100,AuctionCurrency.EXP,10,20,"DEBITING");
        assertEquals(AuctionStorage.IntentResolutionStatus.REFUNDED,
                storage.resolveCreationIntent(intent.key(),false,UUID.randomUUID(),"Admin",30).status());
        assertEquals(4,storage.loadClaims(seller).getFirst().item().getAmount());
    }

    @Test void itemNotRemovedResolutionDiscardsTheSavedCopy()throws Exception{
        UUID seller=UUID.randomUUID();
        AuctionStorage.CreationIntent intent=storage.insertCreationIntent(seller,
                new ItemStack(Material.DIAMOND,5),100,AuctionCurrency.MONEY,10,20,"DEBITING");
        AuctionStorage.IntentResolution result=storage.resolveCreationIntent(intent.key(),
                AuctionStorage.IntentResolutionDecision.ITEM_NOT_REMOVED,UUID.randomUUID(),"Admin",30);
        assertEquals(AuctionStorage.IntentResolutionStatus.DISCARDED,result.status());
        assertTrue(storage.loadCreationIntents().isEmpty());
        assertTrue(storage.loadAllListings().isEmpty());
        assertTrue(storage.loadClaims(seller).isEmpty());
        assertEquals("ITEM_NOT_REMOVED",storage.loadIntentAudit(intent.key()).orElseThrow().decision());
    }

    @Test void legacyExpMigratesIntoTheOutboxExactlyOnce()throws Exception{
        UUID player=UUID.randomUUID();
        try(Connection connection=database.getConnection();PreparedStatement statement=connection.prepareStatement(
                "INSERT INTO auction_pending_exp(uuid,levels) VALUES(?,?)")){
            statement.setString(1,player.toString());statement.setInt(2,31);statement.executeUpdate();
        }

        storage.init();

        AuctionStorage.PendingPayout payout=storage.loadPendingPayouts().getFirst();
        assertEquals("legacy-exp:"+player,payout.key());
        assertEquals(player,payout.ownerUuid());
        assertEquals(AuctionCurrency.EXP,payout.currency());
        assertEquals(31,payout.amount());
        assertEquals(0,countRows("auction_pending_exp"));
        storage.init();
        assertEquals(1,storage.loadPendingPayouts().size());
    }

    private int countRows(String table)throws Exception{
        try(Connection connection=database.getConnection();PreparedStatement statement=connection.prepareStatement(
                "SELECT COUNT(*) FROM "+table);ResultSet rows=statement.executeQuery()){
            return rows.next()?rows.getInt(1):0;
        }
    }
}
