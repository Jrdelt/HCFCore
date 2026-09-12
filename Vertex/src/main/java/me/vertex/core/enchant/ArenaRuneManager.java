package me.vertex.core.enchant;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.zone.ZoneManager;
import me.vertex.core.zone.ZoneType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
    public enum Purchase { RUNE, LUCKY_GEM }
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
    public record ApplyOutcome(ApplyResult result, RuneInfo info, boolean consumeEnchant, boolean consumeGems) { }

    private final Plugin plugin;
    private final ZoneManager zones;
    private final NamespacedKey baseRuneKey;
    private final NamespacedKey enchantKey;
    private final NamespacedKey successKey;
    private final NamespacedKey luckyGemKey;
    private final NamespacedKey appliedKey;
    private Currency currency = Currency.XP_LEVELS;
    private double runePrice = 100D;
    private double luckyGemPrice = 10D;
    private double gemBonus = 3.5D;
    private double detonationRadius = 5D;

    public ArenaRuneManager(Plugin plugin, ZoneManager zones) {
        this.plugin = plugin;
        this.zones = zones;
        baseRuneKey = new NamespacedKey(plugin, "arena_rune");
        enchantKey = new NamespacedKey(plugin, "arena_enchant");
        successKey = new NamespacedKey(plugin, "arena_enchant_success");
        luckyGemKey = new NamespacedKey(plugin, "arena_lucky_gem");
        appliedKey = new NamespacedKey(plugin, "arena_enchants");
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "arena-runes.yml");
        if (!file.exists()) plugin.saveResource("arena-runes.yml", false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        try { currency = Currency.valueOf(config.getString("shop.currency", "XP_LEVELS").trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { currency = Currency.XP_LEVELS; }
        runePrice = Math.max(0D, config.getDouble("shop.arena-rune-price", 100D));
        luckyGemPrice = Math.max(0D, config.getDouble("shop.lucky-gem-price", 10D));
        gemBonus = Math.max(0D, config.getDouble("lucky-gem.success-bonus-percent", 3.5D));
        detonationRadius = Math.max(1D, config.getDouble("corrupted-detonation.radius", 5D));
    }

    public Plugin plugin() { return plugin; }
    public double detonationRadius() { return detonationRadius; }
    public boolean inArena(Player player) { return player != null && (zones.isIn(player, ZoneType.HAVEN) || zones.isIn(player, ZoneType.RIFTLANDS)); }
    public boolean inArena(org.bukkit.Location location) { return location != null && zones.isInAnyZone(location); }

    public ItemStack createShopRuneIcon() {
        ItemStack item = createRune();
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        lore.add(Component.text("Price: " + priceText(Purchase.RUNE), NamedTextColor.YELLOW));
        lore.add(Component.text("Click to purchase", NamedTextColor.GREEN));
        meta.lore(lore); item.setItemMeta(meta); return item;
    }

    public ItemStack createShopLuckyGemIcon() {
        ItemStack item = createLuckyGem();
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        lore.add(Component.text("Price: " + priceText(Purchase.LUCKY_GEM), NamedTextColor.YELLOW));
        lore.add(Component.text("Click to purchase", NamedTextColor.GREEN));
        meta.lore(lore); item.setItemMeta(meta); return item;
    }

    public ItemStack createRune() {
        ItemStack item = icon(Material.BLACK_CANDLE, Component.text("ᴍᴏʙ ᴀʀᴇɴᴀ ʀᴜɴᴇ", NamedTextColor.BLUE), List.of(
                Component.text("Right-click to reveal a random Arena Rune.", NamedTextColor.GRAY),
                Component.text("Levels I-XX. Stackable up to 64.", NamedTextColor.DARK_GRAY)));
        item.getItemMeta().getPersistentDataContainer();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(baseRuneKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createLuckyGem() {
        ItemStack item = icon(Material.GREEN_DYE, Component.text("ʟᴜᴄᴋʏ ɢᴇᴍ", NamedTextColor.GREEN), List.of(
                Component.text("Adds +" + trim(gemBonus) + "% application success.", NamedTextColor.GRAY),
                Component.text("Consumed only by a valid application attempt.", NamedTextColor.DARK_GRAY)));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(luckyGemKey, PersistentDataType.BYTE, (byte) 1);
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
        String chance = trim(info.successRate()) + "% success / " + trim(100D - info.successRate()) + "% fail";
        ItemStack item = star(Color.BLACK, Color.WHITE,
                Component.text(smallCaps(info.effect().displayName()) + " " + roman(info.level()), NamedTextColor.LIGHT_PURPLE), List.of(
                Component.text(info.effect().description() + ": +" + trim(info.effect().valueAt(info.level())) + "%", NamedTextColor.GRAY),
                Component.text(chance, NamedTextColor.YELLOW),
                Component.text("Active only in Haven and Riftlands.", NamedTextColor.DARK_PURPLE),
                Component.text("Right-click to open the application menu.", NamedTextColor.DARK_GRAY)));
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(enchantKey, PersistentDataType.STRING, info.effect().id() + ":" + info.level());
        pdc.set(successKey, PersistentDataType.DOUBLE, info.successRate());
        item.setItemMeta(meta);
        return item;
    }

    public boolean isRune(ItemStack item) { return tagged(item, baseRuneKey); }
    public boolean isLuckyGem(ItemStack item) { return tagged(item, luckyGemKey); }
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
        return createEnchantItem(new RuneInfo(info.effect(), info.level(), Math.min(100D, info.successRate() + gemBonus)));
    }

    public ApplyOutcome apply(ItemStack target, ItemStack enchantItem, int gems) {
        RuneInfo info = info(enchantItem);
        if (info == null || target == null || target.getType().isAir()) return new ApplyOutcome(ApplyResult.INVALID, info, false, false);
        if (!compatible(info.effect(), target.getType())) return new ApplyOutcome(ApplyResult.INCOMPATIBLE, info, false, false);
        Map<Effect, Integer> current = applied(target);
        if (current.containsKey(info.effect())) return new ApplyOutcome(ApplyResult.ALREADY_APPLIED, info, false, false);
        double chance = Math.min(100D, info.successRate() + Math.max(0, gems) * gemBonus);
        if (ThreadLocalRandom.current().nextDouble(100D) >= chance) return new ApplyOutcome(ApplyResult.FAILURE, info, true, gems > 0);
        current.put(info.effect(), info.level());
        writeApplied(target, current);
        return new ApplyOutcome(ApplyResult.SUCCESS, info, true, gems > 0);
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
        String raw = values.entrySet().stream().map(entry -> entry.getKey().id() + ":" + entry.getValue())
                .reduce((left, right) -> left + "," + right).orElse("");
        meta.getPersistentDataContainer().set(appliedKey, PersistentDataType.STRING, raw);
        List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        lore.add(Component.empty());
        lore.add(Component.text("Arena Runes", NamedTextColor.DARK_PURPLE));
        for (Map.Entry<Effect, Integer> entry : values.entrySet()) {
            lore.add(Component.text(entry.getKey().displayName() + " " + roman(entry.getValue())
                    + " +" + trim(entry.getKey().valueAt(entry.getValue())) + "%", NamedTextColor.LIGHT_PURPLE));
        }
        lore.add(Component.text("Active only in Haven and Riftlands.", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
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
        return purchase == Purchase.RUNE ? runePrice : luckyGemPrice;
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
    private static String smallCaps(String text) {
        return text.toLowerCase(Locale.ROOT).replace("a", "ᴀ").replace("b", "ʙ").replace("c", "ᴄ")
                .replace("d", "ᴅ").replace("e", "ᴇ").replace("f", "ꜰ").replace("g", "ɢ")
                .replace("h", "ʜ").replace("i", "ɪ").replace("j", "ᴊ").replace("k", "ᴋ")
                .replace("l", "ʟ").replace("m", "ᴍ").replace("n", "ɴ").replace("o", "ᴏ")
                .replace("p", "ᴘ").replace("q", "ǫ").replace("r", "ʀ").replace("s", "ꜱ")
                .replace("t", "ᴛ").replace("u", "ᴜ").replace("v", "ᴠ").replace("w", "ᴡ")
                .replace("x", "x").replace("y", "ʏ").replace("z", "ᴢ");
    }
    private static String roman(int value) { return switch (value) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; case 6 -> "VI"; case 7 -> "VII"; case 8 -> "VIII"; case 9 -> "IX"; case 10 -> "X"; case 11 -> "XI"; case 12 -> "XII"; case 13 -> "XIII"; case 14 -> "XIV"; case 15 -> "XV"; case 16 -> "XVI"; case 17 -> "XVII"; case 18 -> "XVIII"; case 19 -> "XIX"; default -> "XX"; }; }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static String trim(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value); }
}
