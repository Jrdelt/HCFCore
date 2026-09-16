package me.vertex.core.factions;

import me.vertex.core.factions.event.FactionLifecycleEvent;
import me.vertex.core.preferences.AnnouncementCategory;
import me.vertex.core.preferences.AnnouncementPreferenceManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Broadcasts every faction creation and disband to the whole server. */
public final class FactionAnnounceListener implements Listener {

    private final AnnouncementPreferenceManager announcements;

    public FactionAnnounceListener(AnnouncementPreferenceManager announcements) {
        this.announcements = announcements;
    }

    @EventHandler
    public void onLifecycle(FactionLifecycleEvent event) {
        switch (event.action()) {
            case CREATE -> announcements.broadcast(AnnouncementCategory.SERVER, "factions.announce-created",
                    "faction", event.faction().tag());
            case DISBAND -> announcements.broadcast(AnnouncementCategory.SERVER, "factions.announce-disbanded",
                    "faction", event.faction().tag());
            case RENAME -> {
                // Not a create/disband event; no broadcast requested for renames.
            }
        }
    }
}
