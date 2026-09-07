package me.vertex.core.capture;

/** Capture-progress math kept free of Bukkit so speed rules stay testable. */
final class CaptureProgression {
    private CaptureProgression() {
    }

    static double progressPerSecond(int captureSeconds, int factionMembers, double additionalMemberSpeed) {
        if (captureSeconds <= 0 || factionMembers <= 0) {
            return 0D;
        }
        double multiplier = 1D + Math.max(0, factionMembers - 1) * Math.max(0D, additionalMemberSpeed);
        return 100D / captureSeconds * multiplier;
    }

    static long remainingSeconds(double progressPercent, int captureSeconds, int factionMembers,
            double additionalMemberSpeed) {
        double rate = progressPerSecond(captureSeconds, factionMembers, additionalMemberSpeed);
        return rate <= 0D ? 0L : Math.max(0L, (long) Math.ceil((100D - progressPercent) / rate));
    }
}
