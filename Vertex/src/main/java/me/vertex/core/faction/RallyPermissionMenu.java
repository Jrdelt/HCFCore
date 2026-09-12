package me.vertex.core.faction;

import me.vertex.core.factions.FactionData;
import me.vertex.core.factions.FactionMember;
import me.vertex.core.factions.FactionRole;
import me.vertex.core.factions.FactionsHook;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;

/** Native Vertex faction permission editor. All rows are saved to the database. */
public final class RallyPermissionMenu implements Listener {
    private static final List<String> ROLES = List.of("coleader", "admin", "mod", "member", "recruit", "ally");
    private static final List<Action> ACTIONS = List.of(
            new Action("place-blocks"), new Action("break-blocks"), new Action("containers"), new Action("doors"),
            new Action("spawner-gui"), new Action("spawner-place"), new Action("spawner-remove"),
            new Action("claim"), new Action("unclaim"), new Action("invite"), new Action("kick"),
            new Action("promote"), new Action("demote"), new Action("ally-request"), new Action("enemy-declare"),
            new Action("neutral-request"),
            new Action("home"), new Action("sethome"), new Action("setwarp"), new Action("description"),
            new Action("rally-set"), new Action("rally-clear"), new Action("collector-open"), new Action("collector-break"),
            new Action("collector-withdraw"), new Action("collector-sell"), new Action("collector-upgrade"),
            new Action("collector-filter"), new Action("bank-deposit"), new Action("bank-withdraw"),
            new Action("xp-deposit"), new Action("xp-withdraw"), new Action("tnt-deposit"), new Action("tnt-withdraw"),
            new Action("tnt-fill"), new Action("vault-use"), new Action("chunkbuster-use"));
    private static final List<Action> ALLY_ACTIONS = List.of(new Action("sethome"), new Action("place-blocks"),
            new Action("break-blocks"), new Action("containers"), new Action("levers"), new Action("buttons"),
            new Action("pvp"));
    private final Plugin plugin;
    private final Messages messages;
    private final NamespacedKey roleKey;
    private final NamespacedKey actionKey;

    public RallyPermissionMenu(Plugin plugin, RallyManager ignoredManager, Messages messages) {
        this.plugin = plugin; this.messages = messages;
        roleKey = new NamespacedKey(plugin, "faction_perms_role");
        actionKey = new NamespacedKey(plugin, "faction_perms_action");
    }

    public void open(Player player) {
        FactionData faction = FactionsHook.getFaction(player).orElse(null);
        FactionMember member = FactionsHook.service().member(player.getUniqueId());
        if (faction != null && canEditAny(member)) open(player, faction,
                member.role() == FactionRole.ADMIN ? "member" : member.role() == FactionRole.COLEADER ? "admin" : "coleader");
    }

    @EventHandler
    public void onFactionPermissionsCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length != 2 || !isFactionCommand(parts[0]) || !(parts[1].equalsIgnoreCase("permissions") || parts[1].equalsIgnoreCase("perms"))) return;
        event.setCancelled(true);
        if (FactionsHook.getFaction(event.getPlayer()).isEmpty()) { event.getPlayer().sendMessage(messages.getGui(event.getPlayer(), "factions.must-be-in-faction")); return; }
        if (!canEditAny(FactionsHook.service().member(event.getPlayer().getUniqueId()))) { event.getPlayer().sendMessage(messages.getGui(event.getPlayer(), "factions.permissions-leader-only")); return; }
        open(event.getPlayer());
    }

    private void open(Player player, FactionData faction, String selectedRole) {
        Holder holder = new Holder(faction.id(), selectedRole);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "faction-permissions.title"));
        holder.inventory = inventory;
        FactionMember editor = FactionsHook.service().member(player.getUniqueId());
        for (String role : ROLES) if (canEditRole(editor, role)) inventory.setItem(roleSlot(role), roleItem(role, role.equals(selectedRole)));
        List<Action> actions = selectedRole.equals("ally") ? ALLY_ACTIONS : ACTIONS;
        for (int index = 0; index < actions.size() && index + 9 < inventory.getSize(); index++) {
            Action action = actions.get(index); inventory.setItem(index + 9, actionItem(faction, selectedRole, action));
        }
        player.openInventory(inventory);
    }

    @EventHandler public void onDrag(InventoryDragEvent event) { if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true); }
    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        FactionData faction = FactionsHook.getFaction(player).orElse(null);
        FactionMember editor = FactionsHook.service().member(player.getUniqueId());
        if (faction == null || faction.id() != holder.factionId || !canEditAny(editor)) { player.closeInventory(); return; }
        ItemStack clicked = event.getCurrentItem(); if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        String role = meta.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
        if (role != null) { if(canEditRole(editor,role))open(player, faction, role); return; }
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        String selectedRole=holder.role;boolean allowed=!event.isRightClick();
        FactionsHook.service().submitMutation(()->FactionsHook.service().setAction(player,selectedRole,action,allowed))
                .whenComplete((result,error)->Bukkit.getScheduler().runTask(plugin,()->{
                    if(!player.isOnline())return;
                    FactionData current=FactionsHook.getFaction(player).orElse(null);
                    if(error!=null||result!=me.vertex.core.factions.FactionService.Result.OK||current==null
                            ||current.id()!=holder.factionId||!canEditAny(FactionsHook.service().member(player.getUniqueId()))){
                        player.closeInventory();return;
                    }
                    open(player,current,selectedRole);
                }));
    }

    private ItemStack roleItem(String role, boolean selected) {
        String path = "rally.permission-gui.roles." + role;
        ItemStack item = new ItemStack(material(plugin.getConfig().getString(path + ".material", defaultMaterial(role))));
        ItemMeta meta = item.getItemMeta(); meta.displayName(messages.getGui(null, "faction-permissions.role-" + role));
        meta.lore(List.of(messages.getGui(null, selected ? "faction-permissions.selected" : "faction-permissions.select")));
        meta.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role); item.setItemMeta(meta); return item;
    }

    private ItemStack actionItem(FactionData faction, String role, Action action) {
        boolean allowed = FactionsHook.service().actionAllowed(faction.id(), role, action.id());
        ItemStack item = new ItemStack(allowed ? Material.GREEN_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta(); meta.displayName(messages.getGui(null, "faction-permissions.action-" + action.id()));
        meta.lore(List.of(messages.getGui(null, allowed ? "faction-permissions.allowed" : "faction-permissions.denied"),
                messages.getGui(null, "faction-permissions.click-hint")));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action.id()); item.setItemMeta(meta); return item;
    }

    private int roleSlot(String role) { int fallback = switch (role) { case "coleader" -> 1; case "admin" -> 2; case "mod" -> 3; case "member" -> 4; case "recruit" -> 5; default -> 7; }; int configured = plugin.getConfig().getInt("rally.permission-gui.roles." + role + ".slot", fallback); return configured >= 0 && configured < 9 ? configured : fallback; }
    private boolean isFactionCommand(String raw) { String command = raw.startsWith("/") ? raw.substring(1) : raw; int separator = command.indexOf(':'); if (separator >= 0) command = command.substring(separator + 1); String normalized = command.toLowerCase(Locale.ROOT); return plugin.getConfig().getStringList("factions.command-aliases").stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(normalized::equals); }
    private static boolean canEditAny(FactionMember member){return member!=null&&member.role().atLeast(FactionRole.ADMIN);}
    private static boolean canEditRole(FactionMember editor,String role){if(editor==null)return false;if(role.equals("ally"))return editor.role()==FactionRole.LEADER||editor.role()==FactionRole.COLEADER;FactionRole target=FactionRole.parse(role,FactionRole.RECRUIT);return editor.role()==FactionRole.LEADER&&target.weight()<FactionRole.LEADER.weight()||editor.role()==FactionRole.COLEADER&&target.weight()<=FactionRole.ADMIN.weight()||editor.role()==FactionRole.ADMIN&&(target==FactionRole.MEMBER||target==FactionRole.RECRUIT);}
    private static String defaultMaterial(String role) { return switch (role) { case "coleader" -> "NETHERITE_SWORD"; case "admin" -> "DIAMOND_SWORD"; case "mod" -> "IRON_SWORD"; case "member" -> "GOLDEN_SWORD"; case "ally" -> "PURPLE_DYE"; default -> "WOODEN_SWORD"; }; }
    private static Material material(String name) { try { return Material.valueOf(name.toUpperCase(Locale.ROOT)); } catch (Exception ignored) { return Material.BARRIER; } }
    private record Action(String id) { }
    private static final class Holder implements InventoryHolder { final int factionId; final String role; Inventory inventory; Holder(int factionId, String role) { this.factionId = factionId; this.role = role; } @Override public Inventory getInventory() { return inventory; } }
}
