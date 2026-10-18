package dev.pawfect.bloodcamp.gen;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

public record Seed(Floor floor, int version, long value) {
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    public static final int CHARS = 5;
    public static final long MASK = (1L << (CHARS * 5)) - 1;
    public static final Floor FLOOR = Floor.F7;
    private static final long DAILY_SALT = 0x5EED_B100_DCA4_7L;

    public Seed {
        value &= MASK;
    }

    public Seed(long value) {
        this(FLOOR, LayoutGenerator.VERSION, value);
    }

    public static Seed random(Rng rng) {
        return new Seed(rng.nextLong());
    }

    public static Seed daily(LocalDate day) {
        return new Seed(new Rng(day.toEpochDay() ^ DAILY_SALT ^ ((long) LayoutGenerator.VERSION << 48)).nextLong());
    }

    public static Seed today() {
        return daily(LocalDate.now(ZoneOffset.UTC));
    }

    public static Seed parse(String text) {
        String body = text.trim().toUpperCase(Locale.ROOT);
        if (body.startsWith("F7")) body = body.substring(2).replaceFirst("^[\\s\\-·]+", "");
        if (body.contains("-")) throw new IllegalArgumentException("That's an old seed. Seeds are " + CHARS + " characters now, like K3Q9Z");
        body = body.replace('O', '0').replace('I', '1').replace('L', '1');
        if (body.length() != CHARS) throw new IllegalArgumentException("Seeds are " + CHARS + " characters, like K3Q9Z");
        long value = 0;
        for (char c : body.toCharArray()) {
            int digit = ALPHABET.indexOf(c);
            if (digit < 0) throw new IllegalArgumentException("Seeds can't have " + c + " in them");
            value = (value << 5) | digit;
        }
        return new Seed(value);
    }

    public long rngSeed() {
        return value * 0x9E3779B97F4A7C15L + version;
    }

    public String code() {
        char[] out = new char[CHARS];
        long v = value;
        for (int i = CHARS - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (v & 31));
            v >>>= 5;
        }
        return new String(out);
    }

    @Override
    public String toString() {
        return code();
    }
}
