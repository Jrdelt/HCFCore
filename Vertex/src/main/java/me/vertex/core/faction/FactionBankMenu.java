package me.vertex.core.faction;

import dev.kitteh.factions.Faction;
import dev.kitteh.factions.command.ThirdPartyCommands;
import me.vertex.core.economy.EconomyHook;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import me.vertex.core.util.ChatAmountPrompt;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Six-row faction bank for money, experience, and FactionsUUID's TNT bank.
 * Paper's standard chest inventory API permits 9–54 slots only; a seventh
 * row (63 slots) is not a valid client inventory.
 *
 * <p>The deposit/withdraw amount is typed in chat via
 * {@link ChatAmountPrompt}, not an anvil GUI -- see that class's own
 * documentation for why the anvil approach was replaced after repeated
 * attempts to make its rename-text field reliable at click time.
 */
public final class FactionBankMenu implements Listener {
    private static final int SIZE = 54;
    // Keep the three bank sections in the upper half of the double chest.
    // These are each two chest rows (18 slots) above the prior layout.
    private static final int MONEY_SLOT = 11;
    private static final int EXPERIENCE_SLOT = 13;
    private static final int TNT_SLOT = 15;
    private static final int DEPOSIT_ROW = 22;
    private static final int WITHDRAW_ROW = 31;

    private final Plugin plugin;
    private final FactionBankManager manager;
    private final RallyManager rolePermissions;
    private final Messages messages;
    private final ChatAmountPrompt chatAmountPrompt;
    private final NamespacedKey resourceKey;
    private final NamespacedKey operationKey;

    public FactionBankMenu(Plugin plugin, FactionBankManager manager, RallyManager rolePermissions, Messages messages,
            ChatAmountPrompt chatAmountPrompt) {
        this.plugin = plugin;
        this.manager = manager;
        this.rolePermissions = rolePermissions;
        this.messages = messages;
        this.chatAmountPrompt = chatAmountPrompt;
        this.resourceKey = new NamespacedKey(plugin, "faction_bank_resource");
        this.operationKey = new NamespacedKey(plugin, "faction_bank_operation");
    }

    /** Registers /f bank early enough that Paper's client command tree recognizes it. */
    public static void registerFactionsSubcommand(Plugin plugin, Supplier<FactionBankMenu> menuSupplier) {
        ThirdPartyCommands.register(plugin, "bank", (manager, root, help) -> manager.command(root.literal("bank")
                .handler(context -> {
                    FactionBankMenu menu = menuSupplier.get();
                    if (menu != null && context.sender().sender() instanceof Player player) {
                        menu.open(player);
                    }
                })));
    }

    public void open(Player player) {
        Faction faction = FactionsHook.getFaction(player);
        if (faction == null) {
            player.sendMessage(messages.get(player, "factions.must-be-in-faction"));
            return;
        }

        Holder holder = new Holder(faction.id());
        Inventory inventory = Bukkit.createInventory(holder, SIZE, messages.get(player, "faction-bank.gui-title"));
        holder.inventory = inventory;
        ItemStack border = border();
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, border);
        }
        for (int slot : List.of(MONEY_SLOT, EXPERIENCE_SLOT, TNT_SLOT, DEPOSIT_ROW - 2, DEPOSIT_ROW,
                DEPOSIT_ROW + 2, WITHDRAW_ROW - 2, WITHDRAW_ROW, WITHDRAW_ROW + 2)) {
            inventory.setItem(slot, null);
        }

        inventory.setItem(MONEY_SLOT, balanceItem(player, faction, Resource.MONEY));
        inventory.setItem(EXPERIENCE_SLOT, balanceItem(player, faction, Resource.EXPERIENCE));
        inventory.setItem(TNT_SLOT, balanceItem(player, faction, Resource.TNT));
        inventory.setItem(DEPOSIT_ROW - 2, actionItem(player, Resource.MONEY, Operation.DEPOSIT));
        inventory.setItem(DEPOSIT_ROW, actionItem(player, Resource.EXPERIENCE, Operation.DEPOSIT));
        inventory.setItem(DEPOSIT_ROW + 2, actionItem(player, Resource.TNT, Operation.DEPOSIT));
        inventory.setItem(WITHDRAW_ROW - 2, actionItem(player, Resource.MONEY, Operation.WITHDRAW));
        inventory.setItem(WITHDRAW_ROW, actionItem(player, Resource.EXPERIENCE, Operation.WITHDRAW));
        inventory.setItem(WITHDRAW_ROW + 2, actionItem(player, Resource.TNT, Operation.WITHDRAW));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onFactionBankCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length != 2 || !isFactionCommand(parts[0]) || !parts[1].equalsIgnoreCase("bank")) {
            return;
        }
        event.setCancelled(true);
        open(event.getPlayer());
    }

    @EventHandler
    public void onFactionBankTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player) || !event.getBuffer().startsWith("/")) {
            return;
        }
        String[] parts = event.getBuffer().substring(1).split("\\s+", -1);
        if (parts.length != 2 || !isFactionCommand(parts[0])) {
            return;
        }
        String partial = parts[1].toLowerCase(Locale.ROOT);
        if (!"bank".startsWith(partial)) {
            return;
        }
        List<String> completions = new ArrayList<>(event.getCompletions());
        if (completions.stream().noneMatch("bank"::equalsIgnoreCase)) {
            completions.add("bank");
            event.setCompletions(completions);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Object holder = event.getInventory().getHolder();
        if (!(holder instanceof Holder bankHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        Faction faction = FactionsHook.getFaction(player);
        if (faction == null || faction.id() != bankHolder.factionId) {
            player.closeInventory();
            return;
        }
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String resourceValue = meta.getPersistentDataContainer().get(resourceKey, PersistentDataType.STRING);
        String operationValue = meta.getPersistentDataContainer().get(operationKey, PersistentDataType.STRING);
        if (resourceValue == null || operationValue == null) {
            return;
        }
        Resource resource = Resource.from(resourceValue);
        Operation operation = Operation.from(operationValue);
        if (resource == null || operation == null) {
            return;
        }
        String permission = operation == Operation.DEPOSIT ? "bank-deposit" : "bank-withdraw";
        if (!rolePermissions.canUse(player, permission)) {
            player.sendMessage(messages.get(player, "factions.role-permission-denied"));
            return;
        }
        promptForAmount(player, faction.id(), resource, operation);
    }

    private void promptForAmount(Player player, int factionId, Resource resource, Operation operation) {
        player.closeInventory();
        String promptKey = operation == Operation.DEPOSIT
                ? "faction-bank.amount-chat-prompt-deposit" : "faction-bank.amount-chat-prompt-withdraw";
        chatAmountPrompt.request(player,
                messages.get(player, promptKey, "resource", resource.label(messages, player)),
                amount -> apply(player, factionId, resource, operation, amount),
                () -> { });
    }

    private ItemStack balanceItem(Player player, Faction faction, Resource resource) {
        ItemStack item = new ItemStack(resource.material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.get(player, "faction-bank." + resource.key + "-name")));
        meta.lore(messages.getList(player, "faction-bank.balance-lore",
                "value", resource.value(this, faction), "max", resource.max(faction),
                "resource", resource.label(messages, player)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack actionItem(Player player, Resource resource, Operation operation) {
        ItemStack item = new ItemStack(operation == Operation.DEPOSIT ? Material.LIME_DYE : Material.RED_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.get(player, "faction-bank." + operation.key + "-name",
                "resource", resource.label(messages, player))));
        meta.lore(messages.getList(player, "faction-bank." + operation.key + "-lore",
                "resource", resource.label(messages, player)));
        meta.getPersistentDataContainer().set(resourceKey, PersistentDataType.STRING, resource.key);
        meta.getPersistentDataContainer().set(operationKey, PersistentDataType.STRING, operation.key);
        item.setItemMeta(meta);
        return item;
    }

    private void apply(Player player, int factionId, Resource resource, Operation operation, long amount) {
        if (amount > Integer.MAX_VALUE) {
            // Experience and TNT amounts get narrowed with Math.toIntExact()
            // below, which throws above this -- money itself would tolerate
            // a larger value, but the same cap applies to all three
            // uniformly rather than plumbing a resource-specific one through
            // the chat prompt.
            player.sendMessage(messages.get(player, "faction-bank.invalid-amount"));
            return;
        }
        Faction faction = FactionsHook.getFaction(player);
        if (faction == null || faction.id() != factionId) {
            player.closeInventory();
            return;
        }
        String permission = operation == Operation.DEPOSIT ? "bank-deposit" : "bank-withdraw";
        if (!rolePermissions.canUse(player, permission)) {
            player.sendMessage(messages.get(player, "factions.role-permission-denied"));
            return;
        }
        OperationResult result = success -> {
            if (!success || !player.isOnline()) {
                return;
            }
            player.sendMessage(messages.get(player, "faction-bank." + operation.key + "-success",
                    "amount", String.format("%,d", amount), "resource", resource.label(messages, player)));
            open(player);
        };
        if (operation == Operation.DEPOSIT) {
            deposit(player, faction, resource, amount, result);
        } else {
            withdraw(player, faction, resource, amount, result);
        }
    }

    private void deposit(Player player, Faction faction, Resource resource, long amount, OperationResult result) {
        switch (resource) {
            case MONEY -> depositMoney(player, faction.id(), amount, result);
            case EXPERIENCE -> {
                if (player.getTotalExperience() < amount) {
                    player.sendMessage(messages.get(player, "faction-bank.not-enough"));
                    result.complete(false);
                    return;
                }
                player.giveExp(-Math.toIntExact(amount));
                manager.depositExperience(faction.id(), amount).whenComplete((saved, error) -> onMain(() -> {
                    if (error != null || !Boolean.TRUE.equals(saved)) {
                        player.giveExp(Math.toIntExact(amount));
                        player.sendMessage(messages.get(player, "faction-bank.transaction-failed"));
                        result.complete(false);
                        return;
                    }
                    result.complete(true);
                }));
            }
            case TNT -> result.complete(depositTnt(player, faction, amount));
        }
    }

    private void withdraw(Player player, Faction faction, Resource resource, long amount, OperationResult result) {
        switch (resource) {
            case MONEY -> withdrawMoney(player, faction.id(), amount, result);
            case EXPERIENCE -> {
                manager.withdrawExperience(faction.id(), amount).whenComplete((saved, error) -> onMain(() -> {
                    if (error != null || !Boolean.TRUE.equals(saved)) {
                        player.sendMessage(messages.get(player, "faction-bank.not-enough"));
                        result.complete(false);
                        return;
                    }
                    player.giveExp(Math.toIntExact(amount));
                    result.complete(true);
                }));
            }
            case TNT -> result.complete(withdrawTnt(player, faction, amount));
        }
    }

    private void depositMoney(Player player, int factionId, long amount, OperationResult result) {
        if (!EconomyHook.isAvailable()) {
            player.sendMessage(messages.get(player, "faction-bank.no-economy"));
            result.complete(false);
            return;
        }
        Economy economy = EconomyHook.getEconomy();
        if (!economy.has(player, amount)) {
            player.sendMessage(messages.get(player, "faction-bank.not-enough"));
            result.complete(false);
            return;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        if (response == null || !response.transactionSuccess()) {
            player.sendMessage(messages.get(player, "faction-bank.transaction-failed"));
            result.complete(false);
            return;
        }
        manager.depositMoney(factionId, amount).whenComplete((saved, error) -> onMain(() -> {
            if (error != null || !Boolean.TRUE.equals(saved)) {
                economy.depositPlayer(player, amount);
                player.sendMessage(messages.get(player, "faction-bank.transaction-failed"));
                result.complete(false);
                return;
            }
            result.complete(true);
        }));
    }

    private void withdrawMoney(Player player, int factionId, long amount, OperationResult result) {
        if (!EconomyHook.isAvailable()) {
            player.sendMessage(messages.get(player, "faction-bank.no-economy"));
            result.complete(false);
            return;
        }
        manager.withdrawMoney(factionId, amount).whenComplete((saved, error) -> onMain(() -> {
            if (error != null || !Boolean.TRUE.equals(saved)) {
                player.sendMessage(messages.get(player, "faction-bank.not-enough"));
                result.complete(false);
                return;
            }
            EconomyResponse response = EconomyHook.getEconomy().depositPlayer(player, amount);
            if (response == null || !response.transactionSuccess()) {
                // The failed external payout must never leave a durable
                // withdrawal behind. The compensation is serialized after
                // the successful withdrawal for this faction.
                manager.depositMoney(factionId, amount);
                player.sendMessage(messages.get(player, "faction-bank.transaction-failed"));
                result.complete(false);
                return;
            }
            result.complete(true);
        }));
    }

    private void onMain(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private boolean depositTnt(Player player, Faction faction, long amount) {
        if (faction.tntBankMax() > 0 && (long) faction.tntBank() + amount > faction.tntBankMax()) {
            player.sendMessage(messages.get(player, "faction-bank.tnt-full", "max", String.format("%,d", faction.tntBankMax())));
            return false;
        }
        if (!removeItems(player, Material.TNT, amount)) {
            player.sendMessage(messages.get(player, "faction-bank.not-enough"));
            return false;
        }
        faction.tntBank(Math.toIntExact((long) faction.tntBank() + amount));
        return true;
    }

    private boolean withdrawTnt(Player player, Faction faction, long amount) {
        if (faction.tntBank() < amount) {
            player.sendMessage(messages.get(player, "faction-bank.not-enough"));
            return false;
        }
        faction.tntBank(Math.toIntExact((long) faction.tntBank() - amount));
        long remaining = amount;
        while (remaining > 0) {
            int stack = (int) Math.min(remaining, Material.TNT.getMaxStackSize());
            player.getInventory().addItem(new ItemStack(Material.TNT, stack)).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            remaining -= stack;
        }
        return true;
    }

    private boolean removeItems(Player player, Material material, long amount) {
        if (amount > Integer.MAX_VALUE || !player.getInventory().containsAtLeast(new ItemStack(material), (int) amount)) {
            return false;
        }
        player.getInventory().removeItem(new ItemStack(material, (int) amount));
        return true;
    }

    private ItemStack border() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private boolean isFactionCommand(String raw) {
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        int namespace = command.indexOf(':');
        if (namespace >= 0) {
            command = command.substring(namespace + 1);
        }
        String normalized = command.toLowerCase(Locale.ROOT);
        return plugin.getConfig().getStringList("factions.command-aliases").stream()
                .map(alias -> alias.toLowerCase(Locale.ROOT)).anyMatch(normalized::equals);
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private enum Resource {
        MONEY("money", Material.GOLD_BLOCK), EXPERIENCE("experience", Material.EXPERIENCE_BOTTLE), TNT("tnt", Material.TNT);
        private final String key;
        private final Material material;
        Resource(String key, Material material) { this.key = key; this.material = material; }
        static Resource from(String value) { for (Resource resource : values()) if (resource.key.equals(value)) return resource; return null; }
        String label(Messages messages, Player player) { return MessageFormatter.plain(messages.getRaw(player, "faction-bank." + key + "-name")); }
        String value(FactionBankMenu menu, Faction faction) {
            return switch (this) {
                case MONEY -> EconomyHook.format(menu.manager.money(faction.id()));
                case EXPERIENCE -> String.format("%,d", menu.manager.experience(faction.id()));
                case TNT -> String.format("%,d", faction.tntBank());
            };
        }
        String max(Faction faction) { return this == TNT && faction.tntBankMax() > 0 ? String.format("%,d", faction.tntBankMax()) : "∞"; }
    }

    private enum Operation {
        DEPOSIT("deposit"), WITHDRAW("withdraw");
        private final String key;
        Operation(String key) { this.key = key; }
        static Operation from(String value) { return "deposit".equals(value) ? DEPOSIT : "withdraw".equals(value) ? WITHDRAW : null; }
    }

    @FunctionalInterface
    private interface OperationResult {
        void complete(boolean success);
    }

    private static final class Holder implements InventoryHolder {
        private final int factionId;
        private Inventory inventory;
        private Holder(int factionId) { this.factionId = factionId; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
