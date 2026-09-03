package me.hcfcore.core.kit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

public final class Kit {

    private final String name;
    private final String permission;
    private final int cooldownSeconds;
    private final ItemStack[] armor;
    private final ItemStack[] contents;
    private final Cost cost;
    private final List<Effect> effects;
    private final String icon;
    private final String purpose;

    public Kit(String name, String permission, int cooldownSeconds, ItemStack[] armor, ItemStack[] contents) {
        this(name, permission, cooldownSeconds, armor, contents, Cost.NONE, List.of());
    }

    public Kit(String name, String permission, int cooldownSeconds, ItemStack[] armor, ItemStack[] contents, Cost cost) {
        this(name, permission, cooldownSeconds, armor, contents, cost, List.of());
    }

    public Kit(String name, String permission, int cooldownSeconds, ItemStack[] armor, ItemStack[] contents,
                Cost cost, List<Effect> effects) {
        this(name, permission, cooldownSeconds, armor, contents, cost, effects, null, null);
    }

    public Kit(String name, String permission, int cooldownSeconds, ItemStack[] armor, ItemStack[] contents,
                Cost cost, List<Effect> effects, String icon, String purpose) {
        this.name = name;
        this.permission = permission;
        this.cooldownSeconds = cooldownSeconds;
        this.armor = cloneArray(armor);
        this.contents = cloneArray(contents);
        this.cost = cost;
        this.effects = List.copyOf(effects);
        this.icon = icon;
        this.purpose = purpose;
    }

    public String getName() {
        return name;
    }

    public String getPermission() {
        return permission;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public Cost getCost() {
        return cost;
    }

    public ItemStack[] getArmor() {
        return cloneArray(armor);
    }

    public ItemStack[] getContents() {
        return cloneArray(contents);
    }

    public List<Effect> getEffects() {
        return effects;
    }

    /**
     * Optional override for the /kits GUI icon, since the default (first
     * non-air armor piece, else first content item) doesn't always pick
     * the most recognizable item for a kit -- e.g. a class kit whose
     * helmet happens to be plain but whose leggings are the distinctive
     * piece. Null means "use the default".
     */
    public String getIcon() {
        return icon;
    }

    /**
     * Optional short role/flavor blurb for the /kits GUI lore (e.g.
     * "Faction support -- share buffs with your team"). Null shows
     * nothing.
     */
    public String getPurpose() {
        return purpose;
    }

    private static ItemStack[] cloneArray(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }
        return copy;
    }

    /**
     * A kit's price. Either or both of the money/item components can be
     * set; NONE means free. itemType/itemAmount travel together -- a
     * non-null type with a zero amount is treated as no item cost.
     */
    public record Cost(double money, Material itemType, int itemAmount) {
        public static final Cost NONE = new Cost(0.0, null, 0);

        public boolean hasMoneyCost() {
            return money > 0;
        }

        public boolean hasItemCost() {
            return itemType != null && itemAmount > 0;
        }
    }

    /**
     * A passive "class" buff granted on claim. amplifier is 0-indexed, so
     * amplifier 0 is the effect's level I.
     */
    public record Effect(PotionEffectType type, int amplifier) {
    }
}

