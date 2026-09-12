package me.vertex.core.zone;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Shared event ledger. All SQL work runs off the tick thread after startup. */
public final class ZoneEventStorage {
    private final Database database;
    private final LongSupplier testClock;

    public ZoneEventStorage(Database database) { this(database, null); }
    ZoneEventStorage(Database database, LongSupplier testClock) {
        this.database = database;
        this.testClock = testClock;
    }

    public record Settings(long cycle, long duration, long settlementDelay,
                           double first, double second, double third) {
        public Settings {
            if (cycle < 60_000 || duration <= 0 || duration > cycle
                    || settlementDelay < 1_000 || settlementDelay > 60_000
                    || !validBonus(first) || !validBonus(second) || !validBonus(third))
                throw new IllegalArgumentException("Invalid zone event timing or rewards");
        }
        private static boolean validBonus(double value) { return Double.isFinite(value) && value >= 0; }
    }
    public record Winner(UUID uuid, String name, int place, double boost) { }
    public record Snapshot(long anchor, long start, long end, long databaseNow,
                           List<ZoneStorage.ScoreRow> scores, long winnerCycle, List<Winner> winners) { }
    private record Run(long start, long end, long delay, boolean finished,
                       double first, double second, double third) { }

    public void init() throws SQLException {
        try (Connection c = database.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS zone_event_runs (event_start BIGINT PRIMARY KEY, ends_at BIGINT NOT NULL, settlement_delay BIGINT NOT NULL, finalized_at BIGINT, first_boost DOUBLE NOT NULL, second_boost DOUBLE NOT NULL, third_boost DOUBLE NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS zone_event_score_ops (operation_id VARCHAR(36) PRIMARY KEY, event_start BIGINT NOT NULL, player_uuid VARCHAR(36) NOT NULL, points DOUBLE NOT NULL, scored_at BIGINT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS zone_event_winners (event_start BIGINT NOT NULL, player_uuid VARCHAR(36) NOT NULL, player_name VARCHAR(32) NOT NULL, place INT NOT NULL, boost DOUBLE NOT NULL, PRIMARY KEY(event_start,player_uuid))");
        }
    }

    /** Add a delta once; a retry or second shard cannot overwrite another shard's points. */
    public boolean addScore(UUID operation, long start, UUID player, String name,
                            double delta, long scoredAt) throws SQLException {
        return addScore(operation, start, player, name, delta, scoredAt, null);
    }

    public boolean addScore(UUID operation, long start, UUID player, String name,
                            double delta, long scoredAt, Settings settings) throws SQLException {
        if (!Double.isFinite(delta) || delta <= 0) throw new IllegalArgumentException("Invalid zone score");
        return transaction(c -> {
            try (var s = c.prepareStatement("SELECT event_start,player_uuid,points FROM zone_event_score_ops WHERE operation_id=?")) {
                s.setString(1, operation.toString());
                try (var r = s.executeQuery()) {
                    if (r.next()) {
                        if (r.getLong(1) != start || !r.getString(2).equals(player.toString()) || r.getDouble(3) != delta)
                            throw new SQLException("Zone score operation reused with different contents: " + operation);
                        return false;
                    }
                }
            }
            Run run = run(c, start);
            // The first kill of a new cycle need not wait for a scoreboard refresh.
            if (run == null && settings != null) {
                long anchor = anchor(c, now(c), settings.cycle);
                long expected = anchor + Math.floorDiv(scoredAt - anchor, settings.cycle) * settings.cycle;
                if (start == expected) { ensureRun(c, start, settings); run = run(c, start); }
            }
            if (run == null || run.finished || scoredAt < start || scoredAt >= run.end
                    || now(c) >= run.end + run.delay)
                throw new ClosedEventException(start);
            double previous = 0;
            long reached = scoredAt;
            try (var s = c.prepareStatement("SELECT score,reached_at FROM zone_event_scores WHERE event_start=? AND player_uuid=?")) {
                s.setLong(1, start); s.setString(2, player.toString());
                try (var r = s.executeQuery()) {
                    if (r.next()) { previous = r.getDouble(1); reached = Math.max(reached, r.getLong(2)); }
                }
            }
            double total = previous + delta;
            if (!Double.isFinite(total) || total <= previous) throw new SQLException("Zone score overflow/precision loss");
            String sql = "INSERT INTO zone_event_scores (event_start,player_uuid,player_name,score,reached_at) VALUES (?,?,?,?,?) "
                    + (mysql() ? "ON DUPLICATE KEY UPDATE player_name=VALUES(player_name),score=VALUES(score),reached_at=VALUES(reached_at)"
                    : "ON CONFLICT(event_start,player_uuid) DO UPDATE SET player_name=excluded.player_name,score=excluded.score,reached_at=excluded.reached_at");
            try (var s = c.prepareStatement(sql)) {
                s.setLong(1, start); s.setString(2, player.toString()); s.setString(3, name);
                s.setDouble(4, total); s.setLong(5, reached); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO zone_event_score_ops (operation_id,event_start,player_uuid,points,scored_at) VALUES (?,?,?,?,?)")) {
                s.setString(1, operation.toString()); s.setLong(2, start); s.setString(3, player.toString());
                s.setDouble(4, delta); s.setLong(5, scoredAt); s.executeUpdate();
            }
            return true;
        });
    }

    /** Finalize from durable global scores, then read a coherent projection on every shard. */
    public Snapshot refresh(Settings settings) throws SQLException {
        return transaction(c -> {
            long now = now(c);
            long anchor = anchor(c, now, settings.cycle);
            long start = anchor + Math.floorDiv(now - anchor, settings.cycle) * settings.cycle;
            ensureRun(c, start, settings);
            // Bounded catch-up after downtime; later polls finish any remaining overdue runs.
            List<Long> due = new ArrayList<>();
            try (var s = c.prepareStatement("SELECT event_start FROM zone_event_runs WHERE finalized_at IS NULL AND ends_at+settlement_delay<=? ORDER BY event_start LIMIT 4")) {
                s.setLong(1, now);
                try (var r = s.executeQuery()) { while (r.next()) due.add(r.getLong(1)); }
            }
            for (long event : due) finish(c, run(c, event), now);
            long winnerCycle = Long.MIN_VALUE;
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT MAX(event_start) FROM zone_event_runs WHERE finalized_at IS NOT NULL")) {
                if (r.next()) { long value = r.getLong(1); if (!r.wasNull()) winnerCycle = value; }
            }
            List<Winner> winners = new ArrayList<>();
            if (winnerCycle != Long.MIN_VALUE) try (var s = c.prepareStatement("SELECT player_uuid,player_name,place,boost FROM zone_event_winners WHERE event_start=? ORDER BY place")) {
                s.setLong(1, winnerCycle);
                try (var r = s.executeQuery()) { while (r.next()) winners.add(new Winner(UUID.fromString(r.getString(1)), r.getString(2), r.getInt(3), r.getDouble(4))); }
            }
            return new Snapshot(anchor, start, run(c, start).end, now, scores(c, start), winnerCycle, List.copyOf(winners));
        });
    }

    /** Administrative changes use one durable operation timestamp so persistence retries are safe. */
    public void start(long requestedStart, Settings settings) throws SQLException {
        transaction(c -> {
            long current = anchor(c, now(c), settings.cycle);
            if (current >= requestedStart) return null;
            try (var s = c.prepareStatement("UPDATE zone_event_runs SET ends_at=? WHERE finalized_at IS NULL AND ends_at>? AND event_start<?")) {
                s.setLong(1, requestedStart); s.setLong(2, requestedStart); s.setLong(3, requestedStart); s.executeUpdate();
            }
            try (var s = c.prepareStatement("UPDATE zone_event_state SET value=? WHERE state_key='cycle_anchor'")) {
                s.setLong(1, requestedStart); s.executeUpdate();
            }
            ensureRun(c, requestedStart, settings);
            return null;
        });
    }

    public void stop(long start, long stoppedAt) throws SQLException {
        transaction(c -> {
            try (var s = c.prepareStatement("UPDATE zone_event_runs SET ends_at=? WHERE event_start=? AND finalized_at IS NULL AND ends_at>?")) {
                s.setLong(1, Math.max(start, stoppedAt)); s.setLong(2, start); s.setLong(3, stoppedAt); s.executeUpdate();
            }
            return null;
        });
    }

    private void finish(Connection c, Run run, long now) throws SQLException {
        if (run.finished) return;
        List<ZoneStorage.ScoreRow> top = scores(c, run.start);
        double[] boosts = {run.first, run.second, run.third};
        try (var s = c.prepareStatement("INSERT INTO zone_event_winners (event_start,player_uuid,player_name,place,boost) VALUES (?,?,?,?,?)")) {
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                var row = top.get(i);
                s.setLong(1, run.start); s.setString(2, row.uuid().toString()); s.setString(3, row.name());
                s.setInt(4, i + 1); s.setDouble(5, boosts[i]); s.addBatch();
            }
            s.executeBatch();
        }
        try (var s = c.prepareStatement("UPDATE zone_event_runs SET finalized_at=? WHERE event_start=?")) {
            s.setLong(1, now); s.setLong(2, run.start); s.executeUpdate();
        }
    }

    private List<ZoneStorage.ScoreRow> scores(Connection c, long start) throws SQLException {
        List<ZoneStorage.ScoreRow> scores = new ArrayList<>();
        try (var s = c.prepareStatement("SELECT player_uuid,player_name,score,reached_at FROM zone_event_scores WHERE event_start=? AND score>0 ORDER BY score DESC,reached_at,player_uuid")) {
            s.setLong(1, start);
            try (var r = s.executeQuery()) { while (r.next()) scores.add(new ZoneStorage.ScoreRow(UUID.fromString(r.getString(1)), r.getString(2), r.getDouble(3), r.getLong(4))); }
        }
        return List.copyOf(scores);
    }

    private long anchor(Connection c, long now, long cycle) throws SQLException {
        try (var s = c.prepareStatement(insertIgnore("zone_event_state", "state_key,value", "?,?", "state_key"))) {
            s.setString(1, "cycle_anchor"); s.setLong(2, now - Math.floorMod(now, cycle)); s.executeUpdate();
        }
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT value FROM zone_event_state WHERE state_key='cycle_anchor'")) { r.next(); return r.getLong(1); }
    }

    private void ensureRun(Connection c, long start, Settings settings) throws SQLException {
        try (var s = c.prepareStatement(insertIgnore("zone_event_runs", "event_start,ends_at,settlement_delay,first_boost,second_boost,third_boost", "?,?,?,?,?,?", "event_start"))) {
            s.setLong(1, start); s.setLong(2, Math.addExact(start, settings.duration)); s.setLong(3, settings.settlementDelay);
            s.setDouble(4, settings.first); s.setDouble(5, settings.second); s.setDouble(6, settings.third); s.executeUpdate();
        }
    }

    private Run run(Connection c, long start) throws SQLException {
        try (var s = c.prepareStatement("SELECT ends_at,settlement_delay,finalized_at,first_boost,second_boost,third_boost FROM zone_event_runs WHERE event_start=?")) {
            s.setLong(1, start);
            try (var r = s.executeQuery()) { return r.next() ? new Run(start, r.getLong(1), r.getLong(2), r.getObject(3) != null, r.getDouble(4), r.getDouble(5), r.getDouble(6)) : null; }
        }
    }

    private <T> T transaction(Action<T> action) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                // This row is a cross-process mutex, not a Java lock. Lock before any reads.
                try (var s = c.createStatement()) {
                    s.executeUpdate(insertIgnore("zone_event_state", "state_key,value", "'event_lock',0", "state_key"));
                    s.executeUpdate("UPDATE zone_event_state SET value=value WHERE state_key='event_lock'");
                }
                T result = action.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException error) { c.rollback(); throw error; }
            finally { c.setAutoCommit(true); }
        }
    }
    private boolean mysql() { return database.dialect() == Database.Dialect.MYSQL; }
    private String insertIgnore(String table, String columns, String placeholders, String key) {
        return "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ") "
                + (mysql() ? "ON DUPLICATE KEY UPDATE " + key + "=" + key : "ON CONFLICT(" + key + ") DO NOTHING");
    }
    private long now(Connection c) throws SQLException {
        if (testClock != null) return testClock.getAsLong();
        try (var s = c.createStatement(); var r = s.executeQuery(mysql()
                ? "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)"
                : "SELECT CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)")) { r.next(); return r.getLong(1); }
    }
    @FunctionalInterface private interface Action<T> { T run(Connection connection) throws SQLException; }
    public static final class ClosedEventException extends SQLException {
        ClosedEventException(long start) { super("Zone event " + start + " is closed; late score was not applied"); }
    }
}
