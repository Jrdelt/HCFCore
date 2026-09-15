package me.vertex.core.resetvault;

import org.bukkit.Location;

/**
 * Represents a registered physical Reset Vault access point in a world.
 */
public record ResetVaultAccessBlock(int id, Location location, String hologramId) {
}
