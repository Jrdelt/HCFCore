package me.vertex.core.storage;

import me.vertex.core.auction.AuctionStorage;
import me.vertex.core.blueprint.BlueprintStorage;
import me.vertex.core.coinflip.CoinflipStorage;
import me.vertex.core.preferences.AnnouncementPreferenceStorage;
import me.vertex.core.trade.TradeStorage;
import me.vertex.core.collector.ChunkCollectorStorage;
import me.vertex.core.faction.FactionUpgradeStorage;
import me.vertex.core.faction.FactionBankStorage;
import me.vertex.core.faction.FTopStorage;
import me.vertex.core.faction.PvpTopStorage;
import me.vertex.core.factions.FactionStorage;
import me.vertex.core.gc.GcStorage;
import me.vertex.core.dupe.DupeStorage;
import me.vertex.core.shop.ShopStorage;
import me.vertex.core.spawner.SpawnerStorage;
import me.vertex.core.stats.PlayerStatsStorage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Copies every row this plugin owns from one database backend to the
 * other, so switching between the local SQLite file and a MySQL server
 * doesn't mean abandoning the data already in the old one.
 *
 * <p>Blueprint ids are copied too: active beacon PDC records reference that
 * id, so regenerating it would make a resumed build look detached from its
 * anchor. Everything else is copied verbatim, including the serialized ItemStack blobs in the death
 * history, which are engine-independent bytes.
 */
public final class StorageMigrator {

    /**
     * Every table, with the columns worth copying, in an order that could
     * carry foreign keys if any were ever added.
     */
    private static final Map<String, List<String>> TABLES = new LinkedHashMap<>();

    static {
        // Native faction tables come first because several Vertex systems
        // reference their stable faction ids.
        TABLES.put("vertex_factions", List.of("id", "tag", "tag_key", "description", "open_join", "system_faction", "money", "power", "power_max", "created_at", "home_shard", "home_world", "home_x", "home_y", "home_z", "home_yaw", "home_pitch"));
        TABLES.put("vertex_faction_members", List.of("player_uuid", "faction_id", "role", "last_name", "joined_at"));
        TABLES.put("vertex_faction_claims", List.of("world", "chunk_x", "chunk_z", "faction_id"));
        TABLES.put("vertex_faction_relations", List.of("faction_id", "target_faction_id", "relation"));
        TABLES.put("vertex_faction_permissions", List.of("faction_id", "role", "action_key", "allowed"));
        TABLES.put("vertex_faction_warps", List.of("faction_id", "name", "shard_id", "world", "x", "y", "z", "yaw", "pitch"));
        TABLES.put("vertex_faction_invites", List.of("faction_id", "player_uuid", "invited_by", "expires_at"));
        TABLES.put("vertex_faction_players", List.of("player_uuid", "chat_mode", "autoclaim", "map_enabled"));
        TABLES.put("vertex_player_power", List.of("player_uuid", "current_power", "max_power", "next_regen_at"));
        TABLES.put("vertex_player_stats", List.of("player_uuid", "kills", "deaths", "updated_at"));
        TABLES.put("vertex_faction_player_cooldowns", List.of("player_uuid", "create_available_at"));
        TABLES.put("vertex_faction_cooldowns", List.of("faction_id", "rename_available_at"));
        TABLES.put("vertex_faction_archives", List.of("faction_id", "tag", "disbanded_at", "expires_at"));
        TABLES.put("vertex_faction_relation_requests", List.of("requester_id", "target_id", "relation", "expires_at", "requested_by"));
        TABLES.put("vertex_faction_bans", List.of("faction_id", "player_uuid", "player_name", "banned_by", "banned_at"));
        TABLES.put("vertex_faction_focuses", List.of("source_faction_id", "target_faction_id", "expires_at", "set_by"));
        TABLES.put("vertex_faction_logs", List.of("id", "faction_id", "action", "actor_uuid", "actor_name", "details", "created_at"));
        TABLES.put("vertex_faction_vaults", List.of("faction_id", "contents", "updated_at"));
        // Vault leases are deliberately not migrated. They are ephemeral
        // process coordination records and carrying one to a new backend
        // would strand the vault behind a viewer that is not connected there.
        TABLES.put("vertex_faction_admin_audit", List.of("id", "actor_uuid", "actor_name", "action", "target", "details", "success", "created_at"));
        TABLES.put("kit_cooldowns", List.of("uuid", "kit_name", "available_at"));
        TABLES.put("ability_cooldowns", List.of("uuid", "ability_id", "available_at"));
        TABLES.put("user_locale", List.of("uuid", "locale"));
        TABLES.put("player_deaths", List.of("uuid", "timestamp", "cause", "killer_name",
                "items", "helmet", "chestplate", "leggings", "boots", "offhand"));
        TABLES.put("spawners", List.of("world", "x", "y", "z", "mob_type", "stack_size", "owner_faction",
                "placed_at_data"));
        TABLES.put("ftop_scores", List.of("faction_id", "current_value", "previous_rank", "current_rank", "updated_at"));
        TABLES.put("ftop_schedule", List.of("singleton_key", "next_update_at"));
        TABLES.put("pvptop_points", List.of("faction_id", "points", "updated_at"));
        TABLES.put("pvptop_log", List.of("id", "faction_id", "points", "source", "operation_key",
                "actor_uuid", "created_at"));
        TABLES.put("chunk_collectors", List.of("world", "x", "y", "z", "owner_faction", "owner_uuid"));
        TABLES.put("blueprint_builds", List.of("id", "world", "x", "y", "z", "template", "owner_uuid",
                "owner_faction", "current_index", "started_at"));
        TABLES.put("blueprint_cooldowns", List.of("uuid", "available_at"));
        TABLES.put("faction_upgrade_levels", List.of("faction_id", "upgrade_key", "level"));
        TABLES.put("faction_banks", List.of("faction_id", "money", "experience", "tnt"));
        TABLES.put("mine_koths", List.of("mine_id", "owner_faction", "control", "owned_since"));
        TABLES.put("mine_hot_zones", List.of("mine_id", "started_at", "ends_at"));
        TABLES.put("coinflips", List.of("id", "host_uuid", "target_uuid", "type", "amount", "items", "created_at"));
        TABLES.put("coinflip_claims", List.of("id", "winner_uuid", "items", "won_at", "state",
                "reservation_token", "reserved_at"));
        TABLES.put("coinflip_bans", List.of("uuid", "banned_until"));
        TABLES.put("coinflip_pending_exp", List.of("uuid", "levels"));
        TABLES.put("coinflip_result_notifications", List.of("id", "recipient_uuid", "won", "created_at"));
        TABLES.put("coinflip_log", List.of("id", "host_uuid", "opponent_uuid", "type", "summary",
                "winner_uuid", "resolved_at", "status", "cancelled_by"));
        TABLES.put("coinflip_pending_matches", List.of("id", "coinflip_id", "opponent_uuid", "items", "requested_at"));
        TABLES.put("coinflip_pending_payouts", List.of("payout_key", "owner_uuid", "currency", "amount",
                "created_at", "state", "delivery_started_at"));
        TABLES.put("coinflip_creation_intents", List.of("intent_key", "host_uuid", "target_uuid", "type",
                "amount", "items", "created_at", "state"));
        TABLES.put("coinflip_creation_intent_audit", List.of("intent_key", "host_uuid", "target_uuid", "type",
                "amount", "decision", "actor_uuid", "actor_name", "resolved_at"));
        TABLES.put("shop_stock", List.of("material", "net_volume"));
        TABLES.put("auction_listings", List.of("id", "seller_uuid", "item", "price", "currency", "listed_at", "expires_at"));
        TABLES.put("auction_claims", List.of("id", "owner_uuid", "item", "created_at", "state",
                "reservation_token", "reserved_at"));
        TABLES.put("auction_log", List.of("id", "seller_uuid", "buyer_uuid", "item_summary", "price",
                "listed_at", "resolved_at", "status", "cancelled_by"));
        TABLES.put("auction_watchlist", List.of("id", "owner_uuid", "listing_id"));
        TABLES.put("auction_pending_exp", List.of("uuid", "levels"));
        TABLES.put("auction_pending_payouts", List.of("payout_key", "owner_uuid", "currency", "amount",
                "created_at", "state", "delivery_started_at"));
        TABLES.put("auction_creation_intents", List.of("intent_key", "seller_uuid", "item", "price",
                "currency", "listed_at", "expires_at", "state"));
        TABLES.put("auction_creation_intent_audit", List.of("intent_key", "seller_uuid", "item_summary", "price",
                "currency", "decision", "actor_uuid", "actor_name", "resolved_at"));
        TABLES.put("announcement_preferences", List.of("uuid", "category", "enabled"));
        TABLES.put("trade_escrow", List.of("session_id", "owner_uuid", "items", "money", "experience"));
        TABLES.put("trade_owners", List.of("shard_id", "boot_id", "expires_at"));
        TABLES.put("trade_sessions", List.of("session_id", "owner_shard", "boot_id", "state"));
        TABLES.put("trade_claims", List.of("id", "owner_uuid", "item", "state", "reservation_token",
                "reserved_at"));
        TABLES.put("trade_pending_exp", List.of("uuid", "levels"));
        TABLES.put("trade_pending_money", List.of("uuid", "amount"));
        TABLES.put("trade_pending_payouts", List.of("payout_key", "owner_uuid", "currency", "amount",
                "created_at", "state", "delivery_started_at"));
        TABLES.put("trade_history", List.of("id", "requester_uuid", "target_uuid", "requester_name", "target_name",
                "requester_items", "target_items", "requester_money", "target_money", "requester_exp", "target_exp", "created_at", "status"));
        TABLES.put("gc_balances", List.of("uuid", "balance", "updated_at"));
        TABLES.put("gc_log", List.of("id", "actor_uuid", "target_uuid", "action", "amount", "balance_after",
                "note", "operation_key", "created_at"));
        TABLES.put("gc_redeem_codes", List.of("code", "amount", "uses_remaining", "created_by_uuid", "created_at",
                "expires_at", "status"));
        TABLES.put("dupe_cases", List.of("id", "fingerprint", "holder_uuid", "holder_name", "item_id", "material",
                "source", "details", "status", "created_at", "resolved_by", "resolved_at", "resolution"));
        TABLES.put("base_claims", List.of("faction_id", "slot_index", "anchor_world", "anchor_x", "anchor_z", "created_at"));
        TABLES.put("base_claim_region_chunks", List.of("faction_id", "slot_index", "world", "chunk_x", "chunk_z"));
        TABLES.put("base_claim_slot_purchases", List.of("faction_id", "slot_index", "purchased_at"));
        TABLES.put("raid_claim_expirations", List.of("world", "chunk_x", "chunk_z", "faction_id", "expires_at_millis"));
        TABLES.put("faction_shields", List.of("faction_id", "schedule_start_minute", "schedule_duration_minutes",
                "pending_start_minute", "pending_duration_minutes", "pending_activates_at", "frozen_resume_until",
                "new_faction_eligible_at"));
        TABLES.put("faction_shield_activations", List.of("faction_id", "active_until", "cooldown_until",
                "eligible_at", "activated_at", "activated_by_uuid"));
        TABLES.put("faction_shield_overrides", List.of("faction_id", "forced_state", "frozen_remaining_millis",
                "set_by_uuid", "set_at"));
        TABLES.put("faction_shield_log", List.of("id", "faction_id", "action", "actor_uuid", "details", "created_at"));
        TABLES.put("faction_shield_weekly", List.of("faction_id", "current_schedule", "current_pvp", "pending_schedule", "pending_pvp", "pending_activates_at", "edit_locked_until", "updated_by_uuid"));
        TABLES.put("faction_grace", List.of("singleton_key", "active_until", "updated_at", "updated_by_uuid"));
        TABLES.put("faction_grace_log", List.of("id", "action", "actor_uuid", "details", "created_at"));
        TABLES.put("chunk_buster_operations", List.of("id", "world", "chunk_x", "chunk_z", "type", "started_at", "status"));
        TABLES.put("chunk_buster_role_permissions", List.of("faction_id", "role", "allowed"));
        TABLES.put("chunk_buster_log", List.of("id", "player_uuid", "type", "world", "x", "y", "z", "created_at"));
        TABLES.put("zone_regions", List.of("id", "type", "world", "min_x", "min_y", "min_z", "max_x",
                "max_y", "max_z"));
        TABLES.put("zone_routes", List.of("id", "region_id", "enabled", "speed", "auto_drop", "waypoints"));
        TABLES.put("zone_loot", List.of("zone_type", "position", "item", "chance"));
        TABLES.put("zone_players", List.of("player_uuid", "last_name", "haven_kills", "riftlands_kills",
                "haven_cooldown_until", "riftlands_cooldown_until", "rift_session_id", "winner_boost",
                "winner_cycle"));
        TABLES.put("zone_event_scores", List.of("event_start", "player_uuid", "player_name", "score",
                "reached_at"));
        TABLES.put("zone_event_state", List.of("state_key", "value"));
        TABLES.put("zone_event_runs", List.of("event_start", "ends_at", "settlement_delay", "finalized_at",
                "first_boost", "second_boost", "third_boost"));
        TABLES.put("zone_event_score_ops", List.of("operation_id", "event_start", "player_uuid", "points", "scored_at"));
        TABLES.put("zone_event_winners", List.of("event_start", "player_uuid", "player_name", "place", "boost"));
        TABLES.put("zone_flight_returns", List.of("player_uuid", "zone_region", "world", "x", "y", "z"));
        TABLES.put("entry_portals", List.of("id", "target", "world", "min_x", "min_y", "min_z", "max_x",
                "max_y", "max_z"));
        TABLES.put("entry_portal_routes", List.of("id", "target", "speed", "waypoints"));
        TABLES.put("item_delivery_inbox", List.of("delivery_id", "owner_uuid", "source", "item", "state",
                "reservation_token", "created_at"));
        TABLES.put("vertex_network_shards", List.of("shard_id", "role", "state", "max_players", "current_players", "heartbeat_at", "restart_eta", "updated_by"));
        TABLES.put("vertex_network_events", List.of("id", "source_shard", "topic", "payload", "created_at"));
        TABLES.put("vertex_transfer_handoffs", List.of("transfer_id", "player_uuid", "source_shard", "destination_shard", "destination_world", "x", "y", "z", "yaw", "pitch", "reason", "state", "snapshot", "created_at", "updated_at", "error"));
        TABLES.put("vertex_transfer_locks", List.of("player_uuid", "transfer_kind", "reference_id",
                "created_at"));
        TABLES.put("vertex_queued_transfers", List.of("player_uuid", "source_shard", "destination_shard", "destination_world", "x", "y", "z", "yaw", "pitch", "reason", "expires_at", "created_at"));
        TABLES.put("vertex_global_locations", List.of("location_type", "location_name", "shard_id", "world", "x", "y", "z", "yaw", "pitch", "description", "revision"));
        TABLES.put("vertex_teleport_cooldowns", List.of("player_uuid", "cooldown_type", "available_at"));
        TABLES.put("vertex_network_state_history", List.of("snapshot_id", "player_uuid", "shard_id", "reason", "snapshot", "created_at"));
        TABLES.put("vertex_rtp_requests", List.of("request_id", "player_uuid", "source_shard", "destination_shard", "destination_world", "world_size", "claim_buffer", "attempts", "state", "result_x", "result_y", "result_z", "created_at", "updated_at", "error"));
    }

    private StorageMigrator() {
    }

    /** Row counts and content checksums, as verified before target commit. */
    public record Result(Map<String, Integer> rowsPerTable, int total, Map<String, String> checksums) {
    }

    /** @return how many rows the target database already holds, across every table. */
    public static int countRows(Database database) throws SQLException {
        ensureSchema(database);
        int total = 0;
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            for (String table : TABLES.keySet()) {
                try (ResultSet results = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    if (results.next()) {
                        total += results.getInt(1);
                    }
                }
            }
        }
        return total;
    }

    /**
     * Copies everything from {@code source} into {@code target},
     * replacing whatever the target held. Both databases must already be
     * reachable; the target's schema is created if it doesn't exist yet.
     */
    public static Result migrate(Database source, Database target) throws SQLException {
        prepare(source, target);
        try (Database.ExclusiveLease lease =
                     source.beginExclusiveMaintenance(30L, TimeUnit.SECONDS)) {
            return migrate(source, target, lease);
        }
    }

    /** Creates/upgrades both schemas before the source maintenance gate closes. */
    public static void prepare(Database source, Database target) throws SQLException {
        ensureSchema(source);
        ensureSchema(target);
    }

    /**
     * Copies one repeatable source snapshot while the caller owns the source
     * database's exclusive lease. Row counts and content checksums are checked
     * inside the target transaction before it is committed.
     */
    public static Result migrate(Database source, Database target, Database.ExclusiveLease lease)
            throws SQLException {
        if (lease == null || !lease.belongsTo(source)) {
            throw new SQLException("Storage migration requires the source database's active exclusive lease");
        }
        Map<String, Integer> copied = new LinkedHashMap<>();
        Map<String, String> checksums = new LinkedHashMap<>();
        int total = 0;

        try (Connection from = lease.openConnection();
             Connection to = target.getConnection()) {
            int sourceIsolation = from.getTransactionIsolation();
            boolean sourceAutoCommit = from.getAutoCommit();
            boolean targetAutoCommit = to.getAutoCommit();
            from.setTransactionIsolation(source.dialect() == Database.Dialect.MYSQL
                    ? Connection.TRANSACTION_REPEATABLE_READ
                    : Connection.TRANSACTION_SERIALIZABLE);
            from.setAutoCommit(false);
            to.setAutoCommit(false);
            try {
                try (Statement wipe = to.createStatement()) {
                    List<String> reversed = TABLES.keySet().stream().toList().reversed();
                    for (String table : reversed) {
                        wipe.executeUpdate("DELETE FROM " + table);
                    }
                }

                for (Map.Entry<String, List<String>> entry : TABLES.entrySet()) {
                    TableDigest digest = copyTable(from, to, entry.getKey(), entry.getValue());
                    copied.put(entry.getKey(), digest.rows());
                    checksums.put(entry.getKey(), digest.checksum());
                    total += digest.rows();
                }

                for (Map.Entry<String, List<String>> entry : TABLES.entrySet()) {
                    TableDigest targetDigest = inspectTable(to, entry.getKey(), entry.getValue());
                    int expectedRows = copied.get(entry.getKey());
                    String expectedChecksum = checksums.get(entry.getKey());
                    if (targetDigest.rows() != expectedRows
                            || !targetDigest.checksum().equals(expectedChecksum)) {
                        throw new SQLException("Verification failed for " + entry.getKey()
                                + ": expected " + expectedRows + " row(s) / " + expectedChecksum
                                + ", found " + targetDigest.rows() + " row(s) / "
                                + targetDigest.checksum());
                    }
                }
                to.commit();
            } catch (SQLException | RuntimeException error) {
                to.rollback();
                throw error;
            } finally {
                try {
                    from.rollback();
                } finally {
                    from.setAutoCommit(sourceAutoCommit);
                    from.setTransactionIsolation(sourceIsolation);
                    to.setAutoCommit(targetAutoCommit);
                }
            }
        }
        return new Result(Map.copyOf(copied), total, Map.copyOf(checksums));
    }

    private static TableDigest copyTable(Connection from, Connection to, String table, List<String> columns)
            throws SQLException {
        String columnList = String.join(", ", columns);
        String placeholders = String.join(", ", columns.stream().map(column -> "?").toList());
        String select = "SELECT " + columnList + " FROM " + table;
        String insert = "INSERT INTO " + table + " (" + columnList + ") VALUES (" + placeholders + ")";

        int rows = 0;
        List<byte[]> rowDigests = new ArrayList<>();
        try (PreparedStatement read = from.prepareStatement(select);
             ResultSet results = read.executeQuery();
             PreparedStatement write = to.prepareStatement(insert)) {
            while (results.next()) {
                MessageDigest rowDigest = sha256();
                for (int i = 1; i <= columns.size(); i++) {
                    Object value = results.getObject(i);
                    write.setObject(i, value);
                    updateDigest(rowDigest, value);
                }
                rowDigests.add(rowDigest.digest());
                write.addBatch();
                rows++;
                if (rows % 500 == 0) {
                    write.executeBatch();
                }
            }
            write.executeBatch();
        }
        return new TableDigest(rows, digestRows(rowDigests));
    }

    private static TableDigest inspectTable(Connection connection, String table, List<String> columns)
            throws SQLException {
        String select = "SELECT " + String.join(", ", columns) + " FROM " + table;
        int rows = 0;
        List<byte[]> rowDigests = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(select);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                MessageDigest rowDigest = sha256();
                for (int i = 1; i <= columns.size(); i++) {
                    updateDigest(rowDigest, results.getObject(i));
                }
                rowDigests.add(rowDigest.digest());
                rows++;
            }
        }
        return new TableDigest(rows, digestRows(rowDigests));
    }

    private static String digestRows(List<byte[]> rows) throws SQLException {
        rows.sort(Arrays::compareUnsigned);
        MessageDigest tableDigest = sha256();
        for (byte[] row : rows) {
            tableDigest.update(row);
        }
        return HexFormat.of().formatHex(tableDigest.digest());
    }

    private static void updateDigest(MessageDigest digest, Object value) throws SQLException {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes;
        if (value instanceof byte[] binary) {
            bytes = binary;
        } else if (value instanceof Blob blob) {
            bytes = blob.getBytes(1L, Math.toIntExact(blob.length()));
        } else if (value instanceof Clob clob) {
            bytes = clob.getSubString(1L, Math.toIntExact(clob.length())).getBytes(StandardCharsets.UTF_8);
        } else if (value instanceof Boolean bool) {
            bytes = (bool ? "1" : "0").getBytes(StandardCharsets.UTF_8);
        } else if (value instanceof Number number) {
            try {
                bytes = new BigDecimal(number.toString()).stripTrailingZeros().toPlainString()
                        .getBytes(StandardCharsets.UTF_8);
            } catch (NumberFormatException ignored) {
                bytes = number.toString().getBytes(StandardCharsets.UTF_8);
            }
        } else {
            bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        }
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() throws SQLException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new SQLException("SHA-256 is not available", impossible);
        }
    }

    private record TableDigest(int rows, String checksum) {
    }

    /** Creates any missing tables, using whichever dialect that database speaks. */
    private static void ensureSchema(Database database) throws SQLException {
        new SqlStorage(database).init();
        new FactionStorage(database).init();
        new me.vertex.core.factions.FactionSocialStorage(database).init();
        new me.vertex.core.factions.AdminAuditStorage(database).init();
        new SpawnerStorage(database).init();
        new ChunkCollectorStorage(database).init();
        new BlueprintStorage(database).init();
        new FactionUpgradeStorage(database).init();
        new FactionBankStorage(database).init();
        new me.vertex.core.faction.FactionVaultStorage(database).init();
        new FTopStorage(database).init();
        new PvpTopStorage(database).init();
        new me.vertex.core.mine.MineKothStorage(database).init();
        new me.vertex.core.mine.HotZoneStorage(database).init();
        new CoinflipStorage(database).init();
        new ShopStorage(database).init();
        new AuctionStorage(database).init();
        new TradeStorage(database).init();
        new AnnouncementPreferenceStorage(database).init();
        new GcStorage(database).init();
        new DupeStorage(database).init();
        new me.vertex.core.claims.ClaimStorage(database).init();
        new me.vertex.core.shield.ShieldStorage(database).init();
        new me.vertex.core.grace.GraceStorage(database).init();
        new me.vertex.core.chunkbuster.ChunkBusterStorage(database).init();
        new me.vertex.core.zone.ZoneStorage(database).init();
        new me.vertex.core.portal.PortalStorage(database).init();
        new DeliveryStorage(database).init();
        new me.vertex.core.network.NetworkStorage(database).init();
        new me.vertex.core.teleport.RtpStorage(database).init();
        new PlayerStatsStorage(database).init();
    }

    /**
     * Rewrites just the {@code storage.type} value in config.yml, in
     * place. Deliberately a line edit rather than
     * {@code plugin.saveConfig()}: Bukkit's own writer drops every comment
     * in the file, and this config's comments are most of its
     * documentation.
     */
    public static void writeStorageType(File configFile, String type) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8));

        boolean inStorageSection = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String withoutComment = line.split("#", 2)[0];

            if (inStorageSection) {
                // A new top-level key ends the storage block.
                if (!withoutComment.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                    break;
                }
                java.util.regex.Matcher matcher = TYPE_LINE.matcher(line);
                if (matcher.matches()) {
                    lines.set(i, matcher.group(1) + "type: " + type + matcher.group(3));
                    writeAtomically(configFile.toPath(), lines);
                    return;
                }
            } else if (withoutComment.stripTrailing().equals("storage:")) {
                inStorageSection = true;
            }
        }

        // No storage.type present (an older config) -- add the block.
        lines.add("");
        lines.add("storage:");
        lines.add("  type: " + type);
        writeAtomically(configFile.toPath(), lines);
    }

    /**
     * Saves an edited configuration as a complete sibling file before a
     * single atomic replacement. A power loss can therefore leave either
     * the old, valid config or the new, valid config -- never a truncated
     * halfway-written one.
     */
    private static void writeAtomically(java.nio.file.Path target, List<String> lines) throws IOException {
        java.nio.file.Path absoluteTarget = target.toAbsolutePath();
        java.nio.file.Path directory = absoluteTarget.getParent();
        if (directory == null) {
            throw new IOException("Cannot determine parent directory for " + target);
        }
        java.nio.file.Path temporary = Files.createTempFile(directory,
                absoluteTarget.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, absoluteTarget,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static final java.util.regex.Pattern TYPE_LINE =
            java.util.regex.Pattern.compile("^(\\s+)type:\\s*[^#]*?(\\s*)(#.*)?$");
}
