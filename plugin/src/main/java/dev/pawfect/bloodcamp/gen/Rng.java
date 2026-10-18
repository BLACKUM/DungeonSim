package dev.pawfect.bloodcamp.gen;

import java.util.List;

public final class Rng {
    private long state;

    public Rng(long seed) {
        state = seed;
    }

    public long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public int nextInt(int bound) {
        return (int) ((nextLong() >>> 1) % bound);
    }

    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    public boolean chance(double p) {
        return nextDouble() < p;
    }

    public int weighted(double[] weights) {
        double total = 0;
        for (double w : weights) total += w;
        if (total <= 0) return -1;
        double roll = nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0 && weights[i] > 0) return i;
        }
        for (int i = weights.length - 1; i >= 0; i--) if (weights[i] > 0) return i;
        return -1;
    }

    public <T> void shuffle(List<T> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            T t = list.get(i);
            list.set(i, list.get(j));
            list.set(j, t);
        }
    }

    public <T> T pick(List<T> list) {
        return list.get(nextInt(list.size()));
    }
}
