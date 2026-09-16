package me.vertex.core.factions;

import me.vertex.core.faction.FactionBankManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FactionAdminCommandTest {

    @Test
    void adminBankSyntaxUsesOperationThenAmountThenBalanceType() {
        FactionAdminCommand.BankChange change = FactionAdminCommand.BankChange.parse("set", "1250", "tnt");

        assertEquals(FactionBankManager.BankType.TNT, change.type());
        assertEquals(FactionBankManager.AdminOperation.SET, change.operation());
        assertEquals(1_250L, change.wholeAmount());
    }

    @Test
    void adminBankSyntaxAllowsDecimalMoneyButNotFractionalPhysicalBalances() {
        FactionAdminCommand.BankChange money = FactionAdminCommand.BankChange.parse("add", "12.5", "money");

        assertEquals(12.5D, money.moneyAmount());
        assertNull(FactionAdminCommand.BankChange.parse("add", "12.5", "xp"));
        assertNull(FactionAdminCommand.BankChange.parse("add", "12.5", "tnt"));
        assertNull(FactionAdminCommand.BankChange.parse("tnt", "set", "1250"));
    }
}

