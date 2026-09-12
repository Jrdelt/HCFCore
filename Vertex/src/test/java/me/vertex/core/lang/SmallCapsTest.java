package me.vertex.core.lang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmallCapsTest {
    @Test void convertsStaticTextButPreservesTagsAndPlaceholders() {
        assertEquals("<green>ᴘʟᴀʏᴇʀ: <white>{player} %vault_balance%",
                SmallCaps.template("<green>Player: <white>{player} %vault_balance%"));
    }

    @Test void keepsNumbersPunctuationAndExistingSmallCaps() {
        assertEquals("ʟᴇᴠᴇʟ 5 • 10%", SmallCaps.template("Level 5 • 10%"));
    }

    @Test void aPercentageSignDoesNotProtectFollowingStaticWords() {
        assertEquals("250% ʙᴏɴᴜꜱ", SmallCaps.template("250% Bonus"));
    }

    @Test void preservesLegacyAndHexFormattingCodes() {
        assertEquals("&aɢʀᴇᴇɴ &lʙᴏʟᴅ &#12AbEFʜᴇx §cʀᴇᴅ",
                SmallCaps.template("&aGreen &lBold &#12AbEFHex §cRed"));
    }
}
