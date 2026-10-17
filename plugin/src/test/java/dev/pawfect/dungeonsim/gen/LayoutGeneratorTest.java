package dev.pawfect.dungeonsim.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pawfect.dungeonsim.gen.Layout.DoorType;
import dev.pawfect.dungeonsim.gen.Layout.Room;
import dev.pawfect.dungeonsim.gen.Layout.RoomType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutGeneratorTest {
    @Test
    void codesRoundTrip() {
        Seed seed = new Seed(Floor.M7, 1, 0x12_3456_789AL);
        assertEquals(seed, Seed.parse(seed.toString()));
        assertEquals(seed, Seed.parse(seed.toString().toLowerCase()));
    }

    @Test
    void sameCodeSameMap() {
        Seed seed = Seed.parse("F7-1-K3Q9ZP2M");
        String first = LayoutGenerator.generate(seed).toLegacyString();
        assertEquals(first, LayoutGenerator.generate(seed).toLegacyString());
        assertEquals(GOLDEN, first);
    }

    private static final String GOLDEN = "070708091010111212121210031313131304140215151617061815151917202005040117900090999990999999990999999219910199199999999091000903990999";

    @Test
    void followsHypixelRules() {
        Rng rng = new Rng(42);
        for (int i = 0; i < 500; i++) {
            Seed seed = Seed.random(i % 2 == 0 ? Floor.F7 : Floor.M7, rng);
            check(LayoutGenerator.generate(seed), seed);
        }
    }

    private static void check(Layout l, Seed seed) {
        String where = seed.toString();
        for (int owner : l.roomOf) assertTrue(owner >= 0, where + ": empty cell");
        assertEquals(l.rooms.size() - 1, l.doors.size(), where + ": doors must form a tree");

        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < l.rooms.size(); i++) adj.add(new ArrayList<>());
        for (Layout.Door d : l.doors) {
            adj.get(d.roomA()).add(d.roomB());
            adj.get(d.roomB()).add(d.roomA());
        }
        int entrance = find(l, RoomType.ENTRANCE), blood = find(l, RoomType.BLOOD), fairy = find(l, RoomType.FAIRY);
        int[] parent = new int[l.rooms.size()];
        java.util.Arrays.fill(parent, -2);
        parent[entrance] = -1;
        ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(entrance));
        while (!queue.isEmpty()) {
            int r = queue.poll();
            for (int o : adj.get(r)) if (parent[o] == -2) { parent[o] = r; queue.add(o); }
        }
        for (int p : parent) assertTrue(p != -2, where + ": unreachable room");

        int puzzles = 0, traps = 0, minis = 0;
        for (Room r : l.rooms) {
            switch (r.type()) {
                case PUZZLE -> puzzles++;
                case TRAP -> traps++;
                case MINIBOSS -> minis++;
                default -> {}
            }
            if (r.type() != RoomType.NORMAL && r.type() != RoomType.FAIRY) {
                assertEquals(1, adj.get(r.id()).size(), where + ": " + r.type() + " needs exactly one door");
            }
        }
        assertTrue(puzzles >= 2 && puzzles <= 5, where + ": puzzles " + puzzles);
        assertEquals(1, traps, where);
        assertEquals(1, minis, where);

        int ec = l.rooms.get(entrance).cells()[0];
        int ex = ec % l.width, ez = ec / l.width;
        boolean edge = ex == 0 || ez == 0 || ex == l.width - 1 || ez == l.height - 1;
        boolean corner = (ex == 0 || ex == l.width - 1) && (ez == 0 || ez == l.height - 1);
        assertTrue(edge && !corner, where + ": entrance must be on an edge, not a corner");

        boolean sawFairy = false;
        for (int r = blood; parent[r] != -1; r = parent[r]) {
            if (r == fairy) sawFairy = true;
        }
        assertTrue(sawFairy, where + ": fairy must be on the blood path");
        for (Layout.Door d : l.doors) {
            boolean onPath = isOnPath(d, blood, parent);
            if (d.type() == DoorType.WITHER) assertTrue(onPath, where + ": wither door off the path");
            if (d.type() == DoorType.BLOOD) assertTrue(d.roomA() == blood || d.roomB() == blood, where);
        }
    }

    private static boolean isOnPath(Layout.Door d, int blood, int[] parent) {
        for (int r = blood; parent[r] != -1; r = parent[r]) {
            if ((d.roomA() == r && d.roomB() == parent[r]) || (d.roomB() == r && d.roomA() == parent[r])) return true;
        }
        return false;
    }

    private static int find(Layout l, RoomType type) {
        for (Room r : l.rooms) if (r.type() == type) return r.id();
        throw new AssertionError("no " + type);
    }
}
