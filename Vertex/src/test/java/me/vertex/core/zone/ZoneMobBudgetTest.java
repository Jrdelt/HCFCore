package me.vertex.core.zone;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZoneMobBudgetTest {
    @Test void localTargetUsesConfiguredBaseAndAdditionalPlayersAndCap(){
        var config=ZoneManager.ZoneConfig.defaults(ZoneType.HAVEN);
        assertEquals(0,ZoneManager.localMobTarget(config,0));
        assertEquals(60,ZoneManager.localMobTarget(config,1));
        assertEquals(92,ZoneManager.localMobTarget(config,2));
        assertEquals(400,ZoneManager.localMobTarget(config,Integer.MAX_VALUE));
    }
}
