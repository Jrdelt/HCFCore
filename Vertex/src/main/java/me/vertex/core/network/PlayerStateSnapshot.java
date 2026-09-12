package me.vertex.core.network;

import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/** Complete handoff state captured and restored only on the primary thread. */
public record PlayerStateSnapshot(ItemStack[] storage, ItemStack[] armor, ItemStack offhand,
                                  ItemStack[] enderChest, double health, int food, float saturation,
                                  float exhaustion, int level, float exp, int totalExperience,
                                  GameMode gameMode, boolean flying, boolean allowFlight,
                                  int heldSlot, double absorption, int fireTicks, int remainingAir,
                                  float fallDistance, List<PotionData> potionEffects) {
    public static PlayerStateSnapshot capture(Player player) {
        return new PlayerStateSnapshot(clone(player.getInventory().getStorageContents()),
                clone(player.getInventory().getArmorContents()),
                player.getInventory().getItemInOffHand().clone(), clone(player.getEnderChest().getContents()),
                player.getHealth(), player.getFoodLevel(), player.getSaturation(), player.getExhaustion(),
                player.getLevel(), player.getExp(), player.getTotalExperience(), player.getGameMode(),
                player.isFlying(), player.getAllowFlight(), player.getInventory().getHeldItemSlot(),
                player.getAbsorptionAmount(), player.getFireTicks(), player.getRemainingAir(),
                player.getFallDistance(), player.getActivePotionEffects().stream().map(PotionData::from).toList());
    }

    public void apply(Player player) {
        player.getInventory().setStorageContents(clone(storage));
        player.getInventory().setArmorContents(clone(armor));
        player.getInventory().setItemInOffHand(offhand == null ? null : offhand.clone());
        player.getEnderChest().setContents(clone(enderChest));
        double maxHealth = player.getAttribute(Attribute.MAX_HEALTH) == null ? 20D
                : player.getAttribute(Attribute.MAX_HEALTH).getValue();
        player.setHealth(Math.max(0.1D, Math.min(maxHealth, health)));
        player.setFoodLevel(Math.max(0, Math.min(20, food)));
        player.setSaturation(Math.max(0F, Math.min(20F, saturation)));
        player.setExhaustion(Math.max(0F, exhaustion));
        player.setLevel(Math.max(0, level));
        player.setExp(Math.max(0F, Math.min(0.999999F, exp)));
        player.setTotalExperience(Math.max(0, totalExperience));
        player.setGameMode(gameMode == null ? GameMode.SURVIVAL : gameMode);
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, heldSlot)));
        player.setAbsorptionAmount(Math.max(0D, absorption));
        player.setFireTicks(Math.max(0, fireTicks));
        player.setRemainingAir(Math.max(0, Math.min(player.getMaximumAir(), remainingAir)));
        player.setFallDistance(Math.max(0F, fallDistance));
        for (PotionEffect existing : player.getActivePotionEffects()) {
            player.removePotionEffect(existing.getType());
        }
        if (potionEffects != null) {
            for (PotionData saved : potionEffects) {
                PotionEffect restored = saved.toEffect();
                if (restored != null) player.addPotionEffect(restored, true);
            }
        }
        player.setAllowFlight(allowFlight);
        player.setFlying(allowFlight && flying);
        player.updateInventory();
    }

    public byte[] encode() throws IOException {
        byte[] storageBytes = ItemStack.serializeItemsAsBytes(storage);
        byte[] armorBytes = ItemStack.serializeItemsAsBytes(armor);
        byte[] offhandBytes = ItemStack.serializeItemsAsBytes(new ItemStack[]{offhand});
        byte[] enderBytes = ItemStack.serializeItemsAsBytes(enderChest);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(2);
            write(out, storageBytes);
            write(out, armorBytes);
            write(out, offhandBytes);
            write(out, enderBytes);
            out.writeDouble(health);
            out.writeInt(food);
            out.writeFloat(saturation);
            out.writeFloat(exhaustion);
            out.writeInt(level);
            out.writeFloat(exp);
            out.writeInt(totalExperience);
            out.writeUTF((gameMode == null ? GameMode.SURVIVAL : gameMode).name());
            out.writeBoolean(flying);
            out.writeBoolean(allowFlight);
            out.writeInt(heldSlot);
            out.writeDouble(absorption);
            out.writeInt(fireTicks);
            out.writeInt(remainingAir);
            out.writeFloat(fallDistance);
            List<PotionData> effects = potionEffects == null ? List.of() : potionEffects;
            out.writeInt(effects.size());
            for (PotionData effect : effects) {
                out.writeUTF(effect.key());
                out.writeInt(effect.duration());
                out.writeInt(effect.amplifier());
                out.writeBoolean(effect.ambient());
                out.writeBoolean(effect.particles());
                out.writeBoolean(effect.icon());
            }
            return bytes.toByteArray();
        }
    }

    public static PlayerStateSnapshot decode(byte[] raw) throws IOException {
        if (raw == null) throw new IOException("Missing player snapshot");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            int version = in.readInt();
            if (version != 1 && version != 2) {
                throw new IOException("Unsupported player snapshot version " + version);
            }
            ItemStack[] storage = ItemStack.deserializeItemsFromBytes(read(in));
            ItemStack[] armor = ItemStack.deserializeItemsFromBytes(read(in));
            ItemStack[] offhand = ItemStack.deserializeItemsFromBytes(read(in));
            ItemStack[] ender = ItemStack.deserializeItemsFromBytes(read(in));
            double health = in.readDouble();
            int food = in.readInt();
            float saturation = in.readFloat();
            float exhaustion = in.readFloat();
            int level = in.readInt();
            float exp = in.readFloat();
            int totalExperience = in.readInt();
            GameMode gameMode = GameMode.valueOf(in.readUTF());
            boolean flying = in.readBoolean();
            boolean allowFlight = in.readBoolean();
            int heldSlot = 0;
            double absorption = 0D;
            int fireTicks = 0;
            int remainingAir = 300;
            float fallDistance = 0F;
            List<PotionData> effects = List.of();
            if (version >= 2) {
                heldSlot = in.readInt();
                absorption = in.readDouble();
                fireTicks = in.readInt();
                remainingAir = in.readInt();
                fallDistance = in.readFloat();
                int count = in.readInt();
                if (count < 0 || count > 1_024) throw new IOException("Invalid potion effect count");
                java.util.ArrayList<PotionData> decoded = new java.util.ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    decoded.add(new PotionData(in.readUTF(), in.readInt(), in.readInt(),
                            in.readBoolean(), in.readBoolean(), in.readBoolean()));
                }
                effects = List.copyOf(decoded);
            }
            return new PlayerStateSnapshot(storage, armor, offhand.length == 0 ? null : offhand[0], ender,
                    health, food, saturation, exhaustion, level, exp, totalExperience, gameMode,
                    flying, allowFlight, heldSlot, absorption, fireTicks, remainingAir, fallDistance, effects);
        } catch (RuntimeException error) {
            throw new IOException("Corrupt player state snapshot", error);
        }
    }

    private static void write(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] read(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 32 * 1024 * 1024) {
            throw new IOException("Invalid snapshot field length");
        }
        byte[] value = in.readNBytes(length);
        if (value.length != length) throw new IOException("Truncated player snapshot field");
        return value;
    }

    private static ItemStack[] clone(ItemStack[] source) {
        if (source == null) return new ItemStack[0];
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }
        return copy;
    }

    public record PotionData(String key, int duration, int amplifier, boolean ambient,
                             boolean particles, boolean icon) {
        static PotionData from(PotionEffect effect) {
            return new PotionData(effect.getType().getKey().toString(), effect.getDuration(),
                    effect.getAmplifier(), effect.isAmbient(), effect.hasParticles(), effect.hasIcon());
        }

        PotionEffect toEffect() {
            NamespacedKey parsed = NamespacedKey.fromString(key);
            if (parsed == null) return null;
            PotionEffectType type = PotionEffectType.getByKey(parsed);
            return type == null ? null : new PotionEffect(type, Math.max(0, duration),
                    Math.max(0, amplifier), ambient, particles, icon);
        }
    }
}
