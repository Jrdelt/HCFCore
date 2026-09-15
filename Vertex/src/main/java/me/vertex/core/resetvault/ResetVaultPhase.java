package me.vertex.core.resetvault;

/**
 * Global lifecycle phases for Reset Vault storage.
 * State is global across the network and never per-player.
 */
public enum ResetVaultPhase {
    /** Players cannot open the vault; localized closed message is sent. */
    CLOSED("reset-vault.phase.closed"),
    /** Players may deposit eligible items but cannot withdraw. */
    DEPOSIT("reset-vault.phase.deposit"),
    /** Players may withdraw individual items but cannot deposit. */
    WITHDRAW("reset-vault.phase.withdraw"),
    /** New sessions are blocked; existing open sessions stay open but are read-only. */
    BACKUP_PENDING("reset-vault.phase.backup-pending"),
    /** All vault access and editing is locked while backup runs. */
    BACKUP_RUNNING("reset-vault.phase.backup-running"),
    /** All vault access is locked until highest-authority recovery succeeds. */
    RECOVERY_LOCKED("reset-vault.phase.recovery-locked");

    private final String langKey;

    ResetVaultPhase(String langKey) {
        this.langKey = langKey;
    }

    /**
     * @return the localization key in en_US.yml representing this phase.
     */
    public String langKey() {
        return langKey;
    }

    /**
     * @return whether players may open their vaults in this phase.
     */
    public boolean isAccessible() {
        return this == DEPOSIT || this == WITHDRAW;
    }

    /**
     * @return whether the phase enforces strict read-only mode for active sessions.
     */
    public boolean isReadOnly() {
        return this == BACKUP_PENDING || this == BACKUP_RUNNING || this == RECOVERY_LOCKED || this == CLOSED;
    }

    /**
     * @return whether any normal or admin vault modification is completely blocked.
     */
    public boolean isLocked() {
        return this == BACKUP_RUNNING || this == RECOVERY_LOCKED;
    }
}
