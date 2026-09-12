package me.vertex.core.coinflip;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
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

class CoinflipStorageTest {
    @TempDir Path dataFolder;
    private Database database;private CoinflipStorage storage;
    @BeforeEach void setUp()throws Exception{MockBukkit.mock();database=new Database(new YamlConfiguration(),
            dataFolder.toFile());storage=new CoinflipStorage(database);storage.init();}
    @AfterEach void tearDown(){if(database!=null)database.close();MockBukkit.unmock();}

    @Test void onlyEscrowedCreationIntentCanBecomeACoinflip()throws Exception{
        CoinflipStorage.CreationIntent intent=storage.insertCreationIntent(UUID.randomUUID(),null,
                CoinflipType.MONEY,100,null,10);
        assertEquals(-1,storage.activateCreationIntent(intent));
        assertEquals("PREPARED",storage.loadCreationIntents().getFirst().state());
        assertTrue(storage.setCreationIntentState(intent.key(),"PREPARED","DEBITING"));
        assertTrue(storage.setCreationIntentState(intent.key(),"DEBITING","ESCROWED"));
        assertTrue(storage.activateCreationIntent(intent)>0);
        assertTrue(storage.loadCreationIntents().isEmpty());
        assertEquals(1,storage.loadAllCoinflips().size());
    }

    @Test void staffResolutionIsAtomicAuditedAndIdempotent()throws Exception{
        UUID host=UUID.randomUUID(),staff=UUID.randomUUID();
        CoinflipStorage.CreationIntent intent=storage.insertCreationIntent(host,null,
                CoinflipType.EXP,25,null,10,"DEBITING");
        assertEquals(CoinflipStorage.IntentResolutionStatus.ACTIVATED,
                storage.resolveCreationIntent(intent.key(),true,staff,"Admin",20).status());
        assertEquals(1,storage.loadAllCoinflips().size());
        assertEquals("DEBITED",storage.loadIntentAudit(intent.key()).orElseThrow().decision());
        assertEquals(CoinflipStorage.IntentResolutionStatus.ALREADY_RESOLVED,
                storage.resolveCreationIntent(intent.key(),true,staff,"Admin",30).status());
        assertEquals(CoinflipStorage.IntentResolutionStatus.CONFLICT,
                storage.resolveCreationIntent(intent.key(),false,staff,"Admin",40).status());
        assertEquals(1,storage.loadAllCoinflips().size());
    }

    @Test void notDebitedResolutionDiscardsCurrencyIntent()throws Exception{
        CoinflipStorage.CreationIntent intent=storage.insertCreationIntent(UUID.randomUUID(),null,
                CoinflipType.MONEY,100,null,10,"DEBITING");
        assertEquals(CoinflipStorage.IntentResolutionStatus.DISCARDED,
                storage.resolveCreationIntent(intent.key(),false,UUID.randomUUID(),"Admin",20).status());
        assertTrue(storage.loadCreationIntents().isEmpty());
        assertTrue(storage.loadAllCoinflips().isEmpty());
    }

    @Test void legacyExpMigratesIntoTheOutboxExactlyOnce()throws Exception{
        UUID player=UUID.randomUUID();
        try(Connection connection=database.getConnection();PreparedStatement statement=connection.prepareStatement(
                "INSERT INTO coinflip_pending_exp(uuid,levels) VALUES(?,?)")){
            statement.setString(1,player.toString());statement.setInt(2,44);statement.executeUpdate();
        }

        storage.init();

        CoinflipStorage.PendingPayout payout=storage.loadPendingPayouts().getFirst();
        assertEquals("legacy-exp:"+player,payout.key());
        assertEquals(player,payout.ownerUuid());
        assertEquals(CoinflipType.EXP,payout.currency());
        assertEquals(44,payout.amount());
        assertEquals(0,countRows("coinflip_pending_exp"));
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
