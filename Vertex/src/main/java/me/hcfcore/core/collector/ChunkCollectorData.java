package me.hcfcore.core.collector;

import org.bukkit.Material;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * A single Chunk Collector's runtime state. The owner UUID/faction tag are
 * set once at placement time (mirroring the spawner system's ownership
 * tracking) so a claim change can tell a genuine overclaim apart from the
 * same faction re-confirming land it already owns.
 */
public final class ChunkCollectorData {

    private final Map<Material, Long> stored = new EnumMap<>(Material.class);
    private int upgradeTier;
    private final UUID ownerUuid;
    private final String ownerFactionTag;

    public ChunkCollectorData(int upgradeTier, UUID ownerUuid, String ownerFactionTag) {
        this.upgradeTier = upgradeTier;
        this.ownerUuid = ownerUuid;
        this.ownerFactionTag = ownerFactionTag;
    }

    public Map<Material, Long> stored() {
        return stored;
    }

    public long stored(Material material) {
        return stored.getOrDefault(material, 0L);
    }

    public void setStored(Material material, long amount) {
        if (amount <= 0) {
            stored.remove(material);
        } else {
            stored.put(material, amount);
        }
    }

    public int upgradeTier() {
        return upgradeTier;
    }

    public void setUpgradeTier(int upgradeTier) {
        this.upgradeTier = upgradeTier;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public String ownerFactionTag() {
        return ownerFactionTag;
    }
}
