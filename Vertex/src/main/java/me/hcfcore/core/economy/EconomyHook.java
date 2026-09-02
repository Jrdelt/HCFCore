package me.hcfcore.core.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Locale;

/**
 * Thin wrapper around Vault's economy service. Looked up fresh on every
 * call rather than cached, since Vault (or the economy plugin behind it)
 * may not have registered its provider yet at HCFCore's onEnable time.
 */
public final class EconomyHook {

    private EconomyHook() {
    }

    public static Economy getEconomy() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : provider.getProvider();
    }

    public static String format(double amount) {
        Economy economy = getEconomy();
        if (economy != null) {
            return economy.format(amount);
        }
        return String.format(Locale.ROOT, "%.2f", amount);
    }
}
