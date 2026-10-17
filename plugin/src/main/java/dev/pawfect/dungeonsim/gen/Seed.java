package dev.pawfect.dungeonsim.gen;

import java.util.Locale;

public record Seed(Floor floor, int version, long value) {
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int CHARS = 8;
    public static final long MASK = (1L << (CHARS * 5)) - 1;

    public Seed {
        value &= MASK;
    }

    public static Seed random(Floor floor, Rng rng) {
        return new Seed(floor, LayoutGenerator.VERSION, rng.nextLong());
    }

    public static Seed parse(String code) {
        String[] parts = code.trim().toUpperCase(Locale.ROOT).split("-");
        if (parts.length != 3) throw new IllegalArgumentException("Map codes look like F7-1-K3Q9ZP2M");
        Floor floor = Floor.parse(parts[0]);
        int version;
        try {
            version = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad generator version: " + parts[1]);
        }
        String body = parts[2].replace('O', '0').replace('I', '1').replace('L', '1');
        if (body.length() != CHARS) throw new IllegalArgumentException("The last part should be " + CHARS + " characters");
        long value = 0;
        for (char c : body.toCharArray()) {
            int digit = ALPHABET.indexOf(c);
            if (digit < 0) throw new IllegalArgumentException("Bad character in map code: " + c);
            value = (value << 5) | digit;
        }
        return new Seed(floor, version, value);
    }

    public long rngSeed() {
        return value * 31 + floor.ordinal() * 0x632BE59BD9B4E019L + version;
    }

    @Override
    public String toString() {
        char[] out = new char[CHARS];
        long v = value;
        for (int i = CHARS - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (v & 31));
            v >>>= 5;
        }
        return floor.name() + "-" + version + "-" + new String(out);
    }
}
