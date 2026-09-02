package me.hcfcore.core.storage;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

public interface Storage {

    void init() throws SQLException;

    Map<String, Long> loadCooldowns(UUID uuid) throws SQLException;

    void saveCooldown(UUID uuid, String kitName, long availableAt) throws SQLException;

    Map<String, Long> loadAbilityCooldowns(UUID uuid) throws SQLException;

    void saveAbilityCooldown(UUID uuid, String abilityId, long availableAt) throws SQLException;

    void close();
}
