package dev.pawfect.bloodcamp.gen;

import java.util.Locale;

public enum Floor {
    E(4, 4), F1(4, 5), F2(5, 5), F3(5, 5), F4(6, 5), F5(6, 5), F6(5, 6), F7(6, 6);

    public final int width;
    public final int height;

    Floor(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public boolean supported() {
        return this == F7;
    }

    public static Floor parse(String s) {
        String up = s.trim().toUpperCase(Locale.ROOT);
        if (up.equals("ENTRANCE") || up.equals("F0")) return E;
        try {
            return valueOf(up);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown floor: " + s);
        }
    }
}
