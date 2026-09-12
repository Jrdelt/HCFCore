package me.vertex.core.factions;

import java.util.UUID;

/** Persistent membership row. */
public record FactionMember(UUID playerUuid, int factionId, FactionRole role, String lastName, long joinedAtMillis) {
    public FactionMember withRole(FactionRole value) { return new FactionMember(playerUuid, factionId, value, lastName, joinedAtMillis); }
    public FactionMember withName(String value) { return new FactionMember(playerUuid, factionId, role, value, joinedAtMillis); }
}
