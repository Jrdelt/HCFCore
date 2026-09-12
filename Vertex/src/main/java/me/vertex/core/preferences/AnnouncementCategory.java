package me.vertex.core.preferences;

/**
 * Player-selectable communication preferences.  The original announcement
 * categories remain here so old stored choices stay valid; interaction
 * categories deliberately share the same durable preference store.
 */
public enum AnnouncementCategory {
    COINFLIPS,
    KOTH,
    OUTPOST,
    MINING,
    SERVER,
    GLOBAL_CHAT,
    TRADE_REQUESTS,
    NOTIFICATIONS,
    PRIVATE_MESSAGES,
    PAYMENTS,
    TELEPORT_REQUESTS
}
