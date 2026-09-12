package me.vertex.core.factions;

import me.vertex.core.claims.ChunkKey;
import me.vertex.core.claims.ClaimStorage;
import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlSchema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** SQL backing for Vertex's native faction, territory, relation, and permission state. */
public final class FactionStorage {
    private final Database database;

    public FactionStorage(Database database) { this.database = database; }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_factions (id " + idColumn()
                    + ", tag VARCHAR(32) NOT NULL UNIQUE, tag_key VARCHAR(32) NOT NULL UNIQUE, description VARCHAR(512) NOT NULL, open_join BOOLEAN NOT NULL, system_faction BOOLEAN NOT NULL, money DOUBLE NOT NULL DEFAULT 0, power DOUBLE NOT NULL, power_max DOUBLE NOT NULL, created_at BIGINT NOT NULL, home_shard VARCHAR(64), home_world VARCHAR(128), home_x DOUBLE, home_y DOUBLE, home_z DOUBLE, home_yaw FLOAT, home_pitch FLOAT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_members (player_uuid VARCHAR(36) PRIMARY KEY, faction_id INT NOT NULL, role VARCHAR(16) NOT NULL, last_name VARCHAR(32) NOT NULL, joined_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_claims (world VARCHAR(255) NOT NULL, chunk_x INT NOT NULL, chunk_z INT NOT NULL, faction_id INT NOT NULL, PRIMARY KEY (world, chunk_x, chunk_z))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_relations (faction_id INT NOT NULL, target_faction_id INT NOT NULL, relation VARCHAR(16) NOT NULL, PRIMARY KEY (faction_id, target_faction_id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_permissions (faction_id INT NOT NULL, role VARCHAR(16) NOT NULL, action_key VARCHAR(64) NOT NULL, allowed BOOLEAN NOT NULL, PRIMARY KEY (faction_id, role, action_key))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_warps (faction_id INT NOT NULL, name VARCHAR(32) NOT NULL, shard_id VARCHAR(64) NOT NULL DEFAULT '', world VARCHAR(128) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, PRIMARY KEY (faction_id, name))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_invites (faction_id INT NOT NULL, player_uuid VARCHAR(36) NOT NULL, invited_by VARCHAR(36), expires_at BIGINT NOT NULL, PRIMARY KEY (faction_id, player_uuid))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_players (player_uuid VARCHAR(36) PRIMARY KEY, chat_mode VARCHAR(16) NOT NULL, autoclaim BOOLEAN NOT NULL, map_enabled BOOLEAN NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_player_power (player_uuid VARCHAR(36) PRIMARY KEY, current_power DOUBLE NOT NULL, max_power DOUBLE NOT NULL, next_regen_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_player_cooldowns (player_uuid VARCHAR(36) PRIMARY KEY, create_available_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_cooldowns (faction_id INT PRIMARY KEY, rename_available_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_archives (faction_id INT PRIMARY KEY, tag VARCHAR(32) NOT NULL, disbanded_at BIGINT NOT NULL, expires_at BIGINT NOT NULL)");
            SqlSchema.ensureColumn(connection, "vertex_factions", "home_shard", "VARCHAR(64)");
            SqlSchema.ensureColumn(connection, "vertex_factions", "tag_key", "VARCHAR(32)");
            statement.executeUpdate("UPDATE vertex_factions SET tag_key=LOWER(tag) WHERE tag_key IS NULL OR tag_key=''");
            if (!hasIndex(connection, "vertex_factions", "vertex_factions_tag_key_uq")) {
                // Deliberately fail startup if old data contains tags that
                // differ only by case; silently choosing one would corrupt
                // faction identity and relations.
                statement.executeUpdate("CREATE UNIQUE INDEX vertex_factions_tag_key_uq ON vertex_factions(tag_key)");
            }
            SqlSchema.ensureColumn(connection, "vertex_faction_warps", "shard_id", "VARCHAR(64) NOT NULL DEFAULT ''");
            SqlSchema.ensureVarcharSize(connection, database.dialect(),
                    "vertex_faction_claims", "world", 255, false);
        }
        // Claim writes also maintain Base/Raid classification metadata. Ensure
        // those tables exist whenever this storage's public operations are used.
        new ClaimStorage(database).init();
    }

    public LoadedState load() throws SQLException {
        Map<Integer, FactionData> factions = new LinkedHashMap<>();
        Map<UUID, FactionMember> members = new LinkedHashMap<>();
        Map<ChunkKey, Integer> claims = new LinkedHashMap<>();
        Map<RelationKey, FactionRelation> relations = new LinkedHashMap<>();
        Map<PermissionKey, Boolean> permissions = new LinkedHashMap<>();
        Map<WarpKey, FactionWarp> warps = new LinkedHashMap<>();
        Map<UUID, PlayerSettings> players = new LinkedHashMap<>();
        Map<InviteKey, Invite> invites = new LinkedHashMap<>();
        Map<UUID, FactionPowerProfile> powerProfiles = new LinkedHashMap<>();
        Map<UUID, Long> createCooldowns = new LinkedHashMap<>();
        Map<Integer, Long> renameCooldowns = new LinkedHashMap<>();
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT id,tag,description,open_join,system_faction,power,power_max,created_at,home_shard,home_world,home_x,home_y,home_z,home_yaw,home_pitch FROM vertex_factions"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    FactionData.Home home = rows.getString(10) == null ? null : new FactionData.Home(rows.getString(9), rows.getString(10), rows.getDouble(11), rows.getDouble(12), rows.getDouble(13), rows.getFloat(14), rows.getFloat(15));
                    FactionData faction = new FactionData(rows.getInt(1), rows.getString(2), rows.getString(3), rows.getBoolean(4), rows.getBoolean(5), rows.getDouble(6), rows.getDouble(7), rows.getLong(8), home);
                    factions.put(faction.id(), faction);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid,faction_id,role,last_name,joined_at FROM vertex_faction_members"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) { UUID uuid = UUID.fromString(rows.getString(1)); members.put(uuid, new FactionMember(uuid, rows.getInt(2), FactionRole.parse(rows.getString(3), FactionRole.RECRUIT), rows.getString(4), rows.getLong(5))); }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT world,chunk_x,chunk_z,faction_id FROM vertex_faction_claims"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) claims.put(ChunkKey.fromStorage(rows.getString(1), rows.getInt(2), rows.getInt(3)), rows.getInt(4));
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT faction_id,target_faction_id,relation FROM vertex_faction_relations"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) relations.put(new RelationKey(rows.getInt(1), rows.getInt(2)), FactionRelation.parse(rows.getString(3), FactionRelation.NEUTRAL));
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT faction_id,role,action_key,allowed FROM vertex_faction_permissions"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) permissions.put(new PermissionKey(rows.getInt(1), rows.getString(2), rows.getString(3)), rows.getBoolean(4));
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT faction_id,name,shard_id,world,x,y,z,yaw,pitch FROM vertex_faction_warps"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) { FactionWarp warp = new FactionWarp(rows.getInt(1), rows.getString(2), new FactionData.Home(rows.getString(3), rows.getString(4), rows.getDouble(5), rows.getDouble(6), rows.getDouble(7), rows.getFloat(8), rows.getFloat(9))); warps.put(new WarpKey(warp.factionId(), normalize(warp.name())), warp); }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid,chat_mode,autoclaim,map_enabled FROM vertex_faction_players"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) players.put(UUID.fromString(rows.getString(1)), new PlayerSettings(rows.getString(2), rows.getBoolean(3), rows.getBoolean(4)));
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT faction_id,player_uuid,invited_by,expires_at FROM vertex_faction_invites"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) { UUID target = UUID.fromString(rows.getString(2)); String rawInviter = rows.getString(3); invites.put(new InviteKey(rows.getInt(1), target), new Invite(rows.getInt(1), target, rawInviter == null ? null : UUID.fromString(rawInviter), rows.getLong(4))); }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid,current_power,max_power,next_regen_at FROM vertex_player_power"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID uuid = UUID.fromString(rows.getString(1));
                    powerProfiles.put(uuid, new FactionPowerProfile(uuid, rows.getDouble(2), rows.getDouble(3), rows.getLong(4)));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT player_uuid,create_available_at FROM vertex_faction_player_cooldowns");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) createCooldowns.put(UUID.fromString(rows.getString(1)), rows.getLong(2));
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT faction_id,rename_available_at FROM vertex_faction_cooldowns");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) renameCooldowns.put(rows.getInt(1), rows.getLong(2));
            }
        }
        return new LoadedState(factions, members, claims, relations, permissions, warps, players, invites,
                powerProfiles, createCooldowns, renameCooldowns);
    }

    public int createFaction(FactionData faction, FactionMember leader) throws SQLException {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO vertex_factions (tag,tag_key,description,open_join,system_faction,power,power_max,created_at) VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, faction.tag()); insert.setString(2, normalize(faction.tag())); insert.setString(3, faction.description()); insert.setBoolean(4, faction.open()); insert.setBoolean(5, faction.system()); insert.setDouble(6, faction.power()); insert.setDouble(7, faction.powerMax()); insert.setLong(8, faction.createdAtMillis()); insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Faction insert returned no generated id.");
                    int id = keys.getInt(1);
                    try (PreparedStatement member = connection.prepareStatement("INSERT INTO vertex_faction_members (player_uuid,faction_id,role,last_name,joined_at) VALUES (?,?,?,?,?)")) {
                        member.setString(1, leader.playerUuid().toString()); member.setInt(2, id); member.setString(3, FactionRole.LEADER.name()); member.setString(4, leader.lastName()); member.setLong(5, leader.joinedAtMillis()); member.executeUpdate();
                    }
                    connection.commit(); return id;
                }
            } catch (SQLException error) { connection.rollback(); throw error; }
            finally { connection.setAutoCommit(true); }
        }
    }

    /**
     * Network-safe player creation path. The persistent power row is the
     * per-player mutex, so membership and post-disband cooldown cannot race
     * between two backends.
     */
    public CreateFactionOutcome createFactionChecked(FactionData faction, FactionMember leader,
            long now) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                lockPowerProfile(connection, leader.playerUuid());
                if (lockMemberFaction(connection, leader.playerUuid()) != null) {
                    connection.rollback();
                    return new CreateFactionOutcome(CreateFactionWriteResult.ALREADY_MEMBER, -1);
                }
                String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
                try (PreparedStatement cooldown = connection.prepareStatement(
                        "SELECT create_available_at FROM vertex_faction_player_cooldowns WHERE player_uuid=?"
                                + suffix)) {
                    cooldown.setString(1, leader.playerUuid().toString());
                    try (ResultSet row = cooldown.executeQuery()) {
                        if (row.next() && row.getLong(1) > now) {
                            connection.rollback();
                            return new CreateFactionOutcome(CreateFactionWriteResult.COOLDOWN, -1);
                        }
                    }
                }
                int id;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO vertex_factions (tag,tag_key,description,open_join,system_faction,power,power_max,created_at) VALUES (?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, faction.tag());
                    insert.setString(2, normalize(faction.tag()));
                    insert.setString(3, faction.description());
                    insert.setBoolean(4, faction.open());
                    insert.setBoolean(5, faction.system());
                    insert.setDouble(6, faction.power());
                    insert.setDouble(7, faction.powerMax());
                    insert.setLong(8, faction.createdAtMillis());
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Faction insert returned no generated id.");
                        id = keys.getInt(1);
                    }
                }
                insertMember(connection, new FactionMember(leader.playerUuid(), id,
                        FactionRole.LEADER, leader.lastName(), leader.joinedAtMillis()));
                connection.commit();
                return new CreateFactionOutcome(CreateFactionWriteResult.OK, id);
            } catch (SQLException error) {
                connection.rollback();
                if (isUniqueViolation(error)) {
                    return new CreateFactionOutcome(CreateFactionWriteResult.NAME_TAKEN, -1);
                }
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Creates a system faction without fabricating a hidden player member. */
    public int createSystemFaction(FactionData faction) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO vertex_factions (tag,tag_key,description,open_join,system_faction,power,power_max,created_at) VALUES (?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, faction.tag());
            insert.setString(2, normalize(faction.tag()));
            insert.setString(3, faction.description());
            insert.setBoolean(4, faction.open());
            insert.setBoolean(5, true);
            insert.setDouble(6, faction.power());
            insert.setDouble(7, faction.powerMax());
            insert.setLong(8, faction.createdAtMillis());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("System faction insert returned no generated id.");
                return keys.getInt(1);
            }
        }
    }

    /** Persists joining member, invite removal, and faction power in one transaction. */
    public void joinFaction(FactionMember member, Invite invite, FactionData changedFaction) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                saveMember(connection, member);
                if (invite != null) deleteInvite(connection, invite.factionId(), invite.playerUuid());
                saveFaction(connection, changedFaction);
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /**
     * Network-safe join path. Every decision that can change on another
     * shard is repeated while the durable faction/member rows are locked.
     */
    public JoinOutcome joinFactionChecked(FactionMember member, boolean openJoinEnabled,
            int maximumMembers, long now) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                FactionJoinState faction = lockFactionForJoin(connection, member.factionId());
                if (faction == null || faction.system()) {
                    connection.rollback();
                    return new JoinOutcome(JoinWriteResult.NOT_FOUND, null);
                }
                lockPowerProfile(connection, member.playerUuid());
                if (lockMemberFaction(connection, member.playerUuid()) != null) {
                    connection.rollback();
                    return new JoinOutcome(JoinWriteResult.ALREADY_MEMBER, null);
                }
                if (countMembers(connection, member.factionId()) >= maximumMembers) {
                    connection.rollback();
                    return new JoinOutcome(JoinWriteResult.MEMBER_LIMIT, null);
                }
                if (isBanned(connection, member.factionId(), member.playerUuid())) {
                    connection.rollback();
                    return new JoinOutcome(JoinWriteResult.BANNED, null);
                }
                boolean inviteRequired = !openJoinEnabled || !faction.open();
                if (inviteRequired && !hasLiveInvite(connection, member.factionId(),
                        member.playerUuid(), now)) {
                    connection.rollback();
                    return new JoinOutcome(JoinWriteResult.NOT_INVITED, null);
                }
                insertMember(connection, member);
                deleteInvite(connection, member.factionId(), member.playerUuid());
                FactionTotals totals = recalculateFactionPower(connection, member.factionId());
                connection.commit();
                return new JoinOutcome(JoinWriteResult.OK, totals);
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Persists leaving/kicking a member and the reduced power cap atomically. */
    public void removeFactionMember(UUID playerUuid, FactionData changedFaction) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                deleteMember(connection, playerUuid);
                saveFaction(connection, changedFaction);
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Removes a non-leader only if durable membership still matches this faction. */
    public MemberMutationOutcome removeFactionMemberChecked(UUID playerUuid, int expectedFactionId)
            throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (lockFaction(connection, expectedFactionId) == null) {
                    connection.rollback();
                    return new MemberMutationOutcome(MemberWriteResult.MEMBER_CHANGED, null);
                }
                lockPowerProfile(connection, playerUuid);
                Integer durableFaction = lockMemberFaction(connection, playerUuid);
                if (durableFaction == null || durableFaction != expectedFactionId) {
                    connection.rollback();
                    return new MemberMutationOutcome(MemberWriteResult.MEMBER_CHANGED, null);
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM vertex_faction_members WHERE player_uuid=? AND faction_id=? AND role<>?")) {
                    delete.setString(1, playerUuid.toString());
                    delete.setInt(2, expectedFactionId);
                    delete.setString(3, FactionRole.LEADER.name());
                    if (delete.executeUpdate() != 1) {
                        connection.rollback();
                        return new MemberMutationOutcome(MemberWriteResult.LEADER, null);
                    }
                }
                FactionTotals totals = recalculateFactionPower(connection, expectedFactionId);
                connection.commit();
                return new MemberMutationOutcome(MemberWriteResult.OK, totals);
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /**
     * Runtime kick path. The actor and target are both revalidated while the
     * faction row is locked so a demotion/removal on another shard cannot be
     * bypassed with stale local state.
     */
    public MemberMutationOutcome removeFactionMemberAuthorized(UUID actor, FactionRole expectedActorRole,
            boolean defaultAllowed, UUID playerUuid, int expectedFactionId) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (lockFaction(connection, expectedFactionId) == null) {
                    connection.rollback();
                    return new MemberMutationOutcome(MemberWriteResult.MEMBER_CHANGED, null);
                }
                FactionMemberState durableActor = lockMember(connection, actor);
                FactionMemberState durableTarget = lockMember(connection, playerUuid);
                if (durableActor == null || durableActor.factionId() != expectedFactionId
                        || durableActor.role() != expectedActorRole
                        || !durableActionAllowed(connection, expectedFactionId, durableActor.role(),
                        "kick", defaultAllowed)
                        || durableTarget == null || durableTarget.factionId() != expectedFactionId
                        || durableTarget.role().weight() >= durableActor.role().weight()) {
                    connection.rollback();
                    return new MemberMutationOutcome(MemberWriteResult.NOT_AUTHORIZED, null);
                }
                lockPowerProfile(connection, playerUuid);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM vertex_faction_members WHERE player_uuid=? AND faction_id=? AND role<>?")) {
                    delete.setString(1, playerUuid.toString());
                    delete.setInt(2, expectedFactionId);
                    delete.setString(3, FactionRole.LEADER.name());
                    if (delete.executeUpdate() != 1) {
                        connection.rollback();
                        return new MemberMutationOutcome(MemberWriteResult.MEMBER_CHANGED, null);
                    }
                }
                FactionTotals totals = recalculateFactionPower(connection, expectedFactionId);
                connection.commit();
                return new MemberMutationOutcome(MemberWriteResult.OK, totals);
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Leadership transfer cannot leave a faction with two leaders after a partial write. */
    public void transferLeadership(FactionMember formerLeader, FactionMember newLeader) throws SQLException {
        transferLeadership(java.util.List.of(formerLeader, newLeader));
    }

    /** Atomically applies every role affected by a leadership handoff. */
    public void transferLeadership(java.util.Collection<FactionMember> changedMembers) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (FactionMember member : changedMembers) saveMember(connection, member);
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Applies one normal/admin role change against the durable current role. */
    public RoleWriteResult setRoleChecked(UUID player, int expectedFactionId,
            FactionRole expectedRole, FactionRole nextRole) throws SQLException {
        if (nextRole == null || nextRole == FactionRole.LEADER) return RoleWriteResult.INVALID;
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (lockFaction(connection, expectedFactionId) == null) {
                    connection.rollback();
                    return RoleWriteResult.MEMBER_CHANGED;
                }
                FactionMemberState current = lockMember(connection, player);
                if (current == null || current.factionId() != expectedFactionId
                        || current.role() != expectedRole) {
                    connection.rollback();
                    return RoleWriteResult.MEMBER_CHANGED;
                }
                if (nextRole == FactionRole.COLEADER
                        && countRole(connection, expectedFactionId, FactionRole.COLEADER, player) > 0) {
                    connection.rollback();
                    return RoleWriteResult.ROLE_LIMIT;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE vertex_faction_members SET role=? WHERE player_uuid=? AND faction_id=? AND role=?")) {
                    statement.setString(1, nextRole.name());
                    statement.setString(2, player.toString());
                    statement.setInt(3, expectedFactionId);
                    statement.setString(4, expectedRole.name());
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return RoleWriteResult.MEMBER_CHANGED;
                    }
                }
                connection.commit();
                return RoleWriteResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Runtime promote/demote path with durable actor authorization. */
    public RoleWriteResult setRoleAuthorized(UUID actor, FactionRole expectedActorRole,
            boolean defaultAllowed, String action, UUID player, int expectedFactionId,
            FactionRole expectedRole, FactionRole nextRole) throws SQLException {
        if (nextRole == null || nextRole == FactionRole.LEADER) return RoleWriteResult.INVALID;
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (lockFaction(connection, expectedFactionId) == null) {
                    connection.rollback();
                    return RoleWriteResult.MEMBER_CHANGED;
                }
                FactionMemberState durableActor = lockMember(connection, actor);
                FactionMemberState current = lockMember(connection, player);
                if (durableActor == null || durableActor.factionId() != expectedFactionId
                        || durableActor.role() != expectedActorRole
                        || !durableActionAllowed(connection, expectedFactionId, durableActor.role(),
                        action, defaultAllowed)
                        || current == null || current.factionId() != expectedFactionId
                        || current.role() != expectedRole
                        || current.role().weight() >= durableActor.role().weight()
                        || nextRole.weight() >= durableActor.role().weight()) {
                    connection.rollback();
                    return RoleWriteResult.NOT_AUTHORIZED;
                }
                if (nextRole == FactionRole.COLEADER
                        && countRole(connection, expectedFactionId, FactionRole.COLEADER, player) > 0) {
                    connection.rollback();
                    return RoleWriteResult.ROLE_LIMIT;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE vertex_faction_members SET role=? WHERE player_uuid=? AND faction_id=? AND role=?")) {
                    statement.setString(1, nextRole.name());
                    statement.setString(2, player.toString());
                    statement.setInt(3, expectedFactionId);
                    statement.setString(4, expectedRole.name());
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return RoleWriteResult.MEMBER_CHANGED;
                    }
                }
                connection.commit();
                return RoleWriteResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Atomically leaves one Leader and one Co-Leader after a handoff. */
    public LeadershipWriteResult transferLeadershipChecked(UUID currentLeader, UUID target,
            int expectedFactionId) throws SQLException {
        if (currentLeader == null || target == null || currentLeader.equals(target)) {
            return LeadershipWriteResult.MEMBER_CHANGED;
        }
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (lockFaction(connection, expectedFactionId) == null) {
                    connection.rollback();
                    return LeadershipWriteResult.MEMBER_CHANGED;
                }
                FactionMemberState leader = lockMember(connection, currentLeader);
                FactionMemberState next = lockMember(connection, target);
                if (leader == null || next == null || leader.factionId() != expectedFactionId
                        || next.factionId() != expectedFactionId
                        || leader.role() != FactionRole.LEADER
                        || next.role() == FactionRole.LEADER) {
                    connection.rollback();
                    return LeadershipWriteResult.MEMBER_CHANGED;
                }
                try (PreparedStatement demote = connection.prepareStatement(
                        "UPDATE vertex_faction_members SET role=? WHERE faction_id=? AND role=?")) {
                    demote.setString(1, FactionRole.ADMIN.name());
                    demote.setInt(2, expectedFactionId);
                    demote.setString(3, FactionRole.COLEADER.name());
                    demote.executeUpdate();
                }
                try (PreparedStatement former = connection.prepareStatement(
                        "UPDATE vertex_faction_members SET role=? WHERE player_uuid=? AND faction_id=? AND role=?");
                     PreparedStatement promoted = connection.prepareStatement(
                             "UPDATE vertex_faction_members SET role=? WHERE player_uuid=? AND faction_id=?")) {
                    former.setString(1, FactionRole.COLEADER.name());
                    former.setString(2, currentLeader.toString());
                    former.setInt(3, expectedFactionId);
                    former.setString(4, FactionRole.LEADER.name());
                    promoted.setString(1, FactionRole.LEADER.name());
                    promoted.setString(2, target.toString());
                    promoted.setInt(3, expectedFactionId);
                    if (former.executeUpdate() != 1 || promoted.executeUpdate() != 1) {
                        connection.rollback();
                        return LeadershipWriteResult.MEMBER_CHANGED;
                    }
                }
                connection.commit();
                return LeadershipWriteResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    public void saveFaction(FactionData faction) throws SQLException {
        try (Connection connection = database.getConnection()) { saveFaction(connection, faction); }
    }

    public boolean updateOpen(int factionId, boolean open) throws SQLException {
        return updateFactionField(factionId, "open_join", statement -> statement.setBoolean(1, open));
    }

    public boolean updateDescription(int factionId, String description) throws SQLException {
        return updateFactionField(factionId, "description", statement -> statement.setString(1, description));
    }

    public FactionFieldWriteResult updateOpenChecked(UUID actor, int factionId,
            FactionRole expectedRole, boolean open) throws SQLException {
        return updateFactionFieldChecked(actor, factionId, expectedRole, "open", true,
                "open_join", statement -> statement.setBoolean(1, open));
    }

    public FactionFieldWriteResult updateDescriptionChecked(UUID actor, int factionId,
            FactionRole expectedRole, boolean defaultAllowed, String description) throws SQLException {
        return updateFactionFieldChecked(actor, factionId, expectedRole, "description", defaultAllowed,
                "description", statement -> statement.setString(1, description));
    }

    public boolean updateHome(int factionId, FactionData.Home home) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                FactionCapacity faction = lockFaction(connection, factionId);
                if (faction == null || faction.system()) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE vertex_factions SET home_shard=?,home_world=?,home_x=?,home_y=?,home_z=?,home_yaw=?,home_pitch=? WHERE id=?")) {
                    if (home == null) {
                        for (int index = 1; index <= 7; index++) statement.setNull(index,
                                index <= 2 ? java.sql.Types.VARCHAR : index <= 5
                                        ? java.sql.Types.DOUBLE : java.sql.Types.FLOAT);
                    } else {
                        statement.setString(1, home.shardId()); statement.setString(2, home.world());
                        statement.setDouble(3, home.x()); statement.setDouble(4, home.y());
                        statement.setDouble(5, home.z()); statement.setFloat(6, home.yaw());
                        statement.setFloat(7, home.pitch());
                    }
                    statement.setInt(8, factionId);
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /**
     * Saves a faction home only after rechecking durable membership,
     * permissions, claim ownership, and an ally's territory permission.
     */
    public HomeWriteResult updateHomeChecked(UUID actor, int factionId, FactionRole expectedRole,
            boolean defaultAllowed, FactionData.Home home) throws SQLException {
        if (home == null) return HomeWriteResult.NOT_OWNER;
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                FactionCapacity faction = lockFaction(connection, factionId);
                if (faction == null || faction.system()) {
                    connection.rollback();
                    return HomeWriteResult.FACTION_CHANGED;
                }
                FactionMemberState member = lockMember(connection, actor);
                if (member == null || member.factionId() != factionId || member.role() != expectedRole
                        || !durableActionAllowed(connection, factionId, member.role(),
                        "sethome", defaultAllowed)) {
                    connection.rollback();
                    return HomeWriteResult.NOT_AUTHORIZED;
                }
                ChunkKey location = ChunkKey.at(home.shardId(), home.world(),
                        ((int) Math.floor(home.x())) >> 4, ((int) Math.floor(home.z())) >> 4);
                Integer owner = lockClaimOwner(connection, location);
                boolean allowed = java.util.Objects.equals(owner, factionId);
                if (!allowed && owner != null && lockFaction(connection, owner) != null
                        && durableRelation(connection, factionId, owner) == FactionRelation.ALLY) {
                    allowed = durableAllyActionAllowed(connection, owner, "sethome");
                }
                if (!allowed) {
                    connection.rollback();
                    return HomeWriteResult.NOT_OWNER;
                }
                if (!writeHome(connection, factionId, home)) {
                    connection.rollback();
                    return HomeWriteResult.FACTION_CHANGED;
                }
                connection.commit();
                return HomeWriteResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Re-derives one faction's aggregate from durable members and profiles. */
    public FactionTotals recalculateFactionPowerChecked(int factionId) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (lockFaction(connection, factionId) == null) {
                    connection.rollback();
                    return null;
                }
                FactionTotals totals = recalculateFactionPower(connection, factionId);
                connection.commit();
                return totals;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Atomic rename/cooldown decision used by every shard. */
    public RenameWriteResult renameFaction(int factionId, String nextTag, long now,
            long availableAt) throws SQLException {
        return renameFactionInternal(null, null, factionId, nextTag, now, availableAt);
    }

    public RenameWriteResult renameFactionChecked(UUID actor, FactionRole expectedRole,
            int factionId, String nextTag, long now, long availableAt) throws SQLException {
        return renameFactionInternal(actor, expectedRole, factionId, nextTag, now, availableAt);
    }

    private RenameWriteResult renameFactionInternal(UUID actor, FactionRole expectedRole,
            int factionId, String nextTag, long now, long availableAt) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
                boolean system;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT system_faction FROM vertex_factions WHERE id=?" + suffix)) {
                    statement.setInt(1, factionId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return RenameWriteResult.NOT_FOUND;
                        }
                        system = row.getBoolean(1);
                    }
                }
                if (system) {
                    connection.rollback();
                    return RenameWriteResult.SYSTEM_FACTION;
                }
                if (actor != null) {
                    FactionMemberState member = lockMember(connection, actor);
                    if (member == null || member.factionId() != factionId
                            || member.role() != expectedRole || member.role() != FactionRole.LEADER) {
                        connection.rollback();
                        return RenameWriteResult.NOT_AUTHORIZED;
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT rename_available_at FROM vertex_faction_cooldowns WHERE faction_id=?" + suffix)) {
                    statement.setInt(1, factionId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (row.next() && row.getLong(1) > now) {
                            connection.rollback();
                            return RenameWriteResult.COOLDOWN;
                        }
                    }
                }
                try (PreparedStatement duplicate = connection.prepareStatement(
                        "SELECT id FROM vertex_factions WHERE tag_key=? AND id<>?" + suffix)) {
                    duplicate.setString(1, normalize(nextTag));
                    duplicate.setInt(2, factionId);
                    try (ResultSet row = duplicate.executeQuery()) {
                        if (row.next()) {
                            connection.rollback();
                            return RenameWriteResult.NAME_TAKEN;
                        }
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE vertex_factions SET tag=?,tag_key=? WHERE id=?")) {
                    update.setString(1, nextTag); update.setString(2, normalize(nextTag));
                    update.setInt(3, factionId); update.executeUpdate();
                }
                saveRenameCooldown(connection, factionId, availableAt);
                connection.commit();
                return RenameWriteResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                // A concurrent create/rename can win after the duplicate
                // probe. The unique index remains authoritative.
                if (isUniqueViolation(error)) return RenameWriteResult.NAME_TAKEN;
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    public void deleteFaction(int factionId) throws SQLException {
        deleteFactionInternal(factionId, null, null, 0L);
    }

    /** Core disband cleanup and the former leader's create cooldown are one commit. */
    public DisbandWriteResult deleteFactionChecked(int factionId, UUID formerLeader,
            FactionRole expectedRole, long createAvailableAt) throws SQLException {
        return deleteFactionInternal(factionId, formerLeader, expectedRole, createAvailableAt);
    }

    private DisbandWriteResult deleteFactionInternal(int factionId, UUID formerLeader,
            FactionRole expectedRole, long createAvailableAt) throws SQLException {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // This row is the lifecycle lock used by every network-safe
                // faction-owned mutation. Once it is removed, those writers
                // must refuse to recreate bank/upgrade/vault state.
                if (lockFaction(connection, factionId) == null) {
                    connection.rollback();
                    return DisbandWriteResult.FACTION_CHANGED;
                }
                if (formerLeader != null) {
                    FactionMemberState durableLeader = lockMember(connection, formerLeader);
                    if (durableLeader == null || durableLeader.factionId() != factionId
                            || durableLeader.role() != expectedRole
                            || durableLeader.role() != FactionRole.LEADER) {
                        connection.rollback();
                        return DisbandWriteResult.NOT_AUTHORIZED;
                    }
                }
                long disbandedAt = System.currentTimeMillis();
                long expiresAt;
                try { expiresAt = Math.addExact(disbandedAt, java.util.concurrent.TimeUnit.DAYS.toMillis(7)); }
                catch (ArithmeticException ignored) { expiresAt = Long.MAX_VALUE; }
                String archiveSql = database.dialect() == Database.Dialect.SQLITE
                        ? "INSERT INTO vertex_faction_archives(faction_id,tag,disbanded_at,expires_at) SELECT id,tag,?,? FROM vertex_factions WHERE id=? ON CONFLICT(faction_id) DO UPDATE SET tag=excluded.tag,disbanded_at=excluded.disbanded_at,expires_at=excluded.expires_at"
                        : "INSERT INTO vertex_faction_archives(faction_id,tag,disbanded_at,expires_at) SELECT id,tag,?,? FROM vertex_factions WHERE id=? ON DUPLICATE KEY UPDATE tag=VALUES(tag),disbanded_at=VALUES(disbanded_at),expires_at=VALUES(expires_at)";
                try (PreparedStatement archive = connection.prepareStatement(archiveSql)) {
                    archive.setLong(1, disbandedAt);
                    archive.setLong(2, expiresAt);
                    archive.setInt(3, factionId);
                    archive.executeUpdate();
                }
                for (String table : List.of("vertex_faction_members", "vertex_faction_claims", "vertex_faction_relations", "vertex_faction_permissions", "vertex_faction_warps", "vertex_faction_invites")) {
                    String column = table.equals("vertex_faction_relations") ? "faction_id=? OR target_faction_id=?" : "faction_id=?";
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE " + column)) { statement.setInt(1, factionId); if (table.equals("vertex_faction_relations")) statement.setInt(2, factionId); statement.executeUpdate(); }
                }
                // Every faction-owned module is cleared in this same commit.
                // Tables are optional here because isolated storage tests and
                // older installations may not have initialized every module.
                for (String table : List.of("base_claim_region_chunks", "base_claims",
                        "base_claim_slot_purchases", "raid_claim_expirations",
                        "faction_banks", "faction_upgrade_levels", "vertex_faction_vaults",
                        "vertex_faction_vault_locks", "faction_shields",
                        "faction_shield_overrides", "faction_shield_activations",
                        "faction_shield_weekly", "ftop_scores", "pvptop_points")) {
                    deleteFactionRowsIfPresent(connection, table, "faction_id", factionId);
                }
                deleteFactionPairRowsIfPresent(connection, "vertex_faction_relation_requests",
                        "requester_id", "target_id", factionId);
                deleteFactionRowsIfPresent(connection, "vertex_faction_bans", "faction_id", factionId);
                deleteFactionPairRowsIfPresent(connection, "vertex_faction_focuses",
                        "source_faction_id", "target_faction_id", factionId);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM vertex_faction_cooldowns WHERE faction_id=?")) {
                    statement.setInt(1, factionId);
                    statement.executeUpdate();
                }
                if (formerLeader != null && createAvailableAt > 0L) {
                    saveCreateCooldown(connection, formerLeader, createAvailableAt);
                }
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM vertex_factions WHERE id=?")) { statement.setInt(1, factionId); statement.executeUpdate(); }
                connection.commit();
                return DisbandWriteResult.OK;
            } catch (SQLException error) { connection.rollback(); throw error; }
            finally { connection.setAutoCommit(true); }
        }
    }

    /** Rename and its next-allowed deadline cannot be committed independently. */
    public void saveFactionAndRenameCooldown(FactionData faction, long availableAt) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                saveFaction(connection, faction);
                String sql = database.dialect() == Database.Dialect.SQLITE
                        ? "INSERT INTO vertex_faction_cooldowns(faction_id,rename_available_at) VALUES(?,?) ON CONFLICT(faction_id) DO UPDATE SET rename_available_at=excluded.rename_available_at"
                        : "INSERT INTO vertex_faction_cooldowns(faction_id,rename_available_at) VALUES(?,?) ON DUPLICATE KEY UPDATE rename_available_at=VALUES(rename_available_at)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setInt(1, faction.id());
                    statement.setLong(2, availableAt);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Removes a departing leader, promotes a successor, and updates power atomically. */
    public void removeLeaderAndTransfer(UUID formerLeader, FactionMember successor,
            FactionData changedFaction) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                deleteMember(connection, formerLeader);
                saveMember(connection, successor);
                saveFaction(connection, changedFaction);
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Selects and promotes the durable highest-ranked/oldest successor. */
    public LeaderDepartureOutcome removeLeaderAndTransferChecked(UUID formerLeader,
            int expectedFactionId) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (lockFaction(connection, expectedFactionId) == null) {
                    connection.rollback();
                    return new LeaderDepartureOutcome(LeaderDepartureResult.MEMBER_CHANGED, null, null);
                }
                FactionMemberState durableLeader = lockMember(connection, formerLeader);
                if (durableLeader == null || durableLeader.factionId() != expectedFactionId
                        || durableLeader.role() != FactionRole.LEADER) {
                    connection.rollback();
                    return new LeaderDepartureOutcome(LeaderDepartureResult.MEMBER_CHANGED, null, null);
                }
                FactionMember successor = lockBestSuccessor(connection, expectedFactionId, formerLeader);
                if (successor == null) {
                    connection.rollback();
                    return new LeaderDepartureOutcome(LeaderDepartureResult.NO_SUCCESSOR, null, null);
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM vertex_faction_members WHERE player_uuid=? AND faction_id=? AND role=?");
                     PreparedStatement promote = connection.prepareStatement(
                             "UPDATE vertex_faction_members SET role=? WHERE player_uuid=? AND faction_id=?")) {
                    delete.setString(1, formerLeader.toString());
                    delete.setInt(2, expectedFactionId);
                    delete.setString(3, FactionRole.LEADER.name());
                    promote.setString(1, FactionRole.LEADER.name());
                    promote.setString(2, successor.playerUuid().toString());
                    promote.setInt(3, expectedFactionId);
                    if (delete.executeUpdate() != 1 || promote.executeUpdate() != 1) {
                        connection.rollback();
                        return new LeaderDepartureOutcome(LeaderDepartureResult.MEMBER_CHANGED, null, null);
                    }
                }
                FactionTotals totals = recalculateFactionPower(connection, expectedFactionId);
                connection.commit();
                return new LeaderDepartureOutcome(LeaderDepartureResult.OK,
                        successor.withRole(FactionRole.LEADER), totals);
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    public void saveMember(FactionMember member) throws SQLException {
        try (Connection connection=database.getConnection()) { saveMember(connection, member); }
    }

    /** Updates presentation-only member data without ever recreating a removed member row. */
    public boolean updateMemberName(UUID player, int expectedFactionId, String lastName) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE vertex_faction_members SET last_name=? WHERE player_uuid=? AND faction_id=?")) {
            statement.setString(1, lastName);
            statement.setString(2, player.toString());
            statement.setInt(3, expectedFactionId);
            return statement.executeUpdate() == 1;
        }
    }
    public void deleteMember(UUID uuid) throws SQLException { try(Connection c=database.getConnection()){ deleteMember(c, uuid); } }
    public void saveClaim(ChunkKey key,int factionId)throws SQLException { String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO vertex_faction_claims (world,chunk_x,chunk_z,faction_id) VALUES (?,?,?,?) ON CONFLICT(world,chunk_x,chunk_z) DO UPDATE SET faction_id=excluded.faction_id":"INSERT INTO vertex_faction_claims (world,chunk_x,chunk_z,faction_id) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE faction_id=VALUES(faction_id)";try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,key.world());s.setInt(2,key.x());s.setInt(3,key.z());s.setInt(4,factionId);s.executeUpdate();} }

    /** Admin/system claim path that cannot recreate a claim for a deleted faction. */
    public boolean saveClaimChecked(ChunkKey key, int factionId) throws SQLException {
        return saveClaimChecked(key, factionId, 0L);
    }

    /**
     * Admin claim path with optional atomic Raid classification. A zero
     * deadline is used for system claims and clears stale Raid metadata.
     */
    public boolean saveClaimChecked(ChunkKey key, int factionId, long raidExpiresAt) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (lockFaction(connection, factionId) == null) {
                    connection.rollback();
                    return false;
                }
                lockClaimOwner(connection, key);
                saveClaim(connection, key, factionId);
                replaceClaimClassification(connection, key, factionId, raidExpiresAt);
                connection.commit();
                return true;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /**
     * Persists a complete SafeZone/WarZone claim operation as one database
     * transaction. The caller can then apply the returned cache/event changes
     * in bounded main-thread batches without risking a partially saved area.
     * A {@code null} result means the target system faction disappeared before
     * the transaction could begin.
     */
    public List<ClaimOwnerChange> saveSystemClaimAreaChecked(int factionId, List<ChunkKey> keys)
            throws SQLException {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                FactionCapacity target = lockFaction(connection, factionId);
                if (target == null || !target.system()) {
                    connection.rollback();
                    return null;
                }
                List<ClaimOwnerChange> changes = new ArrayList<>(keys.size());
                for (ChunkKey key : keys) {
                    Integer previousOwner = lockClaimOwner(connection, key);
                    saveClaim(connection, key, factionId);
                    // System claims never carry raid expiry metadata.
                    replaceClaimClassification(connection, key, factionId, 0L);
                    changes.add(new ClaimOwnerChange(key, previousOwner));
                }
                connection.commit();
                return List.copyOf(changes);
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Atomically checks durable owner and faction capacity before a normal claim write. */
    public ClaimWriteResult claim(ChunkKey key,int factionId,Integer expectedOwner,int maximumClaims,
            boolean allowOverclaim)throws SQLException{
        try(Connection c=database.getConnection()){
            boolean previous=c.getAutoCommit();c.setAutoCommit(false);
            try{
                FactionCapacity attacker=lockFaction(c,factionId);
                if(attacker==null){c.rollback();return ClaimWriteResult.OWNER_CHANGED;}
                Integer durableOwner=lockClaimOwner(c,key);
                if(!java.util.Objects.equals(durableOwner,expectedOwner)){c.rollback();return ClaimWriteResult.OWNER_CHANGED;}
                if(durableOwner==null){
                    int count=countClaims(c,factionId);
                    if(count>=maximumClaims){c.rollback();return ClaimWriteResult.CLAIM_LIMIT;}
                    if(attacker.power()+0.000001D<count+1D){c.rollback();return ClaimWriteResult.INSUFFICIENT_POWER;}
                }else{
                    FactionCapacity defender=lockFaction(c,durableOwner);
                    if(!allowOverclaim||defender==null||defender.system()
                            ||countClaims(c,durableOwner)<=Math.floor(defender.power()+0.000001D)){
                        c.rollback();return ClaimWriteResult.OVERCLAIM_DENIED;
                    }
                }
                saveClaim(c,key,factionId);c.commit();return ClaimWriteResult.OK;
            }catch(SQLException error){c.rollback();throw error;}finally{c.setAutoCommit(previous);}
        }
    }
    /** Normal player claim path with durable role/permission authorization. */
    public ClaimWriteResult claimAuthorized(UUID actor, FactionRole expectedRole, boolean defaultAllowed,
            ChunkKey key, int factionId, Integer expectedOwner, int maximumClaims,
            boolean allowOverclaim) throws SQLException {
        return claimAuthorized(actor, expectedRole, defaultAllowed, key, factionId, expectedOwner,
                maximumClaims, allowOverclaim, 0L);
    }

    /** Normal claim write with its safe default Raid classification in the same commit. */
    public ClaimWriteResult claimAuthorized(UUID actor, FactionRole expectedRole, boolean defaultAllowed,
            ChunkKey key, int factionId, Integer expectedOwner, int maximumClaims,
            boolean allowOverclaim, long raidExpiresAt) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                FactionCapacity attacker = lockFaction(connection, factionId);
                if (attacker == null) {
                    connection.rollback();
                    return ClaimWriteResult.OWNER_CHANGED;
                }
                FactionMemberState member = lockMember(connection, actor);
                if (member == null || member.factionId() != factionId || member.role() != expectedRole
                        || !durableActionAllowed(connection, factionId, member.role(),
                        "claim", defaultAllowed)) {
                    connection.rollback();
                    return ClaimWriteResult.NOT_AUTHORIZED;
                }
                Integer durableOwner = lockClaimOwner(connection, key);
                if (!java.util.Objects.equals(durableOwner, expectedOwner)) {
                    connection.rollback();
                    return ClaimWriteResult.OWNER_CHANGED;
                }
                if (durableOwner == null) {
                    int count = countClaims(connection, factionId);
                    if (count >= maximumClaims) {
                        connection.rollback();
                        return ClaimWriteResult.CLAIM_LIMIT;
                    }
                    if (attacker.power() + 0.000001D < count + 1D) {
                        connection.rollback();
                        return ClaimWriteResult.INSUFFICIENT_POWER;
                    }
                } else {
                    FactionCapacity defender = lockFaction(connection, durableOwner);
                    if (!allowOverclaim || defender == null || defender.system()
                            || countClaims(connection, durableOwner)
                            <= Math.floor(defender.power() + 0.000001D)) {
                        connection.rollback();
                        return ClaimWriteResult.OVERCLAIM_DENIED;
                    }
                }
                saveClaim(connection, key, factionId);
                replaceClaimClassification(connection, key, factionId, raidExpiresAt);
                connection.commit();
                return ClaimWriteResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    public ClaimDeleteResult deleteClaimChecked(UUID actor, FactionRole expectedRole,
            boolean defaultAllowed, ChunkKey key, int expectedOwner) throws SQLException {
        return deleteClaimChecked(actor, expectedRole, defaultAllowed, key, expectedOwner, false);
    }

    public ClaimDeleteResult deleteClaimChecked(UUID actor, FactionRole expectedRole,
            boolean defaultAllowed, ChunkKey key, int expectedOwner, boolean clearMetadata) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (lockFaction(connection, expectedOwner) == null) {
                    connection.rollback();
                    return ClaimDeleteResult.OWNER_CHANGED;
                }
                FactionMemberState member = lockMember(connection, actor);
                if (member == null || member.factionId() != expectedOwner
                        || member.role() != expectedRole
                        || !durableActionAllowed(connection, expectedOwner, member.role(),
                        "unclaim", defaultAllowed)) {
                    connection.rollback();
                    return ClaimDeleteResult.NOT_AUTHORIZED;
                }
                if (!java.util.Objects.equals(lockClaimOwner(connection, key), expectedOwner)) {
                    connection.rollback();
                    return ClaimDeleteResult.OWNER_CHANGED;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM vertex_faction_claims WHERE world=? AND chunk_x=? AND chunk_z=? AND faction_id=?")) {
                    statement.setString(1, key.world());
                    statement.setInt(2, key.x());
                    statement.setInt(3, key.z());
                    statement.setInt(4, expectedOwner);
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return ClaimDeleteResult.OWNER_CHANGED;
                    }
                }
                if (clearMetadata) deleteRaidClassification(connection, key);
                connection.commit();
                return ClaimDeleteResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    public ClaimDeleteResult deleteClaimsForFactionChecked(UUID actor, FactionRole expectedRole,
            boolean defaultAllowed, int factionId) throws SQLException {
        return deleteClaimsForFactionChecked(actor, expectedRole, defaultAllowed, factionId, false);
    }

    public ClaimDeleteResult deleteClaimsForFactionChecked(UUID actor, FactionRole expectedRole,
            boolean defaultAllowed, int factionId, boolean clearMetadata) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (lockFaction(connection, factionId) == null) {
                    connection.rollback();
                    return ClaimDeleteResult.OWNER_CHANGED;
                }
                FactionMemberState member = lockMember(connection, actor);
                if (member == null || member.factionId() != factionId || member.role() != expectedRole
                        || !durableActionAllowed(connection, factionId, member.role(),
                        "unclaim", defaultAllowed)) {
                    connection.rollback();
                    return ClaimDeleteResult.NOT_AUTHORIZED;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM vertex_faction_claims WHERE faction_id=?")) {
                    statement.setInt(1, factionId);
                    statement.executeUpdate();
                }
                if (clearMetadata) clearFactionClaimMetadata(connection, factionId);
                connection.commit();
                return ClaimDeleteResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }
    public boolean deleteClaim(ChunkKey key,int expectedOwner)throws SQLException {
        return deleteClaim(key, expectedOwner, false);
    }
    public boolean deleteClaim(ChunkKey key,int expectedOwner,boolean clearMetadata)throws SQLException {
        try(Connection c=database.getConnection()){
            boolean previous=c.getAutoCommit();c.setAutoCommit(false);
            try(PreparedStatement s=c.prepareStatement("DELETE FROM vertex_faction_claims WHERE world=? AND chunk_x=? AND chunk_z=? AND faction_id=?")){
                s.setString(1,key.world());s.setInt(2,key.x());s.setInt(3,key.z());s.setInt(4,expectedOwner);
                if(s.executeUpdate()!=1){c.rollback();return false;}
                if(clearMetadata)deleteRaidClassification(c,key);
                c.commit();return true;
            }catch(SQLException error){c.rollback();throw error;}finally{c.setAutoCommit(previous);}
        }
    }
    public void deleteClaim(ChunkKey key)throws SQLException {try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("DELETE FROM vertex_faction_claims WHERE world=? AND chunk_x=? AND chunk_z=?")){s.setString(1,key.world());s.setInt(2,key.x());s.setInt(3,key.z());s.executeUpdate();}}

    private void replaceClaimClassification(Connection connection, ChunkKey key, int factionId,
            long raidExpiresAt) throws SQLException {
        // Remove only another faction's Base membership. A same-faction
        // membership can intentionally survive one unclaim/reclaim cycle.
        try (PreparedStatement deleteOldBase = connection.prepareStatement(
                "DELETE FROM base_claim_region_chunks WHERE world=? AND chunk_x=? AND chunk_z=? AND faction_id<>?")) {
            deleteOldBase.setString(1, key.world());
            deleteOldBase.setInt(2, key.x());
            deleteOldBase.setInt(3, key.z());
            deleteOldBase.setInt(4, factionId);
            deleteOldBase.executeUpdate();
        }
        if (raidExpiresAt <= 0L) {
            deleteRaidClassification(connection, key);
            return;
        }
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO raid_claim_expirations(world,chunk_x,chunk_z,faction_id,expires_at_millis) VALUES(?,?,?,?,?) ON CONFLICT(world,chunk_x,chunk_z) DO UPDATE SET faction_id=excluded.faction_id,expires_at_millis=excluded.expires_at_millis"
                : "INSERT INTO raid_claim_expirations(world,chunk_x,chunk_z,faction_id,expires_at_millis) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE faction_id=VALUES(faction_id),expires_at_millis=VALUES(expires_at_millis)";
        try (PreparedStatement upsert = connection.prepareStatement(sql)) {
            upsert.setString(1, key.world());
            upsert.setInt(2, key.x());
            upsert.setInt(3, key.z());
            upsert.setInt(4, factionId);
            upsert.setLong(5, raidExpiresAt);
            upsert.executeUpdate();
        }
    }

    private static void deleteRaidClassification(Connection connection, ChunkKey key) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM raid_claim_expirations WHERE world=? AND chunk_x=? AND chunk_z=?")) {
            delete.setString(1, key.world());
            delete.setInt(2, key.x());
            delete.setInt(3, key.z());
            delete.executeUpdate();
        }
    }

    private static void clearFactionClaimMetadata(Connection connection, int factionId) throws SQLException {
        for (String sql : List.of(
                "DELETE FROM raid_claim_expirations WHERE faction_id=?",
                "DELETE FROM base_claim_region_chunks WHERE faction_id=?",
                "DELETE FROM base_claims WHERE faction_id=?")) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, factionId);
                statement.executeUpdate();
            }
        }
    }
    public void saveRelation(int factionId,int target,FactionRelation relation)throws SQLException {String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO vertex_faction_relations (faction_id,target_faction_id,relation) VALUES (?,?,?) ON CONFLICT(faction_id,target_faction_id) DO UPDATE SET relation=excluded.relation":"INSERT INTO vertex_faction_relations (faction_id,target_faction_id,relation) VALUES (?,?,?) ON DUPLICATE KEY UPDATE relation=VALUES(relation)";try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setInt(1,factionId);s.setInt(2,target);s.setString(3,relation.name());s.executeUpdate();}}
    public void deleteRelation(int factionId,int target)throws SQLException {try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("DELETE FROM vertex_faction_relations WHERE faction_id=? AND target_faction_id=?")){s.setInt(1,factionId);s.setInt(2,target);s.executeUpdate();}}
    public void saveMutualRelation(int left,int right,FactionRelation relation)throws SQLException {
        try(Connection c=database.getConnection()){
            boolean previous=c.getAutoCommit();c.setAutoCommit(false);
            try{
                if(relation==FactionRelation.NEUTRAL){deleteRelation(c,left,right);deleteRelation(c,right,left);}
                else{saveRelation(c,left,right,relation);saveRelation(c,right,left,relation);}
                c.commit();
            }catch(SQLException error){c.rollback();throw error;}finally{c.setAutoCommit(previous);}
        }
    }
    /** Raw import/setup helper. Runtime permission edits use {@link #savePermissionChecked}. */
    public void savePermission(int factionId,String role,String action,boolean allowed)throws SQLException {
        try(Connection connection=database.getConnection()){savePermission(connection,factionId,role,action,allowed);}
    }

    /** Revalidates the editor's durable role while the faction row is locked. */
    public PermissionWriteResult savePermissionChecked(UUID actor, int factionId, String role,
            String action, boolean allowed) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (lockFaction(connection, factionId) == null) {
                    connection.rollback();
                    return PermissionWriteResult.FACTION_CHANGED;
                }
                FactionMemberState editor = lockMember(connection, actor);
                if (editor == null || editor.factionId() != factionId || !canEditBucket(editor.role(), role)) {
                    connection.rollback();
                    return PermissionWriteResult.NOT_AUTHORIZED;
                }
                savePermission(connection, factionId, role, action, allowed);
                connection.commit();
                return PermissionWriteResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Raw import/setup helper. Runtime warp edits use {@link #saveWarpChecked}. */
    public void saveWarp(FactionWarp warp)throws SQLException {
        try(Connection connection=database.getConnection()){saveWarp(connection,warp);}
    }

    /** Locks the faction so concurrent shards cannot exceed the configured warp cap. */
    public WarpWriteResult saveWarpChecked(UUID actor, FactionWarp warp, int maximumWarps,
            FactionRole expectedRole, boolean defaultAllowed) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (lockFaction(connection, warp.factionId()) == null) {
                    connection.rollback();
                    return WarpWriteResult.FACTION_CHANGED;
                }
                FactionMemberState member = lockMember(connection, actor);
                if (member == null || member.factionId() != warp.factionId()
                        || member.role() != expectedRole
                        || !durableActionAllowed(connection, warp.factionId(), member.role(),
                        "setwarp", defaultAllowed)) {
                    connection.rollback();
                    return WarpWriteResult.NOT_AUTHORIZED;
                }
                FactionData.Home home = warp.location();
                ChunkKey location = ChunkKey.at(home.shardId(), home.world(),
                        ((int) Math.floor(home.x())) >> 4, ((int) Math.floor(home.z())) >> 4);
                if (!java.util.Objects.equals(lockClaimOwner(connection, location), warp.factionId())) {
                    connection.rollback();
                    return WarpWriteResult.NOT_OWNER;
                }
                if (!warpExists(connection, warp.factionId(), warp.name())
                        && countWarps(connection, warp.factionId()) >= Math.max(0, maximumWarps)) {
                    connection.rollback();
                    return WarpWriteResult.LIMIT_REACHED;
                }
                saveWarp(connection, warp);
                connection.commit();
                return WarpWriteResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }
    public void deleteWarp(int factionId,String name)throws SQLException {try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("DELETE FROM vertex_faction_warps WHERE faction_id=? AND name=?")){s.setInt(1,factionId);s.setString(2,normalize(name));s.executeUpdate();}}
    public WarpDeleteResult deleteWarpChecked(UUID actor, int factionId, FactionRole expectedRole,
            boolean defaultAllowed, String name) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (lockFaction(connection, factionId) == null) {
                    connection.rollback();
                    return WarpDeleteResult.FACTION_CHANGED;
                }
                FactionMemberState member = lockMember(connection, actor);
                if (member == null || member.factionId() != factionId || member.role() != expectedRole
                        || !durableActionAllowed(connection, factionId, member.role(),
                        "setwarp", defaultAllowed)) {
                    connection.rollback();
                    return WarpDeleteResult.NOT_AUTHORIZED;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM vertex_faction_warps WHERE faction_id=? AND name=?")) {
                    statement.setInt(1, factionId);
                    statement.setString(2, normalize(name));
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return WarpDeleteResult.NOT_FOUND;
                    }
                }
                connection.commit();
                return WarpDeleteResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }
    /** Raw import/setup helper. Runtime invitations use {@link #saveInviteChecked}. */
    public void saveInvite(Invite invite)throws SQLException {
        try(Connection connection=database.getConnection()){saveInvite(connection,invite);}
    }

    /** Prevents stale shards from inviting after demotion/disband or inviting an existing member. */
    public InviteWriteResult saveInviteChecked(Invite invite, FactionRole expectedRole,
            boolean defaultAllowed) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (lockFaction(connection, invite.factionId()) == null) {
                    connection.rollback();
                    return InviteWriteResult.FACTION_CHANGED;
                }
                FactionMemberState inviter = invite.invitedBy() == null ? null
                        : lockMember(connection, invite.invitedBy());
                if (inviter == null || inviter.factionId() != invite.factionId()
                        || inviter.role() != expectedRole
                        || !durableActionAllowed(connection, invite.factionId(), inviter.role(),
                        "invite", defaultAllowed)) {
                    connection.rollback();
                    return InviteWriteResult.NOT_AUTHORIZED;
                }
                if (lockMember(connection, invite.playerUuid()) != null) {
                    connection.rollback();
                    return InviteWriteResult.ALREADY_MEMBER;
                }
                if (isBanned(connection, invite.factionId(), invite.playerUuid())) {
                    connection.rollback();
                    return InviteWriteResult.BANNED;
                }
                saveInvite(connection, invite);
                connection.commit();
                return InviteWriteResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }
    public void deleteInvite(int factionId,UUID player)throws SQLException {try(Connection c=database.getConnection()){deleteInvite(c, factionId, player);}}
    public void savePlayerSettings(UUID uuid,PlayerSettings settings)throws SQLException {String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO vertex_faction_players (player_uuid,chat_mode,autoclaim,map_enabled) VALUES (?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET chat_mode=excluded.chat_mode,autoclaim=excluded.autoclaim,map_enabled=excluded.map_enabled":"INSERT INTO vertex_faction_players (player_uuid,chat_mode,autoclaim,map_enabled) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE chat_mode=VALUES(chat_mode),autoclaim=VALUES(autoclaim),map_enabled=VALUES(map_enabled)";try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,uuid.toString());s.setString(2,settings.chatMode());s.setBoolean(3,settings.autoclaim());s.setBoolean(4,settings.mapEnabled());s.executeUpdate();}}

    public void savePower(FactionPowerProfile profile) throws SQLException {
        try (Connection connection = database.getConnection()) {
            savePower(connection, profile);
        }
    }

    /** Saves personal power and its derived faction totals in one SQL commit. */
    public void savePowerAndFaction(FactionPowerProfile profile, FactionData faction) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                savePower(connection, profile);
                if (faction != null) saveFaction(connection, faction);
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /**
     * Saves personal power and derives faction totals from durable membership.
     * This avoids writing an entire stale cached faction row from one shard.
     */
    public FactionTotals savePowerAndAggregate(FactionPowerProfile profile) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Integer factionId = memberFaction(connection, profile.playerUuid());
                if (factionId != null && lockFaction(connection, factionId) == null) factionId = null;
                lockPowerProfile(connection, profile.playerUuid());
                Integer durableFaction = lockMemberFaction(connection, profile.playerUuid());
                if (!java.util.Objects.equals(factionId, durableFaction)) factionId = durableFaction;
                savePower(connection, profile);
                FactionTotals totals = factionId == null ? null : recalculateFactionPower(connection, factionId);
                connection.commit();
                return totals;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    private String idColumn() { return database.dialect() == Database.Dialect.SQLITE ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "INT AUTO_INCREMENT PRIMARY KEY"; }

    private static void deleteFactionRowsIfPresent(Connection connection, String table,
            String column, int factionId) throws SQLException {
        if (!tableExists(connection, table)) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + column + "=?")) {
            statement.setInt(1, factionId);
            statement.executeUpdate();
        }
    }

    private static void deleteFactionPairRowsIfPresent(Connection connection, String table,
            String leftColumn, String rightColumn, int factionId) throws SQLException {
        if (!tableExists(connection, table)) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + leftColumn + "=? OR " + rightColumn + "=?")) {
            statement.setInt(1, factionId);
            statement.setInt(2, factionId);
            statement.executeUpdate();
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        return SqlSchema.tableExists(connection, table);
    }
    public void deleteClaimsForFaction(int factionId) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM vertex_faction_claims WHERE faction_id=?")) {
            statement.setInt(1, factionId);
            statement.executeUpdate();
        }
    }
    private void saveFaction(Connection connection, FactionData faction) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE vertex_factions SET tag=?,tag_key=?,description=?,open_join=?,power=?,power_max=?,home_shard=?,home_world=?,home_x=?,home_y=?,home_z=?,home_yaw=?,home_pitch=? WHERE id=?")) {
            statement.setString(1, faction.tag()); statement.setString(2, normalize(faction.tag())); statement.setString(3, faction.description()); statement.setBoolean(4, faction.open()); statement.setDouble(5, faction.power()); statement.setDouble(6, faction.powerMax());
            FactionData.Home home = faction.home(); if (home == null) { statement.setNull(7, java.sql.Types.VARCHAR); statement.setNull(8, java.sql.Types.VARCHAR); statement.setNull(9, java.sql.Types.DOUBLE); statement.setNull(10, java.sql.Types.DOUBLE); statement.setNull(11, java.sql.Types.DOUBLE); statement.setNull(12, java.sql.Types.FLOAT); statement.setNull(13, java.sql.Types.FLOAT); } else { statement.setString(7, home.shardId()); statement.setString(8, home.world()); statement.setDouble(9, home.x()); statement.setDouble(10, home.y()); statement.setDouble(11, home.z()); statement.setFloat(12, home.yaw()); statement.setFloat(13, home.pitch()); }
            statement.setInt(14, faction.id()); statement.executeUpdate();
        }
    }

    private boolean updateFactionField(int factionId, String column, SqlBinder binder) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                FactionCapacity faction = lockFaction(connection, factionId);
                if (faction == null || faction.system()) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE vertex_factions SET " + column + "=? WHERE id=?")) {
                    binder.bind(statement);
                    statement.setInt(2, factionId);
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    private FactionFieldWriteResult updateFactionFieldChecked(UUID actor, int factionId,
            FactionRole expectedRole, String action, boolean defaultAllowed, String column,
            SqlBinder binder) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                FactionCapacity faction = lockFaction(connection, factionId);
                if (faction == null || faction.system()) {
                    connection.rollback();
                    return FactionFieldWriteResult.FACTION_CHANGED;
                }
                FactionMemberState member = lockMember(connection, actor);
                if (member == null || member.factionId() != factionId || member.role() != expectedRole
                        || !durableActionAllowed(connection, factionId, member.role(), action,
                        defaultAllowed)) {
                    connection.rollback();
                    return FactionFieldWriteResult.NOT_AUTHORIZED;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE vertex_factions SET " + column + "=? WHERE id=?")) {
                    binder.bind(statement);
                    statement.setInt(2, factionId);
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return FactionFieldWriteResult.FACTION_CHANGED;
                    }
                }
                connection.commit();
                return FactionFieldWriteResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    private static boolean writeHome(Connection connection, int factionId, FactionData.Home home)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE vertex_factions SET home_shard=?,home_world=?,home_x=?,home_y=?,home_z=?,home_yaw=?,home_pitch=? WHERE id=?")) {
            if (home == null) {
                for (int index = 1; index <= 7; index++) statement.setNull(index,
                        index <= 2 ? java.sql.Types.VARCHAR : index <= 5
                                ? java.sql.Types.DOUBLE : java.sql.Types.FLOAT);
            } else {
                statement.setString(1, home.shardId());
                statement.setString(2, home.world());
                statement.setDouble(3, home.x());
                statement.setDouble(4, home.y());
                statement.setDouble(5, home.z());
                statement.setFloat(6, home.yaw());
                statement.setFloat(7, home.pitch());
            }
            statement.setInt(8, factionId);
            return statement.executeUpdate() == 1;
        }
    }

    private void saveRenameCooldown(Connection connection, int factionId, long availableAt)
            throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO vertex_faction_cooldowns(faction_id,rename_available_at) VALUES(?,?) ON CONFLICT(faction_id) DO UPDATE SET rename_available_at=excluded.rename_available_at"
                : "INSERT INTO vertex_faction_cooldowns(faction_id,rename_available_at) VALUES(?,?) ON DUPLICATE KEY UPDATE rename_available_at=VALUES(rename_available_at)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, factionId);
            statement.setLong(2, availableAt);
            statement.executeUpdate();
        }
    }

    private static boolean isUniqueViolation(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (current.getErrorCode() == 1062 || "23505".equals(state)
                    || state != null && state.startsWith("23")
                    && current.getMessage() != null
                    && current.getMessage().toLowerCase(java.util.Locale.ROOT).contains("unique")) return true;
        }
        return false;
    }

    @FunctionalInterface
    private interface SqlBinder { void bind(PreparedStatement statement) throws SQLException; }

    private void saveClaim(Connection connection,ChunkKey key,int factionId)throws SQLException {
        String sql=database.dialect()==Database.Dialect.SQLITE
                ?"INSERT INTO vertex_faction_claims (world,chunk_x,chunk_z,faction_id) VALUES (?,?,?,?) ON CONFLICT(world,chunk_x,chunk_z) DO UPDATE SET faction_id=excluded.faction_id"
                :"INSERT INTO vertex_faction_claims (world,chunk_x,chunk_z,faction_id) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE faction_id=VALUES(faction_id)";
        try(PreparedStatement s=connection.prepareStatement(sql)){s.setString(1,key.world());s.setInt(2,key.x());s.setInt(3,key.z());s.setInt(4,factionId);s.executeUpdate();}
    }

    private FactionCapacity lockFaction(Connection connection,int factionId)throws SQLException {
        String suffix=database.dialect()==Database.Dialect.MYSQL?" FOR UPDATE":"";
        try(PreparedStatement s=connection.prepareStatement(
                "SELECT power,system_faction FROM vertex_factions WHERE id=?"+suffix)){
            s.setInt(1,factionId);
            try(ResultSet row=s.executeQuery()){
                return row.next()?new FactionCapacity(row.getDouble(1),row.getBoolean(2)):null;
            }
        }
    }

    private Integer lockClaimOwner(Connection connection,ChunkKey key)throws SQLException {
        String suffix=database.dialect()==Database.Dialect.MYSQL?" FOR UPDATE":"";
        try(PreparedStatement s=connection.prepareStatement(
                "SELECT faction_id FROM vertex_faction_claims WHERE world=? AND chunk_x=? AND chunk_z=?"+suffix)){
            s.setString(1,key.world());s.setInt(2,key.x());s.setInt(3,key.z());
            try(ResultSet row=s.executeQuery()){return row.next()?row.getInt(1):null;}
        }
    }

    private static int countClaims(Connection connection,int factionId)throws SQLException {
        try(PreparedStatement s=connection.prepareStatement(
                "SELECT COUNT(*) FROM vertex_faction_claims WHERE faction_id=?")){
            s.setInt(1,factionId);
            try(ResultSet row=s.executeQuery()){return row.next()?row.getInt(1):0;}
        }
    }

    private void lockPowerProfile(Connection connection, UUID player) throws SQLException {
        String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid FROM vertex_player_power WHERE player_uuid=?" + suffix)) {
            statement.setString(1, player.toString());
            try (ResultSet ignored = statement.executeQuery()) {
                // Acquiring the row/gap lock is the purpose of this query.
            }
        }
    }

    private Integer lockMemberFaction(Connection connection, UUID player) throws SQLException {
        String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT faction_id FROM vertex_faction_members WHERE player_uuid=?" + suffix)) {
            statement.setString(1, player.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getInt(1) : null;
            }
        }
    }

    private static Integer memberFaction(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT faction_id FROM vertex_faction_members WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getInt(1) : null;
            }
        }
    }

    private FactionJoinState lockFactionForJoin(Connection connection, int factionId) throws SQLException {
        String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT open_join,system_faction FROM vertex_factions WHERE id=?" + suffix)) {
            statement.setInt(1, factionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new FactionJoinState(row.getBoolean(1), row.getBoolean(2)) : null;
            }
        }
    }

    private static int countMembers(Connection connection, int factionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM vertex_faction_members WHERE faction_id=?")) {
            statement.setInt(1, factionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getInt(1) : 0;
            }
        }
    }

    private FactionMemberState lockMember(Connection connection, UUID player) throws SQLException {
        String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT faction_id,role FROM vertex_faction_members WHERE player_uuid=?" + suffix)) {
            statement.setString(1, player.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new FactionMemberState(row.getInt(1),
                        FactionRole.parse(row.getString(2), FactionRole.RECRUIT)) : null;
            }
        }
    }

    private static int countRole(Connection connection, int factionId, FactionRole role,
            UUID excluded) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM vertex_faction_members WHERE faction_id=? AND role=? AND player_uuid<>?")) {
            statement.setInt(1, factionId);
            statement.setString(2, role.name());
            statement.setString(3, excluded.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getInt(1) : 0;
            }
        }
    }

    private FactionMember lockBestSuccessor(Connection connection, int factionId, UUID excluded)
            throws SQLException {
        String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
        String sql = "SELECT player_uuid,role,last_name,joined_at FROM vertex_faction_members "
                + "WHERE faction_id=? AND player_uuid<>? ORDER BY CASE role "
                + "WHEN 'COLEADER' THEN 4 WHEN 'ADMIN' THEN 3 WHEN 'MODERATOR' THEN 2 "
                + "WHEN 'MEMBER' THEN 1 ELSE 0 END DESC,joined_at ASC,player_uuid ASC LIMIT 1" + suffix;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, factionId);
            statement.setString(2, excluded.toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                UUID uuid = UUID.fromString(row.getString(1));
                return new FactionMember(uuid, factionId,
                        FactionRole.parse(row.getString(2), FactionRole.RECRUIT),
                        row.getString(3), row.getLong(4));
            }
        }
    }

    private static boolean hasLiveInvite(Connection connection, int factionId, UUID player, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM vertex_faction_invites WHERE faction_id=? AND player_uuid=? AND expires_at>=?")) {
            statement.setInt(1, factionId);
            statement.setString(2, player.toString());
            statement.setLong(3, now);
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }

    private static boolean isBanned(Connection connection, int factionId, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM vertex_faction_bans WHERE faction_id=? AND player_uuid=?")) {
            statement.setInt(1, factionId);
            statement.setString(2, player.toString());
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }

    private static void insertMember(Connection connection, FactionMember member) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vertex_faction_members(player_uuid,faction_id,role,last_name,joined_at) VALUES(?,?,?,?,?)")) {
            statement.setString(1, member.playerUuid().toString());
            statement.setInt(2, member.factionId());
            statement.setString(3, member.role().name());
            statement.setString(4, member.lastName());
            statement.setLong(5, member.joinedAtMillis());
            statement.executeUpdate();
        }
    }

    private static FactionTotals recalculateFactionPower(Connection connection, int factionId)
            throws SQLException {
        double current = 0D;
        double maximum = 0D;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(p.current_power),0),COALESCE(SUM(p.max_power),0) "
                        + "FROM vertex_faction_members m LEFT JOIN vertex_player_power p "
                        + "ON p.player_uuid=m.player_uuid WHERE m.faction_id=?")) {
            statement.setInt(1, factionId);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) { current = row.getDouble(1); maximum = row.getDouble(2); }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE vertex_factions SET power=?,power_max=? WHERE id=?")) {
            statement.setDouble(1, current);
            statement.setDouble(2, maximum);
            statement.setInt(3, factionId);
            statement.executeUpdate();
        }
        return new FactionTotals(factionId, current, maximum);
    }

    private static boolean hasIndex(Connection connection, String table, String index) throws SQLException {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
            while (indexes.next()) if (index.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
        }
        return false;
    }
    private void saveMember(Connection connection, FactionMember member) throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE ? "INSERT INTO vertex_faction_members (player_uuid,faction_id,role,last_name,joined_at) VALUES (?,?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET faction_id=excluded.faction_id,role=excluded.role,last_name=excluded.last_name,joined_at=excluded.joined_at" : "INSERT INTO vertex_faction_members (player_uuid,faction_id,role,last_name,joined_at) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE faction_id=VALUES(faction_id),role=VALUES(role),last_name=VALUES(last_name),joined_at=VALUES(joined_at)";
        try (PreparedStatement statement=connection.prepareStatement(sql)) { statement.setString(1, member.playerUuid().toString()); statement.setInt(2, member.factionId()); statement.setString(3, member.role().name()); statement.setString(4, member.lastName()); statement.setLong(5, member.joinedAtMillis()); statement.executeUpdate(); }
    }
    private void savePermission(Connection connection,int factionId,String role,String action,boolean allowed)throws SQLException {String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO vertex_faction_permissions (faction_id,role,action_key,allowed) VALUES (?,?,?,?) ON CONFLICT(faction_id,role,action_key) DO UPDATE SET allowed=excluded.allowed":"INSERT INTO vertex_faction_permissions (faction_id,role,action_key,allowed) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE allowed=VALUES(allowed)";try(PreparedStatement statement=connection.prepareStatement(sql)){statement.setInt(1,factionId);statement.setString(2,normalize(role));statement.setString(3,normalize(action));statement.setBoolean(4,allowed);statement.executeUpdate();}}
    private void saveWarp(Connection connection,FactionWarp warp)throws SQLException {String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO vertex_faction_warps (faction_id,name,shard_id,world,x,y,z,yaw,pitch) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(faction_id,name) DO UPDATE SET shard_id=excluded.shard_id,world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch":"INSERT INTO vertex_faction_warps (faction_id,name,shard_id,world,x,y,z,yaw,pitch) VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE shard_id=VALUES(shard_id),world=VALUES(world),x=VALUES(x),y=VALUES(y),z=VALUES(z),yaw=VALUES(yaw),pitch=VALUES(pitch)";try(PreparedStatement statement=connection.prepareStatement(sql)){FactionData.Home home=warp.location();statement.setInt(1,warp.factionId());statement.setString(2,normalize(warp.name()));statement.setString(3,home.shardId());statement.setString(4,home.world());statement.setDouble(5,home.x());statement.setDouble(6,home.y());statement.setDouble(7,home.z());statement.setFloat(8,home.yaw());statement.setFloat(9,home.pitch());statement.executeUpdate();}}
    private void saveInvite(Connection connection,Invite invite)throws SQLException {String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO vertex_faction_invites (faction_id,player_uuid,invited_by,expires_at) VALUES (?,?,?,?) ON CONFLICT(faction_id,player_uuid) DO UPDATE SET invited_by=excluded.invited_by,expires_at=excluded.expires_at":"INSERT INTO vertex_faction_invites (faction_id,player_uuid,invited_by,expires_at) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE invited_by=VALUES(invited_by),expires_at=VALUES(expires_at)";try(PreparedStatement statement=connection.prepareStatement(sql)){statement.setInt(1,invite.factionId());statement.setString(2,invite.playerUuid().toString());statement.setString(3,invite.invitedBy()==null?null:invite.invitedBy().toString());statement.setLong(4,invite.expiresAtMillis());statement.executeUpdate();}}
    private static boolean canEditBucket(FactionRole editor, String rawBucket) {
        String bucket = normalize(rawBucket);
        if (bucket.equals("ally")) return editor == FactionRole.LEADER || editor == FactionRole.COLEADER;
        FactionRole target = FactionRole.parse(bucket, null);
        if (target == null || target == FactionRole.LEADER) return false;
        return editor == FactionRole.LEADER
                || editor == FactionRole.COLEADER && target.weight() <= FactionRole.ADMIN.weight()
                || editor == FactionRole.ADMIN
                && (target == FactionRole.MEMBER || target == FactionRole.RECRUIT);
    }
    private static boolean durableActionAllowed(Connection connection, int factionId, FactionRole role,
            String action, boolean defaultAllowed) throws SQLException {
        if (role == FactionRole.LEADER) return true;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT allowed FROM vertex_faction_permissions WHERE faction_id=? AND role=? AND action_key=?")) {
            statement.setInt(1, factionId);
            statement.setString(2, role.permissionBucket());
            statement.setString(3, normalize(action));
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getBoolean(1) : defaultAllowed;
            }
        }
    }
    private static boolean durableAllyActionAllowed(Connection connection, int factionId, String action)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT allowed FROM vertex_faction_permissions WHERE faction_id=? AND role='ally' AND action_key=?")) {
            statement.setInt(1, factionId);
            statement.setString(2, normalize(action));
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        }
    }
    private static FactionRelation durableRelation(Connection connection, int left, int right)
            throws SQLException {
        FactionRelation forward = oneWayRelation(connection, left, right);
        FactionRelation reverse = oneWayRelation(connection, right, left);
        if (forward == FactionRelation.ENEMY || reverse == FactionRelation.ENEMY) {
            return FactionRelation.ENEMY;
        }
        return forward == FactionRelation.ALLY && reverse == FactionRelation.ALLY
                ? FactionRelation.ALLY : FactionRelation.NEUTRAL;
    }
    private static FactionRelation oneWayRelation(Connection connection, int left, int right)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT relation FROM vertex_faction_relations WHERE faction_id=? AND target_faction_id=?")) {
            statement.setInt(1, left);
            statement.setInt(2, right);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? FactionRelation.parse(row.getString(1), FactionRelation.NEUTRAL)
                        : FactionRelation.NEUTRAL;
            }
        }
    }
    private static boolean warpExists(Connection connection, int factionId, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM vertex_faction_warps WHERE faction_id=? AND name=?")) {
            statement.setInt(1, factionId);
            statement.setString(2, normalize(name));
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }
    private static int countWarps(Connection connection, int factionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM vertex_faction_warps WHERE faction_id=?")) {
            statement.setInt(1, factionId);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
        }
    }
    private void deleteMember(Connection connection, UUID uuid) throws SQLException { try(PreparedStatement statement=connection.prepareStatement("DELETE FROM vertex_faction_members WHERE player_uuid=?")){statement.setString(1,uuid.toString());statement.executeUpdate();} }
    private void deleteInvite(Connection connection, int factionId, UUID player) throws SQLException { try(PreparedStatement statement=connection.prepareStatement("DELETE FROM vertex_faction_invites WHERE faction_id=? AND player_uuid=?")){statement.setInt(1,factionId);statement.setString(2,player.toString());statement.executeUpdate();} }
    private void saveRelation(Connection connection,int factionId,int target,FactionRelation relation)throws SQLException {String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO vertex_faction_relations (faction_id,target_faction_id,relation) VALUES (?,?,?) ON CONFLICT(faction_id,target_faction_id) DO UPDATE SET relation=excluded.relation":"INSERT INTO vertex_faction_relations (faction_id,target_faction_id,relation) VALUES (?,?,?) ON DUPLICATE KEY UPDATE relation=VALUES(relation)";try(PreparedStatement s=connection.prepareStatement(sql)){s.setInt(1,factionId);s.setInt(2,target);s.setString(3,relation.name());s.executeUpdate();}}
    private static void deleteRelation(Connection connection,int factionId,int target)throws SQLException {try(PreparedStatement s=connection.prepareStatement("DELETE FROM vertex_faction_relations WHERE faction_id=? AND target_faction_id=?")){s.setInt(1,factionId);s.setInt(2,target);s.executeUpdate();}}
    private void savePower(Connection connection, FactionPowerProfile profile) throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO vertex_player_power (player_uuid,current_power,max_power,next_regen_at) VALUES (?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET current_power=excluded.current_power,max_power=excluded.max_power,next_regen_at=excluded.next_regen_at"
                : "INSERT INTO vertex_player_power (player_uuid,current_power,max_power,next_regen_at) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE current_power=VALUES(current_power),max_power=VALUES(max_power),next_regen_at=VALUES(next_regen_at)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profile.playerUuid().toString());
            statement.setDouble(2, profile.current());
            statement.setDouble(3, profile.maximum());
            statement.setLong(4, profile.nextRegenerationAtMillis());
            statement.executeUpdate();
        }
    }

    private void saveCreateCooldown(Connection connection, UUID uuid, long availableAt) throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO vertex_faction_player_cooldowns(player_uuid,create_available_at) VALUES(?,?) ON CONFLICT(player_uuid) DO UPDATE SET create_available_at=excluded.create_available_at"
                : "INSERT INTO vertex_faction_player_cooldowns(player_uuid,create_available_at) VALUES(?,?) ON DUPLICATE KEY UPDATE create_available_at=VALUES(create_available_at)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, availableAt);
            statement.executeUpdate();
        }
    }
    private static String normalize(String raw) { return raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT); }

    public record LoadedState(Map<Integer,FactionData> factions,Map<UUID,FactionMember> members,
            Map<ChunkKey,Integer> claims,Map<RelationKey,FactionRelation> relations,
            Map<PermissionKey,Boolean> permissions,Map<WarpKey,FactionWarp> warps,
            Map<UUID,PlayerSettings> players,Map<InviteKey,Invite> invites,
            Map<UUID,FactionPowerProfile> powerProfiles,Map<UUID,Long> createCooldowns,
            Map<Integer,Long> renameCooldowns) { }
    public record RelationKey(int factionId,int targetId) { }
    public record PermissionKey(int factionId,String role,String action) { }
    public record WarpKey(int factionId,String name) { }
    public record InviteKey(int factionId,UUID playerUuid) { }
    public record Invite(int factionId,UUID playerUuid,UUID invitedBy,long expiresAtMillis) { }
    /** Previous durable owner captured while an all-or-nothing system claim is committed. */
    public record ClaimOwnerChange(ChunkKey key, Integer previousFactionId) { }
    public record PlayerSettings(String chatMode,boolean autoclaim,boolean mapEnabled) { public static final PlayerSettings DEFAULT=new PlayerSettings("PUBLIC",false,false); }
    public enum CreateFactionWriteResult { OK, ALREADY_MEMBER, COOLDOWN, NAME_TAKEN }
    public record CreateFactionOutcome(CreateFactionWriteResult result, int factionId) { }
    public enum ClaimWriteResult {
        OK, OWNER_CHANGED, CLAIM_LIMIT, INSUFFICIENT_POWER, OVERCLAIM_DENIED, NOT_AUTHORIZED
    }
    public enum ClaimDeleteResult { OK, OWNER_CHANGED, NOT_AUTHORIZED }
    public enum JoinWriteResult { OK, ALREADY_MEMBER, NOT_FOUND, MEMBER_LIMIT, BANNED, NOT_INVITED }
    public record JoinOutcome(JoinWriteResult result, FactionTotals totals) { }
    public enum MemberWriteResult { OK, MEMBER_CHANGED, LEADER, NOT_AUTHORIZED }
    public record MemberMutationOutcome(MemberWriteResult result, FactionTotals totals) { }
    public enum RoleWriteResult { OK, MEMBER_CHANGED, ROLE_LIMIT, INVALID, NOT_AUTHORIZED }
    public enum LeadershipWriteResult { OK, MEMBER_CHANGED }
    public enum LeaderDepartureResult { OK, MEMBER_CHANGED, NO_SUCCESSOR }
    public enum RenameWriteResult {
        OK, NOT_FOUND, SYSTEM_FACTION, NAME_TAKEN, COOLDOWN, NOT_AUTHORIZED
    }
    public enum DisbandWriteResult { OK, FACTION_CHANGED, NOT_AUTHORIZED }
    public enum FactionFieldWriteResult { OK, FACTION_CHANGED, NOT_AUTHORIZED }
    public enum HomeWriteResult { OK, FACTION_CHANGED, NOT_AUTHORIZED, NOT_OWNER }
    public enum PermissionWriteResult { OK, FACTION_CHANGED, NOT_AUTHORIZED }
    public enum WarpWriteResult { OK, FACTION_CHANGED, NOT_AUTHORIZED, NOT_OWNER, LIMIT_REACHED }
    public enum WarpDeleteResult { OK, FACTION_CHANGED, NOT_AUTHORIZED, NOT_FOUND }
    public enum InviteWriteResult { OK, FACTION_CHANGED, NOT_AUTHORIZED, ALREADY_MEMBER, BANNED }
    public record LeaderDepartureOutcome(LeaderDepartureResult result, FactionMember successor,
                                         FactionTotals totals) { }
    public record FactionTotals(int factionId, double current, double maximum) { }
    private record FactionCapacity(double power,boolean system) { }
    private record FactionJoinState(boolean open, boolean system) { }
    private record FactionMemberState(int factionId, FactionRole role) { }
}
