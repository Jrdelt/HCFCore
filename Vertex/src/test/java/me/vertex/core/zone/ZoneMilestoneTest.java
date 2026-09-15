package me.vertex.core.zone;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ZoneMilestoneTest {
    @Test void nearestFutureMilestoneDoesNotDependOnMapOrder() {
        var milestones = Map.of(1000L,5D,100L,1D,500L,3D);
        assertEquals(100,ZoneManager.nextMilestone(0,milestones));
        assertEquals(500,ZoneManager.nextMilestone(100,milestones));
        assertEquals(1000,ZoneManager.nextMilestone(999,milestones));
        assertEquals(0,ZoneManager.nextMilestone(1000,milestones));
        assertEquals(0,ZoneManager.nextMilestone(0,Map.of()));
        assertEquals(3D,ZoneManager.progressionBoost(700,milestones));
        assertEquals(5D,ZoneManager.progressionBoost(2000,milestones));
    }
}
