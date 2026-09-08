package me.vertex.core.shop;

import org.bukkit.Material;

/**
 * One tradeable item, loaded from a category's {@code items} section in
 * {@code shop.yml}. {@code dynamicPricing} false pins the price flat at
 * {@code basePrice} regardless of trade volume -- used for materials whose
 * cost needs to stay predictable (e.g. bulk-purchased by an automated
 * system like a sand bot) rather than spike with demand.
 */
record ShopEntry(Material material, double basePrice, boolean dynamicPricing) {
}
