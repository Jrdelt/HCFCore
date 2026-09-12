package me.vertex.core.enchant;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.zone.ZoneManager;
import me.vertex.core.zone.ZoneType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Arena-only runes that intentionally remain separate from the legacy custom-enchant tables. */
public final class ArenaRuneManager {
    public enum Currency { XP_LEVELS, MONEY }
    public enum Purchase { RUNE }
    public enum ApplyResult { SUCCESS, FAILURE, INCOMPATIBLE, ALREADY_APPLIED, INVALID }

    public enum Effect {
        VOID_SHIELD("void-shield", "Void Shield", 0.5D, "Reduces mob damage taken"),
        ECLIPSE_WARD("eclipse-ward", "Eclipse Ward", 0.25D, "Reduces PvP damage taken"),
        ECLIPSE_STRIKE("eclipse-strike", "Eclipse Strike", 0.25D, "Increases PvP damage dealt"),
        ABYSSAL_SCAVENGER("abyssal-scavenger", "Abyssal Scavenger", 0.5D, "Increases mob drops"),
        ABYSSAL_INSIGHT("abyssal-insight", "Abyssal Insight", 0.5D, "Increases mob-kill XP"),
        ABYSSAL_FEAST("abyssal-feast", "Abyssal Feast", 0.1D, "Heals max health on mob kills"),
        CORRUPTED_DETONATION("corrupted-detonation", "Corrupted Detonation", 0.1D, "30% chance to damage nearby mobs");

        private final String id;
        private final String displayName;
        private final double perLevel;
        private final String description;
        Effect(String id, String displayName, double perLevel, String description) {
            this.id = id; this.displayName = displayName; this.perLevel = perLevel; this.description = description;
        }
        public String id() { return id; }
        public String displayName() { return displayName; }
        public String description() { return description; }
        public double valueAt(int level) { return perLevel * Math.max(1, Math.min(20, level)); }
        public boolean restrictedEquipment() { return this == ECLIPSE_WARD || this == ECLIPSE_STRIKE; }
        static Effect byId(String id) {
            for (Effect effect : values()) if (effect.id.equalsIgnoreCase(id)) return effect;
            return null;
        }
    }

    public record RuneInfo(Effect effect, int level, double successRate) { }
    public record ApplyOutcome(ApplyResult result, RuneInfo info, boolean consumeEnchant) { }

    private final Plugin plugin;
    private final ZoneManager zones;
    private final NamespacedKey baseRuneKey;
    private final NamespacedKey enchantKey;
    private final NamespacedKey successKey;
    private final NamespacedKey luckyGemKey;
    private final NamespacedKey universalLuckyGemKey;
    private final NamespacedKey appliedKey;
    private Currency currency = Currency.XP_LEVELS;
    private double runePrice = 100D;
    private double detonationRadius = 5D;

    public ArenaRuneManager(Plugin plugin, ZoneManager zones) {
        this.plugin = plugin;
        this.zones = zones;
        baseRuneKey = new NamespacedKey(plugin, "arena_rune");
        enchantKey = new NamespacedKey(plugin, "arena_enchant");
        successKey = new NamespacedKey(plugin, "arena_enchant_success");
        luckyGemKey = new NamespacedKey(plugin, "arena_lucky_gem");
        universalLuckyGemKey = new NamespacedKey(plugin, "lucky_gem");
        appliedKey = new NamespacedKey(plugin, "arena_enchants");
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "arena-runes.yml");
        if (!file.exists()) plugin.saveResource("arena-runes.yml", false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        try { currency = Currency.valueOf(config.getString("shop.currency", "XP_LEVELS").trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { currency = Currency.XP_LEVELS; }
        runePrice = Math.max(0D, config.getDouble("shop.arena-rune-price", 100D));
        detonationRadius = Math.max(1D, config.getDouble("corrupted-detonation.radius", 5D));
    }

    public Plugin plugin() { return plugin; }
    public double detonationRadius() { return detonationRadius; }
    public boolean inArena(Player player) { return player != null && (zones.isIn(player, ZoneType.HAVEN) || zones.isIn(player, ZoneType.RIFTLANDS)); }
    public boolean inArena(org.bukkit.Location location) { return location != null && zones.isInAnyZone(location); }

    public ItemStack createRune() {
        ItemStack item = icon(Material.BLACK_CANDLE, RuneFormatting.plain("ᴍᴏʙ ᴀʀᴇɴᴀ ʀᴜɴᴇ", NamedTextColor.BLUE), List.of(
                RuneFormatting.plain("ᴄᴜꜱᴛᴏᴍ ᴇɴᴄʜᴀɴᴛᴍᴇɴᴛ", NamedTextColor.DARK_GRAY),
                RuneFormatting.plain("ʀɪɢʜᴛ-ᴄʟɪᴄᴋ ᴛᴏ ɪᴅᴇɴᴛɪꜰʏ ᴀ ʀᴀɴᴅᴏᴍ ʀᴜɴᴇ", NamedTextColor.GRAY),
                RuneFormatting.plain("ʟᴇᴠᴇʟꜱ ɪ-XX • ꜱᴛᴀᴄᴋᴀʙʟᴇ ᴜᴘ ᴛᴏ 64", NamedTextColor.DARK_GRAY)));
        item.getItemMeta().getPersistentDataContainer();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(baseRuneKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack rollRune() {
        Effect effect = Effect.values()[ThreadLocalRandom.current().nextInt(Effect.values().length)];
        int bucket = ThreadLocalRandom.current().nextInt(1_000);
        int level = bucket < 600 ? ThreadLocalRandom.current().nextInt(1, 11)
                : bucket < 850 ? ThreadLocalRandom.current().nextInt(11, 16)
                : bucket < 990 ? ThreadLocalRandom.current().nextInt(16, 20) : 20;
        int success = ThreadLocalRandom.current().nextInt(15, 86);
        return createEnchantItem(new RuneInfo(effect, level, success));
    }

    public ItemStack createEnchantItem(RuneInfo info) {
        List<Component> lore = new ArrayList<>();
        lore.add(RuneFormatting.plain("ᴄᴜꜱᴛᴏᴍ ᴇɴᴄʜᴀɴᴛᴍᴇɴᴛ", NamedTextColor.DARK_GRAY));
        lore.add(RuneFormatting.plain(RuneFormatting.smallCaps(info.effect().description()) + ": +"
                + RuneFormatting.percent(info.effect().valueAt(info.level())) + "%", NamedTextColor.GRAY));
        if (info.effect() == Effect.CORRUPTED_DETONATION) {
            lore.add(RuneFormatting.plain("ᴘʀᴏᴄ ᴄʜᴀɴᴄᴇ: 30%", NamedTextColor.GRAY));
        }
        lore.add(RuneFormatting.plain("ᴀᴘᴘʟɪᴄᴀᴛɪᴏɴ ꜱᴜᴄᴄᴇꜱꜱ: ", NamedTextColor.GRAY)
                .append(RuneFormatting.plain(RuneFormatting.percent(info.successRate()) + "%", NamedTextColor.GREEN))
                .append(RuneFormatting.plain(" / ꜰᴀɪʟ: ", NamedTextColor.GRAY))
                .append(RuneFormatting.plain(RuneFormatting.percent(100D - info.successRate()) + "%", NamedTextColor.RED)));
        lore.add(RuneFormatting.plain("ᴀᴄᴛɪᴠᴇ ᴏɴʟʏ ɪɴ ʜᴀᴠᴇɴ ᴀɴᴅ ʀɪꜰᴛʟᴀɴᴅꜱ", NamedTextColor.DARK_PURPLE));
        lore.add(RuneFormatting.plain("ᴅʀᴀɢ ᴏɴᴛᴏ ᴄᴏᴍᴘᴀᴛɪʙʟᴇ ᴇǫᴜɪᴘᴍᴇɴᴛ ᴛᴏ ᴀᴘᴘʟʏ", NamedTextColor.DARK_GRAY));
        ItemStack item = star(Color.BLACK, Color.WHITE,
                RuneFormatting.title(info.effect().displayName(), info.level(), NamedTextColor.BLUE, info.level() == 20), lore);
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(enchantKey, PersistentDataType.STRING, info.effect().id() + ":" + info.level());
        pdc.set(successKey, PersistentDataType.DOUBLE, info.successRate());
        item.setItemMeta(meta);
        return item;
    }

    public boolean isRune(ItemStack item) { return tagged(item, baseRuneKey); }
    /** Recognizes the retired Arena gem marker and the current universal marker. */
    public boolean isLuckyGem(ItemStack item) {
        return tagged(item, luckyGemKey) || tagged(item, universalLuckyGemKey);
    }
    public boolean isEnchant(ItemStack item) { return info(item) != null; }
    private boolean tagged(ItemStack item, NamespacedKey key) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public RuneInfo info(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String raw = pdc.get(enchantKey, PersistentDataType.STRING);
        Double success = pdc.get(successKey, PersistentDataType.DOUBLE);
        if (raw == null || success == null) return null;
        String[] split = raw.split(":", 2);
        if (split.length != 2) return null;
        Effect effect = Effect.byId(split[0]);
        try {
            int level = Integer.parseInt(split[1]);
            return effect == null || level < 1 || level > 20 ? null : new RuneInfo(effect, level, clamp(success, 0D, 100D));
        } catch (NumberFormatException ignored) { return null; }
    }

    /** Returns one upgraded physical enchant, or null once its success chance is already capped. */
    public ItemStack addLuckyGem(ItemStack enchantItem) {
        RuneInfo info = info(enchantItem);
        if (info == null || info.successRate() >= 100D) return null;
        return createEnchantItem(new RuneInfo(info.effect(), info.level(),
                Math.min(100D, info.successRate() + EnchantManager.LUCKY_GEM_BONUS_PERCENT)));
    }

    public ApplyOutcome apply(ItemStack target, ItemStack enchantItem, int ignoredLegacyGemCount) {
        RuneInfo info = info(enchantItem);
        if (info == null || target == null || target.getType().isAir()) return new ApplyOutcome(ApplyResult.INVALID, info, false);
        if (!compatible(info.effect(), target.getType())) return new ApplyOutcome(ApplyResult.INCOMPATIBLE, info, false);
        Map<Effect, Integer> current = applied(target);
        if (current.containsKey(info.effect())) return new ApplyOutcome(ApplyResult.ALREADY_APPLIED, info, false);
        double chance = info.successRate();
        if (ThreadLocalRandom.current().nextDouble(100D) >= chance) return new ApplyOutcome(ApplyResult.FAILURE, info, true);
        current.put(info.effect(), info.level());
        writeApplied(target, current);
        return new ApplyOutcome(ApplyResult.SUCCESS, info, true);
    }

    public double equippedValue(Player player, Effect effect) {
        if (player == null || effect == null) return 0D;
        double total = 0D;
        for (ItemStack item : equipment(player)) {
            Integer level = applied(item).get(effect);
            if (level != null) total += effect.valueAt(level);
        }
        return total;
    }

    public double detonationPercent(Player player) { return equippedValue(player, Effect.CORRUPTED_DETONATION); }

    private static List<ItemStack> equipment(Player player) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack item : player.getInventory().getArmorContents()) if (item != null && !item.getType().isAir()) result.add(item);
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (main != null && !main.getType().isAir()) result.add(main);
        if (off != null && !off.getType().isAir()) result.add(off);
        return result;
    }

    private static boolean compatible(Effect effect, Material material) {
        if (material == null || material.isAir() || material.getMaxDurability() <= 0) return false;
        if (!effect.restrictedEquipment()) return true;
        String type = material.name();
        return type.endsWith("_CHESTPLATE") || type.endsWith("_SWORD") || type.endsWith("_AXE");
    }

    private Map<Effect, Integer> applied(ItemStack item) {
        Map<Effect, Integer> result = new EnumMap<>(Effect.class);
        if (item == null || !item.hasItemMeta()) return result;
        String raw = item.getItemMeta().getPersistentDataContainer().get(appliedKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return result;
        for (String entry : raw.split(",")) {
            String[] split = entry.split(":", 2);
            if (split.length != 2) continue;
            Effect effect = Effect.byId(split[0]);
            try {
                int level = Integer.parseInt(split[1]);
                if (effect != null && level >= 1 && level <= 20) result.put(effect, level);
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }

    private void writeApplied(ItemStack item, Map<Effect, Integer> values) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        Map<Effect, Integer> previous = applied(item);
        List<Component> lore = stripLegacyArenaLore(meta.lore() == null ? List.of() : meta.lore());
        removeGeneratedArenaLines(lore, previous);
        String raw = values.entrySet().stream().map(entry -> entry.getKey().id() + ":" + entry.getValue())
                .reduce((left, right) -> left + "," + right).orElse("");
        meta.getPersistentDataContainer().set(appliedKey, PersistentDataType.STRING, raw);
        for (Map.Entry<Effect, Integer> entry : values.entrySet()) {
            lore.add(RuneFormatting.appliedLine(entry.getKey().displayName(), entry.getValue(), NamedTextColor.BLUE,
                    entry.getValue() == 20));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    private static List<Component> stripLegacyArenaLore(List<Component> source) {
        List<Component> lore = new ArrayList<>(source);
        int header = -1;
        for (int index = 0; index < lore.size(); index++) {
            if ("Arena Runes".equals(PlainTextComponentSerializer.plainText().serialize(lore.get(index)))) {
                header = index;
            }
        }
        if (header < 0) {
            return lore;
        }
        int start = header;
        if (start > 0 && PlainTextComponentSerializer.plainText().serialize(lore.get(start - 1)).isEmpty()) {
            start--;
        }
        return new ArrayList<>(lore.subList(0, start));
    }

    private static void removeGeneratedArenaLines(List<Component> lore, Map<Effect, Integer> values) {
        for (Map.Entry<Effect, Integer> entry : values.entrySet()) {
            String expected = entry.getKey().displayName() + " " + roman(entry.getValue());
            String standardized = RuneFormatting.smallCaps(entry.getKey().displayName()) + " "
                    + RuneFormatting.roman(entry.getValue());
            for (int index = lore.size() - 1; index >= 0; index--) {
                String plain = PlainTextComponentSerializer.plainText().serialize(lore.get(index));
                if (expected.equals(plain) || standardized.equals(plain)) {
                    lore.remove(index);
                    break;
                }
            }
        }
    }

    public boolean requiresEconomy() {
        return currency == Currency.MONEY;
    }

    public int affordableQuantity(Player player, Purchase purchase) {
        if (player == null) {
            return 0;
        }
        if (currency == Currency.XP_LEVELS) {
            int unitCost = levelCost(purchase);
            return unitCost == 0 ? Integer.MAX_VALUE : player.getLevel() / unitCost;
        }
        if (!EconomyHook.isAvailable()) {
            return 0;
        }
        double price = price(purchase);
        return price <= 0D ? Integer.MAX_VALUE
                : (int) Math.floor(EconomyHook.getEconomy().getBalance(player) / price);
    }

    public boolean charge(Player player, Purchase purchase, int quantity) {
        if (player == null || quantity < 1) {
            return false;
        }
        if (currency == Currency.XP_LEVELS) {
            long total = (long) levelCost(purchase) * quantity;
            if (total > Integer.MAX_VALUE) {
                return false;
            }
            int levels = (int) total;
            if (player.getLevel() < levels) return false;
            player.setLevel(player.getLevel() - levels);
            return true;
        }
        if (!EconomyHook.isAvailable()) return false;
        EconomyResponse response = EconomyHook.getEconomy().withdrawPlayer(player, price(purchase) * quantity);
        return response != null && response.transactionSuccess();
    }

    private double price(Purchase purchase) {
        return runePrice;
    }

    private int levelCost(Purchase purchase) {
        return (int) Math.ceil(price(purchase));
    }

    public String priceText(Purchase purchase) {
        return priceText(purchase, 1);
    }

    public String priceText(Purchase purchase, int quantity) {
        long count = Math.max(0, quantity);
        return currency == Currency.XP_LEVELS ? (levelCost(purchase) * count) + " XP levels"
                : EconomyHook.format(price(purchase) * count);
    }

    private static ItemStack star(Color first, Color second, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.FIREWORK_STAR);
        FireworkEffectMeta meta = (FireworkEffectMeta) item.getItemMeta();
        meta.setEffect(FireworkEffect.builder().withColor(first, second).build());
        meta.displayName(name); meta.lore(lore); item.setItemMeta(meta); return item;
    }
    private static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name); meta.lore(lore); item.setItemMeta(meta); return item;
    }
    private static String roman(int value) { return switch (value) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; case 6 -> "VI"; case 7 -> "VII"; case 8 -> "VIII"; case 9 -> "IX"; case 10 -> "X"; case 11 -> "XI"; case 12 -> "XII"; case 13 -> "XIII"; case 14 -> "XIV"; case 15 -> "XV"; case 16 -> "XVI"; case 17 -> "XVII"; case 18 -> "XVIII"; case 19 -> "XIX"; default -> "XX"; }; }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
