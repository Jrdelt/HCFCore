package me.vertex.core.mine;

import java.util.Map;

/**
 * The Mine KOTH control state machine.
 *
 * <p>Control is a single 0-100 percentage owned by whichever faction is
 * currently attributed the point, rather than a progress bar per faction.
 * An attacker pushes the holder's control down, and only once it reaches
 * zero do they begin climbing their own. That is what makes taking a
 * defended point cost roughly twice as long as taking an empty one, without
 * needing to track every faction's progress separately.
 *
 * <p>Kept free of Bukkit so every contested/defended/threshold rule is
 * unit-testable, the same way {@code ShopPricing} is.
 */
public final class MineKothControl {

    public enum State {
        /** Nobody eligible is in the zone. */
        IDLE,
        /** One faction is making progress unopposed. */
        CAPTURING,
        /** More than one hostile faction is present; nobody advances. */
        CONTESTED,
        /** The owner is pushing their control back toward 100%. */
        DEFENDING,
        /** The owner holds it outright at 100%. */
        HELD
    }

    /**
     * @param baseCaptureSeconds     time for one member to go 0 to 100 uncontested
     * @param additionalMemberSpeed  extra rate per member beyond the first
     * @param maxCountedMembers      members past this add nothing
     * @param maxSpeedMultiplier     hard ceiling on the combined multiplier
     * @param boosterResetThreshold  control below this wipes booster progression
     */
    public record Settings(
            double baseCaptureSeconds,
            boolean multipleMembersSpeedUp,
            double additionalMemberSpeed,
            int maxCountedMembers,
            double maxSpeedMultiplier,
            double boosterResetThreshold) {

        public static final Settings DEFAULTS =
                new Settings(180D, true, 0.25D, 5, 2.0D, 50D);
    }

    /**
     * @param ownerFactionId    the faction holding the point, or null when unowned
     * @param controlPercent    0-100, belonging to the owner, or to the capturer when unowned
     * @param capturingFactionId who {@code controlPercent} belongs to while unowned
     */
    public record Snapshot(Integer ownerFactionId, double controlPercent, Integer capturingFactionId) {
        public static final Snapshot UNOWNED = new Snapshot(null, 0D, null);
    }

    /**
     * @param ownerChanged        a new faction just reached 100%
     * @param lostControl         the previous owner just dropped to 0%
     * @param crossedResetThreshold control fell below the booster reset threshold this tick
     */
    public record Result(
            Integer ownerFactionId,
            double controlPercent,
            Integer capturingFactionId,
            State state,
            boolean ownerChanged,
            boolean lostControl,
            boolean crossedResetThreshold) {
    }

    private MineKothControl() {
    }

    /** Rate multiplier for a faction with {@code members} eligible players in the zone. */
    public static double speedMultiplier(int members, Settings settings) {
        if (!settings.multipleMembersSpeedUp() || members <= 1) {
            return 1D;
        }
        int counted = Math.min(members, Math.max(1, settings.maxCountedMembers()));
        return Math.min(settings.maxSpeedMultiplier(),
                1D + (counted - 1) * settings.additionalMemberSpeed());
    }

    /**
     * @param membersInZone eligible members per faction currently inside the zone
     */
    public static Result tick(Snapshot current, Map<Integer, Integer> membersInZone,
            double secondsElapsed, Settings settings) {
        Integer owner = current.ownerFactionId();
        double control = current.controlPercent();
        Integer capturer = current.capturingFactionId();

        boolean ownerPresent = owner != null && membersInZone.containsKey(owner);
        int attackerCount = (int) membersInZone.keySet().stream()
                .filter(faction -> !faction.equals(owner))
                .count();

        if (membersInZone.isEmpty()) {
            return unchanged(current, owner != null && control >= 100D ? State.HELD : State.IDLE);
        }

        // More than one hostile faction: nobody advances. The holder keeps
        // the point rather than it sliding to whoever happens to be present,
        // so a rival cannot be used as cover to take it.
        if (attackerCount > 1 && !ownerPresent) {
            return unchanged(current, State.CONTESTED);
        }

        if (ownerPresent) {
            // The owner defends. Enemies present or not, they cannot advance
            // while the holder is standing on it.
            double restored = Math.min(100D, control + rate(membersInZone.get(owner), secondsElapsed, settings));
            State state = restored >= 100D ? State.HELD : State.DEFENDING;
            return new Result(owner, restored, null, state, false, false, false);
        }

        if (attackerCount > 1) {
            return unchanged(current, State.CONTESTED);
        }

        Integer attacker = membersInZone.keySet().iterator().next();
        double delta = rate(membersInZone.get(attacker), secondsElapsed, settings);

        if (owner != null) {
            // Push the holder's control down first; the point only changes
            // hands once it has been fully worn away.
            double reduced = control - delta;
            if (reduced > 0D) {
                boolean crossed = control >= settings.boosterResetThreshold()
                        && reduced < settings.boosterResetThreshold();
                return new Result(owner, reduced, attacker, State.CAPTURING, false, false, crossed);
            }
            // Spent what was left knocking it to zero; the rest starts the
            // attacker's own climb in the same tick.
            double carried = Math.min(100D, -reduced);
            return new Result(null, carried, attacker, State.CAPTURING, false, true,
                    control >= settings.boosterResetThreshold());
        }

        // Unowned. A different faction arriving starts from zero rather than
        // inheriting the previous capturer's progress.
        double progress = attacker.equals(capturer) ? control : 0D;
        double climbed = Math.min(100D, progress + delta);
        if (climbed >= 100D) {
            return new Result(attacker, 100D, null, State.HELD, true, false, false);
        }
        return new Result(null, climbed, attacker, State.CAPTURING, false, false, false);
    }

    private static double rate(int members, double secondsElapsed, Settings settings) {
        double perSecond = 100D / Math.max(0.001D, settings.baseCaptureSeconds());
        return perSecond * speedMultiplier(members, settings) * secondsElapsed;
    }

    private static Result unchanged(Snapshot current, State state) {
        return new Result(current.ownerFactionId(), current.controlPercent(), current.capturingFactionId(),
                state, false, false, false);
    }
}
