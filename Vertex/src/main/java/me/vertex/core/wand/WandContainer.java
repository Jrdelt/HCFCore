package me.vertex.core.wand;

import me.vertex.core.collector.ChunkCollectorData;
import me.vertex.core.collector.ChunkCollectorManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * One view over the two very different things a wand can be used on: a
 * vanilla chest, whose contents are {@link ItemStack}s in slots, and a Chunk
 * Collector, whose contents are a material-to-count map.
 *
 * <p>Keeping that difference here means the sell and TNT logic is written
 * once against plain counts rather than twice against two storage shapes.
 */
public abstract class WandContainer {

    /** Totals per material, counting only stacks {@code eligible} accepts. */
    public abstract Map<Material, Integer> contents(Predicate<ItemStack> eligible);

    /** Removes exactly {@code amount}, only ever called with an amount {@link #contents} reported. */
    public abstract void remove(Material material, int amount, Predicate<ItemStack> eligible);

    /** Persists the removal, for storage that is not the block itself. */
    public void commit() {
    }

    /** @return a view of the block, or null when it is not a container a wand can use. */
    public static WandContainer of(Block block, ChunkCollectorManager collectors) {
        if (collectors != null && collectors.readData(block.getLocation()) != null) {
            return new CollectorContainer(block.getLocation(), collectors);
        }
        if (block.getState() instanceof Container container) {
            return new InventoryContainer(container.getInventory());
        }
        return null;
    }

    private static final class InventoryContainer extends WandContainer {
        private final Inventory inventory;

        private InventoryContainer(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Map<Material, Integer> contents(Predicate<ItemStack> eligible) {
            Map<Material, Integer> totals = new LinkedHashMap<>();
            for (ItemStack item : inventory.getContents()) {
                if (item != null && eligible.test(item)) {
                    totals.merge(item.getType(), item.getAmount(), Integer::sum);
                }
            }
            return totals;
        }

        @Override
        public void remove(Material material, int amount, Predicate<ItemStack> eligible) {
            int remaining = amount;
            ItemStack[] contents = inventory.getContents();
            for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
                ItemStack item = contents[slot];
                // Re-tested per slot on purpose: a chest can hold both a plain
                // stack and a named one of the same material, and only the
                // plain one was ever counted or paid for.
                if (item == null || item.getType() != material || !eligible.test(item)) {
                    continue;
                }
                int take = Math.min(remaining, item.getAmount());
                item.setAmount(item.getAmount() - take);
                inventory.setItem(slot, item.getAmount() <= 0 ? null : item);
                remaining -= take;
            }
        }
    }

    private static final class CollectorContainer extends WandContainer {
        private final Location location;
        private final ChunkCollectorManager collectors;
        private final ChunkCollectorData data;

        private CollectorContainer(Location location, ChunkCollectorManager collectors) {
            this.location = location;
            this.collectors = collectors;
            this.data = collectors.readData(location);
        }

        @Override
        public Map<Material, Integer> contents(Predicate<ItemStack> eligible) {
            Map<Material, Integer> totals = new LinkedHashMap<>();
            data.stored().forEach((material, amount) -> {
                // A collector stores bare counts, so the only thing to test is
                // the material itself.
                if (amount > 0 && eligible.test(new ItemStack(material))) {
                    totals.put(material, (int) Math.min(Integer.MAX_VALUE, amount));
                }
            });
            return totals;
        }

        @Override
        public void remove(Material material, int amount, Predicate<ItemStack> eligible) {
            data.setStored(material, Math.max(0L, data.stored(material) - amount));
        }

        @Override
        public void commit() {
            collectors.writeData(location, data);
        }
    }
}
