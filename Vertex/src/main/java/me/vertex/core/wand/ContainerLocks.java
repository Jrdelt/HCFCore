package me.vertex.core.wand;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import java.util.Set;

/** Locks both halves, not the double chest's fractional midpoint. Read only on the server thread. */
final class ContainerLocks {
    private ContainerLocks() { }
    static Set<Location> locations(Block block){
        if(block.getState() instanceof Container container)return locations(container.getInventory());
        return Set.of(block.getLocation());
    }
    static Set<Location> locations(Inventory inventory){
        if(inventory.getHolder() instanceof DoubleChest chest){
            var left=chest.getLeftSide();var right=chest.getRightSide();
            if(left instanceof Container a&&right instanceof Container b)return Set.of(a.getLocation(),b.getLocation());
        }
        Location location=inventory.getLocation();return location==null?Set.of():Set.of(location);
    }
}
