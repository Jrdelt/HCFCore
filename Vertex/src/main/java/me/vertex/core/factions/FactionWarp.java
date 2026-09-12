package me.vertex.core.factions;

/** A named, faction-owned warp. */
public record FactionWarp(int factionId, String name, FactionData.Home location) { }
