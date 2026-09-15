package me.vertex.core.util;
import org.bukkit.potion.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.*;
class FlightEffectsTest {
    @AfterEach void cleanup(){MockBukkit.unmock();}
    @Test void flightDoesNotReplaceExistingLongPotion(){
        var player=MockBukkit.mock().addPlayer();
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,2000,0,true,true,true));
        FlightEffects.renewSlowFall(player);
        var effect=player.getPotionEffect(PotionEffectType.SLOW_FALLING);
        assertEquals(2000,effect.getDuration());assertTrue(effect.hasParticles());
    }
    @Test void newFlightEffectIsFiniteAndShort(){
        var player=MockBukkit.mock().addPlayer();FlightEffects.renewSlowFall(player);
        var effect=player.getPotionEffect(PotionEffectType.SLOW_FALLING);
        assertFalse(effect.isInfinite());assertEquals(100,effect.getDuration());
    }
}
