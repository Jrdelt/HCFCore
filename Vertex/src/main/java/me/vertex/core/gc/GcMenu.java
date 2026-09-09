package me.vertex.core.gc;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuItemTemplate;
import me.vertex.core.menu.MenuLayout;
import me.vertex.core.menu.MenuPlaceholders;
import me.vertex.core.menu.MenuRegistry;
import me.vertex.core.util.Numbers;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * The player-facing GC wallet: balance display plus Deposit/Withdraw/
 * Redeem/Logs buttons. Copies {@code FactionBankMenu}'s exact shape --
 * PDC-tagged buttons read back in {@link #onClick}, the whole inventory
 * reopened after a change -- with its layout driven by {@code gui/gc.yml}
 * through {@link MenuRegistry}, the same config-driven shape {@code
 * gui/events.yml} already uses.
 *
 * <p>Deposit converts Vault money into GC; Withdraw converts GC back into
 * Vault money. Both amounts are typed on the shared physical sign (see
 * {@link GcSignPrompt}) rather than in chat or an anvil.
 */
public final class GcMenu implements Listener {

    public static final String MENU_ID = "gc";

    private final Plugin plugin;
    private final GcManager manager;
    private final GcSignPrompt signPrompt;
    private final GcInteropHook interopHook;
    private final Messages messages;
    private final MenuRegistry menuRegistry;
    private final NamespacedKey actionKey;

    public GcMenu(Plugin plugin, GcManager manager, GcSignPrompt signPrompt, GcInteropHook interopHook,
            Messages messages, MenuRegistry menuRegistry) {
        this.plugin = plugin;
        this.manager = manager;
        this.signPrompt = signPrompt;
        this.interopHook = interopHook;
        this.messages = messages;
        this.menuRegistry = menuRegistry;
        this.actionKey = new NamespacedKey(plugin, "gc_menu_action");
    }

    public void open(Player player) {
        MenuLayout layout = menuRegistry.layout(MENU_ID);
        MenuPlaceholders placeholders = MenuPlaceholders.of()
                .put("balance", Numbers.formatFull(manager.balance(player.getUniqueId())));
        Holder holder = new Holder();
        Inventory inventory = layout.createInventory(holder, placeholders);
        holder.setInventory(inventory);
        layout.place(inventory, "balance", placeholders);
        placeAction(inventory, layout, placeholders, "deposit");
        placeAction(inventory, layout, placeholders, "withdraw");
        placeAction(inventory, layout, placeholders, "redeem");
        placeAction(inventory, layout, placeholders, "logs");
        player.openInventory(inventory);
    }

    private void placeAction(Inventory inventory, MenuLayout layout, MenuPlaceholders placeholders, String id) {
        MenuItemTemplate template = layout.item(id);
        if (template == null) {
            return;
        }
        ItemStack item = template.render(placeholders);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        for (int slot : template.slots()) {
            if (slot >= 0 && slot < inventory.getSize()) {
                inventory.setItem(slot, item);
            }
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
        if (!(event.getInventory().getHolder() instanceof Holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        String action = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) {
            return;
        }
        switch (action) {
            case "deposit" -> beginDeposit(player);
            case "withdraw" -> beginWithdraw(player);
            case "redeem" -> {
                player.closeInventory();
                player.sendMessage(messages.get(player, "gc.redeem-hint"));
            }
            case "logs" -> GcLogMenu.open(player, manager, messages, 0);
            default -> { }
        }
    }

    private void beginDeposit(Player player) {
        player.closeInventory();
        GcSignPrompt.RequestResult result = signPrompt.request(player, GcSignPrompt.Operation.DEPOSIT,
                manager.signPromptTimeoutSeconds(), amount -> handleDeposit(player, amount), () -> reopenIfOnline(player));
        announceRequestResult(player, result);
    }

    private void beginWithdraw(Player player) {
        player.closeInventory();
        GcSignPrompt.RequestResult result = signPrompt.request(player, GcSignPrompt.Operation.WITHDRAW,
                manager.signPromptTimeoutSeconds(), amount -> handleWithdraw(player, amount), () -> reopenIfOnline(player));
        announceRequestResult(player, result);
    }

    private void announceRequestResult(Player player, GcSignPrompt.RequestResult result) {
        switch (result) {
            case NOT_CONFIGURED -> player.sendMessage(messages.get(player, "gc.sign-not-configured"));
            case BUSY -> player.sendMessage(messages.get(player, "gc.sign-busy"));
            case OK -> player.sendMessage(messages.get(player, "gc.sign-prompt-opened"));
        }
    }

    private void reopenIfOnline(Player player) {
        if (player.isOnline()) {
            open(player);
        }
    }

    /** Money leaves Vault first, then credits GC -- compensated back into Vault if the GC credit fails to persist. */
    private void handleDeposit(Player player, long amount) {
        if (amount < manager.minDeposit() || amount > manager.maxDeposit()) {
            player.sendMessage(messages.get(player, "gc.amount-out-of-range"));
            reopenIfOnline(player);
            return;
        }
        if (!EconomyHook.isAvailable()) {
            player.sendMessage(messages.get(player, "gc.no-economy"));
            reopenIfOnline(player);
            return;
        }
        Economy economy = EconomyHook.getEconomy();
        if (!economy.has(player, amount)) {
            player.sendMessage(messages.get(player, "gc.not-enough-money"));
            reopenIfOnline(player);
            return;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        if (response == null || !response.transactionSuccess()) {
            player.sendMessage(messages.get(player, "gc.not-enough-money"));
            reopenIfOnline(player);
            return;
        }
        UUID uuid = player.getUniqueId();
        manager.credit(uuid, uuid, GcAction.DEPOSIT, amount, null, () -> {
            // The GC credit never made it to the database -- the withdrawn
            // money must not simply vanish.
            economy.depositPlayer(player, amount);
            if (player.isOnline()) {
                player.sendMessage(messages.get(player, "gc.transaction-failed"));
            }
        });
        player.sendMessage(messages.get(player, "gc.deposit-success", "amount", Numbers.formatFull(amount)));
        interopHook.onGcCredited(player, amount, "deposit");
        reopenIfOnline(player);
    }

    /** GC is debited first (fails outright if insufficient); only then is Vault credited. */
    private void handleWithdraw(Player player, long amount) {
        if (amount < manager.minWithdraw() || amount > manager.maxWithdraw()) {
            player.sendMessage(messages.get(player, "gc.amount-out-of-range"));
            reopenIfOnline(player);
            return;
        }
        if (!EconomyHook.isAvailable()) {
            player.sendMessage(messages.get(player, "gc.no-economy"));
            reopenIfOnline(player);
            return;
        }
        UUID uuid = player.getUniqueId();
        Economy economy = EconomyHook.getEconomy();
        boolean debited = manager.tryDebit(uuid, uuid, GcAction.WITHDRAW, amount, null, () -> {
            // The database never durably recorded the debit -- GcManager has
            // already reversed its own in-memory balance; nothing external
            // was touched yet at this point, so there is nothing more to undo.
            if (player.isOnline()) {
                player.sendMessage(messages.get(player, "gc.transaction-failed"));
            }
        });
        if (!debited) {
            player.sendMessage(messages.get(player, "gc.not-enough-gc"));
            reopenIfOnline(player);
            return;
        }
        EconomyResponse response = economy.depositPlayer(player, amount);
        if (response == null || !response.transactionSuccess()) {
            // The failed external payout must never leave a durable
            // withdrawal behind -- mirrors FactionBankMenu#withdrawMoney.
            manager.credit(uuid, uuid, GcAction.DEPOSIT, amount, "withdraw-compensation");
            player.sendMessage(messages.get(player, "gc.transaction-failed"));
            reopenIfOnline(player);
            return;
        }
        player.sendMessage(messages.get(player, "gc.withdraw-success", "amount", Numbers.formatFull(amount)));
        reopenIfOnline(player);
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }
}
