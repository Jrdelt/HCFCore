package me.vertex.core.faction;

import dev.kitteh.factions.Faction;
import dev.kitteh.factions.permissible.PermState;
import dev.kitteh.factions.permissible.PermSelector;
import dev.kitteh.factions.permissible.PermissibleActions;
import dev.kitteh.factions.permissible.Role;
import dev.kitteh.factions.permissible.selector.RoleSingleSelector;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
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

/** Edits a faction's real FactionsUUID role matrix, plus Vertex's rally actions. */
public final class RallyPermissionMenu implements Listener {
    private static final List<String> ROLES = List.of("mod", "member", "recruit");
    private static final String RALLY_SET = "vertex:rally-set";
    private static final String RALLY_CLEAR = "vertex:rally-clear";
    private static final List<CustomAction> CUSTOM_ACTIONS = List.of(
            new CustomAction(RALLY_SET, "Set Rally"),
            new CustomAction(RALLY_CLEAR, "Clear Rally"),
            new CustomAction("vertex:spawner-add", "Add Spawners"),
            new CustomAction("vertex:spawner-remove", "Remove Spawners"),
            new CustomAction("vertex:collector-open", "Open Collectors"),
            new CustomAction("vertex:collector-break", "Break Collectors"),
            new CustomAction("vertex:bank-deposit", "Deposit Bank Resources"),
            new CustomAction("vertex:bank-withdraw", "Withdraw Bank Resources"));
    private final Plugin plugin;
    private final RallyManager manager;
    private final Messages messages;
    private final NamespacedKey roleKey;
    private final NamespacedKey actionKey;

    public RallyPermissionMenu(Plugin plugin, RallyManager manager, Messages messages) {
        this.plugin = plugin; this.manager = manager; this.messages = messages;
        this.roleKey = new NamespacedKey(plugin, "faction_perms_role");
        this.actionKey = new NamespacedKey(plugin, "faction_perms_action");
    }

    public void open(Player player) {
        Faction faction = FactionsHook.getFaction(player);
        if (faction != null && FactionsHook.isLeader(player)) open(player, faction, "mod");
    }

    /** FactionsUUID owns /f, so route only its permissions aliases before that command executes. */
    @EventHandler
    public void onFactionPermissionsCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().toLowerCase(Locale.ROOT).trim().split("\\s+");
        if (parts.length != 2 || !isFactionCommand(parts[0])
                || !(parts[1].equals("permissions") || parts[1].equals("perms"))) return;
        Player player = event.getPlayer();
        if (FactionsHook.getFaction(player) == null) return;
        event.setCancelled(true);
        if (!FactionsHook.isLeader(player)) {
            player.sendMessage(messages.get(player, "factions.permissions-leader-only"));
            return;
        }
        open(player);
    }

    /** Adds the routed permission subcommands to FactionsUUID's normal /f completion list. */
    @EventHandler
    public void onFactionTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player)) {
            return;
        }
        String buffer = event.getBuffer();
        if (!buffer.startsWith("/")) {
            return;
        }
        String[] parts = buffer.substring(1).split("\\s+", -1);
        if (parts.length != 2 || !isFactionCommand(parts[0])) {
            return;
        }
        String partial = parts[1].toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>(event.getCompletions());
        for (String value : List.of("perms", "permissions")) {
            if (value.startsWith(partial) && completions.stream().noneMatch(value::equalsIgnoreCase)) {
                completions.add(value);
            }
        }
        event.setCompletions(completions);
    }

    private void open(Player player, Faction faction, String selectedRole) {
        Holder holder = new Holder(faction.id(), selectedRole);
        Inventory inventory = Bukkit.createInventory(holder, 54, MessageFormatter.deserialize(smallCaps(
                plugin.getConfig().getString("rally.permission-gui.title", "<gold>Faction Permissions"))));
        holder.inventory = inventory;
        for (String role : ROLES) inventory.setItem(roleSlot(role), roleItem(role, role.equals(selectedRole)));
        int slot = 9;
        for (PermissibleActions action : PermissibleActions.values()) {
            inventory.setItem(slot++, actionItem(faction, selectedRole, action.name(), action.shortDescription()));
        }
        for (CustomAction action : CUSTOM_ACTIONS) {
            inventory.setItem(slot++, actionItem(faction, selectedRole, action.id(), action.label()));
        }
        player.openInventory(inventory);
    }

    @EventHandler public void onDrag(InventoryDragEvent event) { if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true); }
    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        Faction faction = FactionsHook.getFaction(player);
        if (faction == null || faction.id() != holder.factionId || !FactionsHook.isLeader(player)) { player.closeInventory(); return; }
        ItemStack clicked = event.getCurrentItem(); if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        String role = meta.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
        if (role != null) { open(player, faction, role); return; }
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action != null) {
            set(faction, holder.role, action, !event.isRightClick());
            open(player, faction, holder.role);
        }
    }

    private ItemStack roleItem(String role, boolean selected) {
        String path = "rally.permission-gui.roles." + role;
        ItemStack item = new ItemStack(material(plugin.getConfig().getString(path + ".material", defaultMaterial(role))));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageFormatter.deserialize(smallCaps(plugin.getConfig().getString(path + ".name", "<gold>" + role))));
        meta.lore(List.of(MessageFormatter.deserialize(smallCaps(selected ? "<green>Selected" : "<gray>Click to select"))));
        meta.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role); item.setItemMeta(meta); return item;
    }

    private ItemStack actionItem(Faction faction, String role, String action, String label) {
        boolean allowed = allowed(faction, role, action);
        String actionPath = "rally.permission-gui.actions." + action.toLowerCase(Locale.ROOT).replace(':', '-');
        String configuredLabel = plugin.getConfig().getString(actionPath + ".name", label);
        // A green pane always means allowed and a red pane always means
        // denied. In particular, Set/Clear Rally must never look blocked
        // merely because their custom label was once paired with a Barrier.
        ItemStack item = new ItemStack(allowed ? Material.GREEN_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageFormatter.deserialize(smallCaps((allowed ? "<green>" : "<red>") + configuredLabel)));
        meta.lore(List.of(MessageFormatter.deserialize(smallCaps(allowed ? "<green>Allowed" : "<red>Denied")),
                MessageFormatter.deserialize(smallCaps("<gray>Left-click: allow | Right-click: deny"))));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action); item.setItemMeta(meta); return item;
    }

    private boolean allowed(Faction faction, String role, String action) {
        if (action.startsWith("vertex:")) return manager.rolePermission(faction.id(), role, action.substring("vertex:".length()));
        Faction.Permissions permissions = faction.permissions();
        return factionSelectors(role).stream().allMatch(selector -> !permissions.has(selector)
                || permissions.get(selector).get(action) != PermState.DENY);
    }

    private void set(Faction faction, String role, String action, boolean allowed) {
        if (action.startsWith("vertex:")) { manager.setRolePermission(faction.id(), role, action.substring("vertex:".length()), allowed); return; }
        Faction.Permissions permissions = faction.permissions();
        for (PermSelector selector : factionSelectors(role)) {
            Faction.Permissions.SelectorPerms rolePermissions = permissions.has(selector) ? permissions.get(selector) : permissions.add(selector);
            rolePermissions.set(action, allowed ? PermState.ALLOW : PermState.DENY);
            // Exact role rows must take priority over FactionsUUID's inherited/default selectors.
            while (permissions.selectors().indexOf(selector) > 0) permissions.moveSelectorUp(selector);
        }
    }

    private static List<PermSelector> factionSelectors(String role) {
        return switch (role) {
            case "mod" -> List.of(new RoleSingleSelector(Role.COLEADER), new RoleSingleSelector(Role.MODERATOR));
            case "recruit" -> List.of(new RoleSingleSelector(Role.RECRUIT));
            default -> List.of(new RoleSingleSelector(Role.NORMAL));
        };
    }
    private int roleSlot(String role) {
        int fallback = switch (role) { case "mod" -> 2; case "member" -> 4; default -> 6; };
        int configured = plugin.getConfig().getInt("rally.permission-gui.roles." + role + ".slot", fallback);
        return configured >= 0 && configured < 9 ? configured : fallback;
    }
    private boolean isFactionCommand(String rawCommand) {
        String command = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;
        int namespaceSeparator = command.indexOf(':');
        if (namespaceSeparator >= 0) {
            command = command.substring(namespaceSeparator + 1);
        }
        String normalized = command.toLowerCase(Locale.ROOT);
        return plugin.getConfig().getStringList("factions.command-aliases").stream()
                .map(alias -> alias.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    /** Converts only display text, preserving MiniMessage tags such as <gold>. */
    private static String smallCaps(String value) {
        StringBuilder output = new StringBuilder(value.length());
        boolean inTag = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '<') inTag = true;
            if (inTag) {
                output.append(character);
                if (character == '>') inTag = false;
                continue;
            }
            output.append(smallCap(character));
        }
        return output.toString();
    }

    private static char smallCap(char character) {
        return switch (Character.toLowerCase(character)) {
            case 'a' -> 'ᴀ'; case 'b' -> 'ʙ'; case 'c' -> 'ᴄ'; case 'd' -> 'ᴅ'; case 'e' -> 'ᴇ';
            case 'f' -> 'ꜰ'; case 'g' -> 'ɢ'; case 'h' -> 'ʜ'; case 'i' -> 'ɪ'; case 'j' -> 'ᴊ';
            case 'k' -> 'ᴋ'; case 'l' -> 'ʟ'; case 'm' -> 'ᴍ'; case 'n' -> 'ɴ'; case 'o' -> 'ᴏ';
            case 'p' -> 'ᴘ'; case 'q' -> 'ǫ'; case 'r' -> 'ʀ'; case 's' -> 'ꜱ'; case 't' -> 'ᴛ';
            case 'u' -> 'ᴜ'; case 'v' -> 'ᴠ'; case 'w' -> 'ᴡ'; case 'x' -> 'x'; case 'y' -> 'ʏ';
            case 'z' -> 'ᴢ'; default -> character;
        };
    }
    private static String defaultMaterial(String role) { return switch (role) { case "admin" -> "NETHERITE_SWORD"; case "mod" -> "DIAMOND_SWORD"; case "member" -> "GOLDEN_SWORD"; default -> "WOODEN_SWORD"; }; }
    private static Material material(String name) { try { return Material.valueOf(name.toUpperCase(Locale.ROOT)); } catch (Exception e) { return Material.BARRIER; } }
    private record CustomAction(String id, String label) { }
    private static final class Holder implements InventoryHolder { final int factionId; final String role; Inventory inventory; Holder(int factionId, String role) { this.factionId=factionId; this.role=role; } @Override public Inventory getInventory(){return inventory;} }
}
