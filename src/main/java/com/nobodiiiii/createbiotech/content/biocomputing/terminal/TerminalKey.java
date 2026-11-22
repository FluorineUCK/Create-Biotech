package com.nobodiiiii.createbiotech.content.biocomputing.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Stable terminal key names, independent of GLFW and the player's keyboard layout settings. */
public final class TerminalKey {
    public static final String ANY = "any";
    public static final List<String> CHOICES;
    static {
        var keys = new ArrayList<>(List.of("space", "up", "down", "right", "left", ANY));
        "abcdefghijklmnopqrstuvwxyz0123456789".chars().forEach(c -> keys.add(Character.toString(c)));
        CHOICES = List.copyOf(keys);
    }
    private TerminalKey() {}
    public static String selector(String key) {
        String name = key.strip().toLowerCase(Locale.ROOT);
        if (!CHOICES.contains(name)) throw new IllegalArgumentException("Unsupported terminal key: " + name);
        return name;
    }
    public static String input(String key) {
        String name = selector(key);
        if (ANY.equals(name)) throw new IllegalArgumentException("Any key is a selector, not a physical key");
        return name;
    }
    public static long bit(String key) { return 1L << CHOICES.indexOf(input(key)); }
    public static boolean held(long bits, String selector) { return ANY.equals(selector) ? bits != 0 : (bits & bit(selector)) != 0; }
    public static long requireBits(long bits) {
        long allowed = ((1L << CHOICES.size()) - 1) & ~(1L << CHOICES.indexOf(ANY));
        if ((bits & ~allowed) != 0) throw new IllegalArgumentException("Invalid terminal key state");
        return bits;
    }
}
