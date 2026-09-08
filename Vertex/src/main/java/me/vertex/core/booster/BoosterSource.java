package me.vertex.core.booster;

import org.bukkit.entity.Player;

import java.util.List;

/**
 * Something that can grant a bonus. Implementations are thin adapters over
 * the system that already owns the value -- they read it, they never
 * reimplement it, so the tracker cannot disagree with what the server applies.
 *
 * <p>New booster kinds (Mine KOTH, Hot Zones, Resource Rush, temporary event
 * rewards) plug in by adding a source here rather than by touching the
 * stacking or display code.
 */
public interface BoosterSource {

    /** Stable id, used as the lang key suffix for this source's display name. */
    String id();

    /**
     * This source's offers toward {@code category} for {@code player}.
     * Return an empty list when the source has nothing to do with the
     * category at all; return an inactive contribution when it would apply
     * but currently does not, so the tracker can explain why.
     */
    List<BoosterContribution> contribute(Player player, BoosterCategory category);
}
