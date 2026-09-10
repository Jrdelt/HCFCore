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
import me.vertex.core.gc.GcStorage;
import me.vertex.core.dupe.DupeStorage;
import me.vertex.core.shop.ShopStorage;
import me.vertex.core.spawner.SpawnerStorage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        TABLES.put("pvptop_log", List.of("id", "faction_id", "points", "source", "actor_uuid", "created_at"));
        TABLES.put("chunk_collectors", List.of("world", "x", "y", "z", "owner_faction", "owner_uuid"));
        TABLES.put("blueprint_builds", List.of("id", "world", "x", "y", "z", "template", "owner_uuid",
                "owner_faction", "current_index", "started_at"));
        TABLES.put("blueprint_cooldowns", List.of("uuid", "available_at"));
        TABLES.put("faction_upgrade_levels", List.of("faction_id", "upgrade_key", "level"));
        TABLES.put("faction_banks", List.of("faction_id", "money", "experience", "tnt"));
        TABLES.put("mine_koths", List.of("mine_id", "owner_faction", "control", "owned_since"));
        TABLES.put("mine_hot_zones", List.of("mine_id", "started_at", "ends_at"));
        TABLES.put("coinflips", List.of("id", "host_uuid", "target_uuid", "type", "amount", "items", "created_at"));
        TABLES.put("coinflip_claims", List.of("id", "winner_uuid", "items", "won_at"));
        TABLES.put("coinflip_bans", List.of("uuid", "banned_until"));
        TABLES.put("coinflip_pending_exp", List.of("uuid", "levels"));
        TABLES.put("coinflip_result_notifications", List.of("id", "recipient_uuid", "won", "created_at"));
        TABLES.put("coinflip_log", List.of("id", "host_uuid", "opponent_uuid", "type", "summary",
                "winner_uuid", "resolved_at", "status", "cancelled_by"));
        TABLES.put("coinflip_pending_matches", List.of("id", "coinflip_id", "opponent_uuid", "items", "requested_at"));
        TABLES.put("shop_stock", List.of("material", "net_volume"));
        TABLES.put("auction_listings", List.of("id", "seller_uuid", "item", "price", "currency", "listed_at", "expires_at"));
        TABLES.put("auction_claims", List.of("id", "owner_uuid", "item", "created_at"));
        TABLES.put("auction_log", List.of("id", "seller_uuid", "buyer_uuid", "item_summary", "price",
                "listed_at", "resolved_at", "status", "cancelled_by"));
        TABLES.put("auction_watchlist", List.of("id", "owner_uuid", "listing_id"));
        TABLES.put("auction_pending_exp", List.of("uuid", "levels"));
        TABLES.put("trade_preferences", List.of("uuid", "accepting"));
        TABLES.put("announcement_preferences", List.of("uuid", "category", "enabled"));
        TABLES.put("trade_escrow", List.of("session_id", "owner_uuid", "items", "money", "experience"));
        TABLES.put("trade_claims", List.of("id", "owner_uuid", "item"));
        TABLES.put("trade_pending_exp", List.of("uuid", "levels"));
        TABLES.put("trade_history", List.of("id", "requester_uuid", "target_uuid", "requester_name", "target_name",
                "requester_items", "target_items", "requester_money", "target_money", "requester_exp", "target_exp", "created_at", "status"));
        TABLES.put("gc_balances", List.of("uuid", "balance", "updated_at"));
        TABLES.put("gc_log", List.of("id", "actor_uuid", "target_uuid", "action", "amount", "balance_after", "note", "created_at"));
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
        TABLES.put("faction_shield_overrides", List.of("faction_id", "forced_state", "frozen_remaining_millis",
                "set_by_uuid", "set_at"));
        TABLES.put("faction_shield_log", List.of("id", "faction_id", "action", "actor_uuid", "details", "created_at"));
    }

    private StorageMigrator() {
    }

    /** Row counts per table, plus the total, as actually copied. */
    public record Result(Map<String, Integer> rowsPerTable, int total) {
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
        ensureSchema(source);
        ensureSchema(target);

        Map<String, Integer> copied = new LinkedHashMap<>();
        int total = 0;

        try (Connection from = source.getConnection();
             Connection to = target.getConnection()) {
            boolean previousAutoCommit = to.getAutoCommit();
            to.setAutoCommit(false);
            try {
                // Wipe first so a re-run is idempotent rather than
                // duplicating every row it already copied last time.
                try (Statement wipe = to.createStatement()) {
                    List<String> reversed = TABLES.keySet().stream().toList().reversed();
                    for (String table : reversed) {
                        wipe.executeUpdate("DELETE FROM " + table);
                    }
                }

                for (Map.Entry<String, List<String>> entry : TABLES.entrySet()) {
                    int rows = copyTable(from, to, entry.getKey(), entry.getValue());
                    copied.put(entry.getKey(), rows);
                    total += rows;
                }
                to.commit();
            } catch (SQLException e) {
                to.rollback();
                throw e;
            } finally {
                to.setAutoCommit(previousAutoCommit);
            }
        }
        return new Result(copied, total);
    }

    private static int copyTable(Connection from, Connection to, String table, List<String> columns)
            throws SQLException {
        String columnList = String.join(", ", columns);
        String placeholders = String.join(", ", columns.stream().map(column -> "?").toList());
        String select = "SELECT " + columnList + " FROM " + table;
        String insert = "INSERT INTO " + table + " (" + columnList + ") VALUES (" + placeholders + ")";

        int rows = 0;
        try (PreparedStatement read = from.prepareStatement(select);
             ResultSet results = read.executeQuery();
             PreparedStatement write = to.prepareStatement(insert)) {
            while (results.next()) {
                for (int i = 1; i <= columns.size(); i++) {
                    // getObject/setObject round-trips every type these
                    // tables use -- including the byte[] blobs -- the same
                    // way on both drivers.
                    write.setObject(i, results.getObject(i));
                }
                write.addBatch();
                rows++;
                if (rows % 500 == 0) {
                    write.executeBatch();
                }
            }
            write.executeBatch();
        }
        return rows;
    }

    /** Creates any missing tables, using whichever dialect that database speaks. */
    private static void ensureSchema(Database database) throws SQLException {
        new SqlStorage(database).init();
        new SpawnerStorage(database).init();
        new ChunkCollectorStorage(database).init();
        new BlueprintStorage(database).init();
        new FactionUpgradeStorage(database).init();
        new FactionBankStorage(database).init();
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
