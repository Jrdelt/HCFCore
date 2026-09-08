package me.vertex.core.shop;

import org.bukkit.Material;

import java.util.List;

/** One category shown in the {@code /shop} picker (max 9 -- it's a single row), loaded from {@code shop.yml}. */
record ShopCategory(String id, String displayName, Material icon, List<ShopEntry> entries) {
}
