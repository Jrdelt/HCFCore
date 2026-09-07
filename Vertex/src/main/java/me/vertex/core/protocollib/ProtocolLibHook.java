package me.vertex.core.protocollib;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin wrapper around ProtocolLib, used only to inject and remove fake
 * (non-player) tab list rows -- the group-header lines the grouped tab
 * list draws above each rank's real player rows. Bukkit's API has no
 * concept of a tab entry that isn't a connected player, so this is the
 * one piece of the tab list that can't be done without a packet library;
 * every call here first confirms ProtocolLib is actually installed, the
 * same defensive pattern every other optional integration in this plugin
 * follows.
 */
public final class ProtocolLibHook {

    private static final EnumSet<EnumWrappers.PlayerInfoAction> ADD_ACTIONS = EnumSet.of(
            EnumWrappers.PlayerInfoAction.ADD_PLAYER,
            EnumWrappers.PlayerInfoAction.UPDATE_LISTED,
            EnumWrappers.PlayerInfoAction.UPDATE_LIST_ORDER,
            EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME);

    private ProtocolLibHook() {
    }

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("ProtocolLib");
    }

    /**
     * Adds or moves a fake tab entry on {@code viewer}'s client: a
     * server-only GameProfile under {@code fakeId} that was never a real
     * connected player, shown with {@code text} and sorted by
     * {@code order} exactly like a real row (lower positive {@code order}
     * shows higher, matching {@code Player#setPlayerListOrder}). No-ops if
     * ProtocolLib isn't installed.
     */
    public static void upsertFakeEntry(Player viewer, UUID fakeId, String fakeUsername, Component text, int order) {
        if (!isAvailable()) {
            return;
        }
        try {
            ProtocolManager manager = ProtocolLibrary.getProtocolManager();
            PacketContainer packet = manager.createPacket(PacketType.Play.Server.PLAYER_INFO);
            packet.getPlayerInfoActions().write(0, ADD_ACTIONS);
            packet.getPlayerInfoDataLists().write(0, List.of(new PlayerInfoData(
                    fakeId,
                    0,
                    true,
                    EnumWrappers.NativeGameMode.SURVIVAL,
                    new WrappedGameProfile(fakeId, fakeUsername),
                    WrappedChatComponent.fromJson(GsonComponentSerializer.gson().serialize(text)),
                    order,
                    null)));
            manager.sendServerPacket(viewer, packet);
        } catch (Exception e) {
            log().log(Level.WARNING, "Failed to send fake tab list entry to " + viewer.getName(), e);
        }
    }

    /** Removes one fake tab entry from {@code viewer}'s client. No-ops if ProtocolLib isn't installed. */
    public static void removeFakeEntry(Player viewer, UUID fakeId) {
        removeFakeEntries(viewer, List.of(fakeId));
    }

    /** Removes any number of fake tab entries from {@code viewer}'s client in one packet. */
    public static void removeFakeEntries(Player viewer, Collection<UUID> fakeIds) {
        if (!isAvailable() || fakeIds.isEmpty()) {
            return;
        }
        try {
            ProtocolManager manager = ProtocolLibrary.getProtocolManager();
            PacketContainer packet = manager.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
            packet.getUUIDLists().write(0, List.copyOf(fakeIds));
            manager.sendServerPacket(viewer, packet);
        } catch (Exception e) {
            log().log(Level.WARNING, "Failed to remove fake tab list entries from " + viewer.getName(), e);
        }
    }

    private static Logger log() {
        return Bukkit.getLogger();
    }
}
