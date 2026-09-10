package me.vertex.core.mine;

import me.vertex.core.booster.BoosterService;
import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** {@link MinesMenu} is read-only: it navigates, it never changes anything. */
public final class MinesMenuListener implements Listener {

    private final MineManager mines;
    private final MineKothManager koths;
    private final HotZoneManager hotZones;
    private final BoosterService boosters;
    private final Messages messages;
    private final MenuRegistry menus;
    private final MineTeleportManager teleports;

    public MinesMenuListener(MineManager mines, MineKothManager koths, HotZoneManager hotZones,
            BoosterService boosters, Messages messages, MenuRegistry menus, MineTeleportManager teleports) {
        this.mines = mines;
        this.koths = koths;
        this.hotZones = hotZones;
        this.boosters = boosters;
        this.messages = messages;
        this.menus = menus;
        this.teleports = teleports;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MinesMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MinesMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        if (holder.mineId() != null) {
            var back = menus.layout(MinesMenu.MENU_ID).item("back");
            if (back != null && event.getRawSlot() == back.slot()) {
                menus.layout(MinesMenu.MENU_ID).playSound(viewer, "back");
                MinesMenu.openOverview(viewer, mines, koths, hotZones, boosters, messages, menus);
                return;
            }
            var teleport = menus.layout(MinesMenu.MENU_ID).item("teleport");
            int teleportSlot = teleport == null ? 20 : teleport.slot();
            if (event.getRawSlot() == teleportSlot) {
                if (teleport != null) menus.layout(MinesMenu.MENU_ID).playSound(viewer, "teleport");
                teleports.open(viewer, holder.mineId());
            }
            return;
        }
        String clicked = holder.mineAt(event.getRawSlot());
        if (clicked != null && mines.region(clicked) != null && mines.region(clicked).isDefined()) {
            menus.layout(MinesMenu.MENU_ID).playSound(viewer, "mine");
            MinesMenu.openDetail(viewer, clicked, mines, koths, hotZones, boosters, messages, menus);
        }
    }
}
