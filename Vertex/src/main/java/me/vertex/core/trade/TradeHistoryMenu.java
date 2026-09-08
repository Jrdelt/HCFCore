package me.vertex.core.trade;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only, paginated history results. SQL paging happens before this view opens. */
final class TradeHistoryMenu {
    private static final int PAGE_SIZE=45;
    private TradeHistoryMenu() { }
    static void open(Player viewer, Messages messages, UUID filter, String label, int page, List<TradeLogEntry> entries) {
        Inventory inventory=Bukkit.createInventory(new Holder(filter,page),54,messages.get(viewer,"trade.history-title","player",label));
        for(int slot=0;slot<54;slot++) inventory.setItem(slot, filler());
        for(int i=0;i<entries.size()&&i<PAGE_SIZE;i++) inventory.setItem(i, entry(entries.get(i),viewer,messages));
        inventory.setItem(45, button(Material.ARROW,messages.get(viewer,"trade.history-previous")));
        inventory.setItem(49, button(Material.BARRIER,messages.get(viewer,"trade.history-read-only")));
        inventory.setItem(53, button(Material.ARROW,messages.get(viewer,"trade.history-next")));
        viewer.openInventory(inventory);
    }
    private static ItemStack entry(TradeLogEntry entry, Player viewer, Messages messages) {
        ItemStack item=new ItemStack("SUSPICIOUS".equals(entry.status())?Material.REDSTONE:Material.PAPER); ItemMeta meta=item.getItemMeta();
        String other=entry.requester().equals(viewer.getUniqueId())?entry.targetName():entry.requesterName(); meta.displayName(messages.get(viewer,"trade.history-entry","player",other,"id",String.valueOf(entry.id())));
        List<net.kyori.adventure.text.Component> lore=new ArrayList<>();
        lore.add(messages.get(viewer,"trade.history-status","status",entry.status().toLowerCase())); lore.add(messages.get(viewer,"trade.history-money","left",String.valueOf(entry.requesterMoney()),"right",String.valueOf(entry.targetMoney()))); lore.add(messages.get(viewer,"trade.history-exp","left",String.valueOf(entry.requesterExperience()),"right",String.valueOf(entry.targetExperience()))); lore.add(messages.get(viewer,"trade.history-items","left",String.valueOf(count(entry.requesterItems())),"right",String.valueOf(count(entry.targetItems())))); lore.add(messages.get(viewer,"trade.history-date","date",DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.timestamp())))); meta.lore(lore); item.setItemMeta(meta); return item;
    }
    private static int count(ItemStack[] items){int total=0;for(ItemStack i:items)if(i!=null)total+=i.getAmount();return total;}
    private static ItemStack filler(){return button(Material.GRAY_STAINED_GLASS_PANE,net.kyori.adventure.text.Component.empty());}
    private static ItemStack button(Material m,net.kyori.adventure.text.Component text){ItemStack i=new ItemStack(m);ItemMeta meta=i.getItemMeta();meta.displayName(text);i.setItemMeta(meta);return i;}
    record Holder(UUID filter,int page) implements InventoryHolder { @Override public Inventory getInventory(){return null;} }
}
