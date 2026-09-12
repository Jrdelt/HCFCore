package me.vertex.core.network;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerStateSnapshotTest {
    @BeforeEach void setUp(){MockBukkit.mock();}
    @AfterEach void tearDown(){MockBukkit.unmock();}

    @Test
    void versionTwoRoundTripPreservesExtendedPlayerState()throws Exception{
        PlayerStateSnapshot original=new PlayerStateSnapshot(
                new ItemStack[]{new ItemStack(Material.DIAMOND,7)},new ItemStack[4],null,
                new ItemStack[27],17.5D,16,3.5F,1.25F,22,0.4F,500,
                GameMode.SURVIVAL,false,false,6,4D,80,123,2.5F,
                List.of(new PlayerStateSnapshot.PotionData(PotionEffectType.SPEED.getKey().toString(),
                        200,2,false,true,true)));

        PlayerStateSnapshot decoded=PlayerStateSnapshot.decode(original.encode());
        assertEquals(7,decoded.storage()[0].getAmount());
        assertEquals(6,decoded.heldSlot());
        assertEquals(4D,decoded.absorption());
        assertEquals(80,decoded.fireTicks());
        assertEquals(123,decoded.remainingAir());
        assertEquals(PotionEffectType.SPEED.getKey().toString(),decoded.potionEffects().getFirst().key());
    }
}
