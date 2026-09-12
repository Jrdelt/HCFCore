package me.vertex.core.lang;

/** Converts static UI copy to the server's small-caps alphabet without touching tags or placeholders. */
public final class SmallCaps {
    private SmallCaps() { }

    public static String template(String input) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder output = new StringBuilder(input.length());
        char protectedUntil = 0;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (protectedUntil != 0) {
                output.append(character);
                if (character == protectedUntil) protectedUntil = 0;
                continue;
            }
            if (character == '<') { protectedUntil = '>'; output.append(character); continue; }
            if (character == '{') { protectedUntil = '}'; output.append(character); continue; }
            if (character == '%') {
                int end = input.indexOf('%', index + 1);
                if (end > index + 1 && input.substring(index + 1, end).matches("[A-Za-z0-9_.:-]+")) {
                    output.append(input, index, end + 1);
                    index = end;
                    continue;
                }
            }
            if ((character == '&' || character == '§') && index + 1 < input.length()) {
                char code = input.charAt(index + 1);
                if (code == '#' && index + 7 < input.length()
                        && input.substring(index + 2, index + 8).matches("[0-9A-Fa-f]{6}")) {
                    output.append(input, index, index + 8);
                    index += 7;
                    continue;
                }
                if ("0123456789abcdefklmnorx".indexOf(Character.toLowerCase(code)) >= 0) {
                    output.append(character).append(code);
                    index++;
                    continue;
                }
            }
            output.append(letter(character));
        }
        return output.toString();
    }

    private static char letter(char character) {
        return switch (Character.toLowerCase(character)) {
            case 'a' -> 'ᴀ'; case 'b' -> 'ʙ'; case 'c' -> 'ᴄ'; case 'd' -> 'ᴅ'; case 'e' -> 'ᴇ';
            case 'f' -> 'ꜰ'; case 'g' -> 'ɢ'; case 'h' -> 'ʜ'; case 'i' -> 'ɪ'; case 'j' -> 'ᴊ';
            case 'k' -> 'ᴋ'; case 'l' -> 'ʟ'; case 'm' -> 'ᴍ'; case 'n' -> 'ɴ'; case 'o' -> 'ᴏ';
            case 'p' -> 'ᴘ'; case 'q' -> 'ǫ'; case 'r' -> 'ʀ'; case 's' -> 'ꜱ'; case 't' -> 'ᴛ';
            case 'u' -> 'ᴜ'; case 'v' -> 'ᴠ'; case 'w' -> 'ᴡ'; case 'x' -> 'x'; case 'y' -> 'ʏ';
            case 'z' -> 'ᴢ'; default -> character;
        };
    }
}
