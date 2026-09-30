package com.omegarealm.core.player;

import java.util.Set;

/**
 * Plain account aliases; display spelling is preserved, comparison folds ASCII only.
 */
public final class NicknamePolicy {

    private static final Set<String> RESERVED = Set.of("clear", "moderator", "admin", "owner");

    private NicknamePolicy() {
    }

    public static boolean valid(String nickname) {
        return nickname != null && nickname.matches("[A-Za-z0-9_]{3,16}");
    }

    public static boolean reserved(String nickname) {
        return RESERVED.contains(key(nickname));
    }

    public static String key(String name) {
        StringBuilder folded = new StringBuilder(name.length());
        for (char character : name.toCharArray()) {
            folded.append(character >= 'A' && character <= 'Z' ? (char) (character + 32) : character);
        }
        return folded.toString();
    }

    static Set<String> accountKeys(String username) {
        String key = key(username);
        return key.startsWith(".") ? Set.of(key, key.substring(1)) : Set.of(key);
    }
}
