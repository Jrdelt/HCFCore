package me.hcfcore.core.storage;

import me.hcfcore.core.staff.Death;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface Storage {

    void init() throws SQLException;

    Map<String, Long> loadCooldowns(UUID uuid) throws SQLException;

    void saveCooldown(UUID uuid, String kitName, long availableAt) throws SQLException;

    Map<String, Long> loadAbilityCooldowns(UUID uuid) throws SQLException;

    void saveAbilityCooldown(UUID uuid, String abilityId, long availableAt) throws SQLException;

    String loadLocale(UUID uuid) throws SQLException;

    void saveLocale(UUID uuid, String locale) throws SQLException;

    void saveDeath(UUID uuid, Death death) throws SQLException;

    List<Death> loadDeaths(UUID uuid, int limit) throws SQLException;

    void close();
}
