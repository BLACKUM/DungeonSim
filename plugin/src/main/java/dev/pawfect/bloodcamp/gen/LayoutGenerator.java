package dev.pawfect.bloodcamp.gen;

import dev.pawfect.bloodcamp.gen.Layout.DoorType;
import dev.pawfect.bloodcamp.gen.Layout.RoomType;
import dev.pawfect.bloodcamp.gen.Layout.Shape;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LayoutGenerator {
    public static final int VERSION = 1;
    private static final int MAX_ATTEMPTS = 20000;
    private static final int ATTEMPTS_PER_PLAN = 2000;

    private static final double[] ENTRANCE_6X6 = {
        0, 356, 221, 205, 340, 0,
        130, 0, 0, 0, 0, 105,
        72, 0, 0, 0, 0, 76,
        77, 0, 0, 0, 0, 64,
        135, 0, 0, 0, 0, 105,
        0, 391, 193, 186, 346, 0,
    };

    private static final String RECIPES = "312121:206,322021:195,221112:186,321112:174,212121:157,412121:148,"
        + "222021:136,311212:121,421112:111,422021:102,211212:89,512121:81,402221:79,302221:75,202221:70,212202:64,"
        + "411212:62,320122:56,220122:53,312202:51,522021:50,322102:48,222102:46,412202:41,521112:35,511212:33,"
        + "502221:32,222111:29,321121:29,422102:29,221121:27,322111:27,512202:27,310222:24,212211:23,420122:23,"
        + "312211:22,520122:21,311221:18,522102:18,211221:16,210222:14,421121:14,410222:12,422111:11,512211:9,"
        + "220212:8,221202:7,321202:7,411221:7,320212:6,321210:6,412211:6,510222:6,521121:6,421202:5,521202:4,"
        + "220220:3,221210:3,221211:3,222201:3,420212:3,511221:3,222120:2,320221:2,321211:2,421210:2,422201:2,"
        + "212220:1,220221:1,322201:1,420221:1,421211:1,422120:1,422200:1,520212:1,520220:1,522111:1,522120:1,"
        + "522201:1";
    private static final int[][] RECIPE_ROWS;
    private static final double[] RECIPE_W;

    static {
        String[] entries = RECIPES.split(",");
        RECIPE_ROWS = new int[entries.length][];
        RECIPE_W = new double[entries.length];
        for (int i = 0; i < entries.length; i++) {
            String[] kv = entries[i].split(":");
            RECIPE_ROWS[i] = kv[0].chars().map(ch -> ch - '0').toArray();
            RECIPE_W[i] = Double.parseDouble(kv[1]);
        }
    }

    private static final double[] PATH_SPLIT = {
        29, 24, 12,
        446, 350, 255,
        579, 408, 248,
        287, 201, 76,
        53, 25, 9,
    };
    private static final int PATH_SEARCH_BUDGET = 4000;
    private static final double[] BLOOD_DIST = {0, 47, 55, 142, 214, 489, 427, 669, 513, 614, 0, 0};
    private static final double BLOOD_INNER = 0.3;
    private static final double[] FAIRY_DETOUR = {1, 0, 0.7, 0, 0.3, 0, 0.1};
    private static final Shape[] MULTI = {Shape.S2X2, Shape.L, Shape.S1X4, Shape.S1X3, Shape.S1X2};
    private static final double[] L_MISSING_W = {616, 1246, 1362, 1339};
    private static final double SPECIAL_CORNER = 2.0;
    private static final double TOWARD_TARGET = 0.6;
    private static final double GROW_NEWEST = 0.3;
    private static final double GROW_DEPTH_DECAY = 0.15;
    private static final double ATTACH_TO_BIG_LEAF = 0.5;
    private static final double FAIRY_EDGE = 2.0;
    private static final double FAIRY_GROW = 10.0;
    private static final double ATTACH_TO_LEAF = 2.0;
    private static final double ATTACH_SIBLING = 1.0;
    private static final double FAIRY_NORMAL_DOOR = 0.052;
    private static final double KEEP_NORMAL_LEAF = 0.005;

    private LayoutGenerator() {}

    public static Layout generate(Seed seed) {
        Floor floor = seed.floor();
        if (!floor.supported()) throw new IllegalArgumentException(floor + " isn't on Bloodcamp, only F7");
        if (seed.version() != VERSION) {
            throw new IllegalArgumentException("That code is from generator v" + seed.version() + ", this server runs v" + VERSION);
        }
        Rng rng = new Rng(seed.rngSeed());
        Plan plan = null;
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            if (i % ATTEMPTS_PER_PLAN == 0) plan = new Plan(RECIPE_ROWS[rng.weighted(RECIPE_W)], rng.weighted(PATH_SPLIT));
            Layout layout = new Attempt(seed, rng, plan).run();
            if (layout != null) return layout;
        }
        throw new IllegalStateException("No valid layout for " + seed + " after " + MAX_ATTEMPTS + " attempts");
    }

    private record Plan(int[] recipe, int pathSplit) {
        int puzzles() { return recipe[0]; }
        int copies(int shape) { return recipe[1 + shape]; }
        int toFairy() { return 2 + pathSplit / 3; }
        int toBlood() { return 2 + pathSplit % 3; }
    }

    private static final class RoomB {
        final int id;
        RoomType type;
        final Shape shape;
        final List<Integer> cells = new ArrayList<>();

        RoomB(int id, RoomType type, Shape shape) {
            this.id = id;
            this.type = type;
            this.shape = shape;
        }

        boolean core() {
            return type == RoomType.NORMAL || type == RoomType.FAIRY;
        }
    }

    private static final class Attempt {
        final Seed seed;
        final Rng rng;
        final Plan plan;
        final int w, h, n;
        int searchBudget;
        final int[] roomOf;
        final List<RoomB> rooms = new ArrayList<>();
        final Map<Integer, List<int[]>> walls = new HashMap<>();
        final List<Set<Integer>> adj = new ArrayList<>();
        final List<int[]> treeEdges = new ArrayList<>();
        final int[] degree;
        final Set<Integer> pathEdges = new HashSet<>();

        Attempt(Seed seed, Rng rng, Plan plan) {
            this.seed = seed;
            this.rng = rng;
            this.plan = plan;
            this.w = seed.floor().width;
            this.h = seed.floor().height;
            this.n = w * h;
            this.roomOf = new int[n];
            java.util.Arrays.fill(roomOf, -1);
            this.degree = new int[n];
        }

        int x(int c) { return c % w; }
        int z(int c) { return c / w; }
        int dist(int a, int b) { return Math.abs(x(a) - x(b)) + Math.abs(z(a) - z(b)); }
        boolean onEdge(int c) { return x(c) == 0 || z(c) == 0 || x(c) == w - 1 || z(c) == h - 1; }
        boolean corner(int c) { return (x(c) == 0 || x(c) == w - 1) && (z(c) == 0 || z(c) == h - 1); }

        List<Integer> neighbours(int c) {
            List<Integer> out = new ArrayList<>(4);
            if (z(c) > 0) out.add(c - w);
            if (x(c) < w - 1) out.add(c + 1);
            if (z(c) < h - 1) out.add(c + w);
            if (x(c) > 0) out.add(c - 1);
            return out;
        }

        RoomB addRoom(RoomType type, Shape shape, int... cells) {
            RoomB r = new RoomB(rooms.size(), type, shape);
            for (int c : cells) {
                r.cells.add(c);
                roomOf[c] = r.id;
            }
            rooms.add(r);
            return r;
        }

        Layout run() {
            int entrance = rng.weighted(ENTRANCE_6X6);
            int inward = inwardOf(entrance);
            RoomB entranceRoom = addRoom(RoomType.ENTRANCE, Shape.S1X1, entrance);

            int puzzles = plan.puzzles();

            int[] perDist = new int[BLOOD_DIST.length];
            for (int c = 0; c < n; c++) if (c != entrance && c != inward) perDist[dist(entrance, c)]++;
            double[] bw = new double[n];
            for (int c = 0; c < n; c++) {
                if (c == entrance || c == inward) continue;
                int d = dist(entrance, c);
                bw[c] = BLOOD_DIST[d] / perDist[d] * (onEdge(c) ? 1 : BLOOD_INNER);
            }
            int blood = rng.weighted(bw);
            RoomB bloodRoom = addRoom(RoomType.BLOOD, Shape.S1X1, blood);

            double[] fw = new double[n];
            int straight = dist(entrance, blood);
            for (int c = 0; c < n; c++) {
                if (roomOf[c] != -1 || c == inward) continue;
                int detour = dist(entrance, c) + dist(c, blood) - straight;
                if (detour < FAIRY_DETOUR.length) fw[c] = FAIRY_DETOUR[detour] * (onEdge(c) ? FAIRY_EDGE : 1);
            }
            int fairy = rng.weighted(fw);
            if (fairy < 0) return null;
            RoomB fairyRoom = addRoom(RoomType.FAIRY, Shape.S1X1, fairy);

            if (!placeMultiRooms()) return null;

            List<Integer> free = new ArrayList<>();
            for (int c = 0; c < n; c++) if (roomOf[c] == -1 && c != inward) free.add(c);
            int specials = puzzles + 2;
            if (free.size() < specials) return null;
            List<RoomType> types = new ArrayList<>();
            for (int i = 0; i < puzzles; i++) types.add(RoomType.PUZZLE);
            types.add(RoomType.TRAP);
            types.add(RoomType.MINIBOSS);
            rng.shuffle(types);
            List<RoomB> leafRooms = new ArrayList<>();
            for (RoomType t : types) {
                double[] sw = new double[free.size()];
                for (int i = 0; i < sw.length; i++) sw[i] = corner(free.get(i)) ? SPECIAL_CORNER : 1;
                int cell = free.remove(rng.weighted(sw));
                leafRooms.add(addRoom(t, Shape.S1X1, cell));
            }
            for (int c = 0; c < n; c++) if (roomOf[c] == -1) addRoom(RoomType.NORMAL, Shape.S1X1, c);

            buildAdjacency();
            int first = roomOf[inward];
            if (rooms.get(first).type != RoomType.NORMAL) return null;

            Set<Integer> bloodSide = new HashSet<>();
            for (int r : adj.get(bloodRoom.id)) if (rooms.get(r).type == RoomType.NORMAL) bloodSide.add(r);
            List<Integer> toFairy = pathOfLength(first, Set.of(fairyRoom.id), Set.of(), plan.toFairy() - 1, fairy);
            if (toFairy == null) return null;
            Set<Integer> used = new HashSet<>(toFairy);
            used.remove(fairyRoom.id);
            List<Integer> toBlood = pathOfLength(fairyRoom.id, bloodSide, used, plan.toBlood() - 1, blood);
            if (toBlood == null) return null;

            List<Integer> path = new ArrayList<>(toFairy);
            path.addAll(toBlood.subList(1, toBlood.size()));
            boolean[] inTree = new boolean[rooms.size()];
            inTree[entranceRoom.id] = true;
            connect(entranceRoom.id, first, true);
            for (int i = 0; i < path.size(); i++) {
                inTree[path.get(i)] = true;
                if (i > 0) connect(path.get(i - 1), path.get(i), true);
            }
            connect(path.get(path.size() - 1), bloodRoom.id, true);
            inTree[bloodRoom.id] = true;

            int[] depth = new int[rooms.size()];
            for (int i = 0; i < path.size(); i++) depth[path.get(i)] = i + 1;
            List<Integer> active = new ArrayList<>(path);
            rng.shuffle(active);
            while (!active.isEmpty()) {
                int idx;
                if (rng.chance(GROW_NEWEST)) {
                    idx = active.size() - 1;
                } else {
                    double[] aw = new double[active.size()];
                    for (int i = 0; i < aw.length; i++) {
                        int a = active.get(i);
                        aw[i] = Math.pow(GROW_DEPTH_DECAY, depth[a]) * (a == fairyRoom.id ? FAIRY_GROW : 1);
                    }
                    idx = rng.weighted(aw);
                }
                int r = active.get(idx);
                List<Integer> options = new ArrayList<>();
                for (int o : adj.get(r)) if (!inTree[o] && rooms.get(o).core()) options.add(o);
                if (options.isEmpty()) {
                    active.remove(idx);
                    continue;
                }
                int next = rng.pick(options);
                inTree[next] = true;
                depth[next] = depth[r] + 1;
                connect(r, next, false);
                active.add(next);
            }
            for (RoomB r : rooms) if (r.core() && !inTree[r.id]) return null;

            rng.shuffle(leafRooms);
            int[] specialKids = new int[rooms.size()];
            for (RoomB s : leafRooms) {
                List<Integer> options = new ArrayList<>();
                for (int o : adj.get(s.id)) if (rooms.get(o).core()) options.add(o);
                if (options.isEmpty()) return null;
                double[] ow = new double[options.size()];
                for (int i = 0; i < ow.length; i++) {
                    RoomB o = rooms.get(options.get(i));
                    double wgt = 1;
                    if (o.type == RoomType.NORMAL && degree[o.id] == 1) wgt = o.shape == Shape.S1X1 ? ATTACH_TO_LEAF : ATTACH_TO_BIG_LEAF;
                    ow[i] = wgt * Math.pow(ATTACH_SIBLING, specialKids[o.id]);
                }
                int parent = options.get(rng.weighted(ow));
                specialKids[parent]++;
                connect(parent, s.id, false);
            }

            for (RoomB r : rooms) {
                if (r.type == RoomType.NORMAL && r.shape == Shape.S1X1 && degree[r.id] == 1 && !rng.chance(KEEP_NORMAL_LEAF)) return null;
            }

            return build(entranceRoom.id, bloodRoom.id, fairyRoom.id);
        }

        int inwardOf(int c) {
            if (z(c) == 0) return c + w;
            if (z(c) == h - 1) return c - w;
            if (x(c) == 0) return c + 1;
            return c - 1;
        }

        boolean placeMultiRooms() {
            List<Shape> toPlace = new ArrayList<>();
            for (int i = 0; i < MULTI.length; i++) for (int j = 0; j < plan.copies(i); j++) toPlace.add(MULTI[i]);
            rng.shuffle(toPlace);
            toPlace.sort((a, b) -> Integer.compare(cellCount(b), cellCount(a)));
            for (Shape s : toPlace) {
                List<int[]> options = new ArrayList<>();
                List<Double> ow = new ArrayList<>();
                placements(s, options, ow);
                if (options.isEmpty()) return false;
                double[] arr = ow.stream().mapToDouble(Double::doubleValue).toArray();
                addRoom(RoomType.NORMAL, s, options.get(rng.weighted(arr)));
            }
            return true;
        }

        int cellCount(Shape s) {
            return switch (s) {
                case S1X1 -> 1;
                case S1X2 -> 2;
                case S1X3, L -> 3;
                case S1X4, S2X2 -> 4;
            };
        }

        void placements(Shape s, List<int[]> out, List<Double> weights) {
            List<int[][]> variants = new ArrayList<>();
            List<Double> vw = new ArrayList<>();
            switch (s) {
                case S2X2 -> { variants.add(new int[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}}); vw.add(1.0); }
                case L -> {
                    int[][] box = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
                    for (int miss = 0; miss < 4; miss++) {
                        int[][] v = new int[3][];
                        int j = 0;
                        for (int i = 0; i < 4; i++) if (i != miss) v[j++] = box[i];
                        variants.add(v);
                        vw.add(L_MISSING_W[miss]);
                    }
                }
                default -> {
                    int len = cellCount(s);
                    int[][] horiz = new int[len][];
                    int[][] vert = new int[len][];
                    for (int i = 0; i < len; i++) {
                        horiz[i] = new int[]{i, 0};
                        vert[i] = new int[]{0, i};
                    }
                    variants.add(horiz);
                    vw.add(1.0);
                    variants.add(vert);
                    vw.add(1.0);
                }
            }
            for (int v = 0; v < variants.size(); v++) {
                int[][] offs = variants.get(v);
                for (int z0 = 0; z0 < h; z0++) {
                    for (int x0 = 0; x0 < w; x0++) {
                        int[] cells = new int[offs.length];
                        boolean ok = true;
                        for (int i = 0; i < offs.length && ok; i++) {
                            int cx = x0 + offs[i][0], cz = z0 + offs[i][1];
                            if (cx >= w || cz >= h || roomOf[cz * w + cx] != -1) ok = false;
                            else cells[i] = cz * w + cx;
                        }
                        if (ok) {
                            out.add(cells);
                            weights.add(vw.get(v));
                        }
                    }
                }
            }
        }

        void buildAdjacency() {
            for (int i = 0; i < rooms.size(); i++) adj.add(new HashSet<>());
            int entranceCell = rooms.get(0).cells.get(0);
            for (int c = 0; c < n; c++) {
                for (int o : neighbours(c)) {
                    if (o < c) continue;
                    int a = roomOf[c], b = roomOf[o];
                    if (a == b) continue;
                    if ((c == entranceCell && o != inwardOf(c)) || (o == entranceCell && c != inwardOf(o))) continue;
                    adj.get(a).add(b);
                    adj.get(b).add(a);
                    walls.computeIfAbsent(key(a, b), x -> new ArrayList<>()).add(new int[]{c, o});
                }
            }
        }

        int key(int a, int b) {
            return Math.min(a, b) * 64 + Math.max(a, b);
        }

        int roomDist(int room, int cell) {
            int best = Integer.MAX_VALUE;
            for (int c : rooms.get(room).cells) best = Math.min(best, dist(c, cell));
            return best;
        }

        List<Integer> pathOfLength(int start, Set<Integer> targets, Set<Integer> blocked, int doors, int goalCell) {
            List<Integer> path = new ArrayList<>();
            path.add(start);
            Set<Integer> onPath = new HashSet<>(blocked);
            onPath.add(start);
            searchBudget = PATH_SEARCH_BUDGET;
            return extend(path, onPath, targets, doors, goalCell) ? path : null;
        }

        boolean extend(List<Integer> path, Set<Integer> onPath, Set<Integer> targets, int left, int goalCell) {
            int cur = path.get(path.size() - 1);
            if (left == 0) return targets.contains(cur);
            if (targets.contains(cur) || --searchBudget < 0) return false;
            List<Integer> options = new ArrayList<>();
            for (int o : adj.get(cur)) {
                if (onPath.contains(o)) continue;
                RoomType t = rooms.get(o).type;
                if (t == RoomType.NORMAL || targets.contains(o)) options.add(o);
            }
            rng.shuffle(options);
            if (rng.chance(TOWARD_TARGET)) options.sort((a, b) -> Integer.compare(roomDist(a, goalCell), roomDist(b, goalCell)));
            for (int o : options) {
                path.add(o);
                onPath.add(o);
                if (extend(path, onPath, targets, left - 1, goalCell)) return true;
                path.remove(path.size() - 1);
                onPath.remove(o);
            }
            return false;
        }

        void connect(int a, int b, boolean onPath) {
            treeEdges.add(new int[]{a, b});
            degree[a]++;
            degree[b]++;
            if (onPath) pathEdges.add(key(a, b));
        }

        Layout build(int entranceId, int bloodId, int fairyId) {
            List<Layout.Room> outRooms = new ArrayList<>();
            for (RoomB r : rooms) {
                outRooms.add(new Layout.Room(r.id, r.type, r.shape, r.cells.stream().mapToInt(Integer::intValue).toArray()));
            }
            List<Layout.Door> doors = new ArrayList<>();
            for (int[] e : treeEdges) {
                int a = e[0], b = e[1];
                List<int[]> options = walls.get(key(a, b));
                int[] pair = rng.pick(options);
                DoorType type;
                if (a == entranceId || b == entranceId) type = DoorType.ENTRANCE;
                else if (a == bloodId || b == bloodId) type = DoorType.BLOOD;
                else if (pathEdges.contains(key(a, b))) {
                    boolean intoFairy = b == fairyId;
                    type = intoFairy && rng.chance(FAIRY_NORMAL_DOOR) ? DoorType.NORMAL : DoorType.WITHER;
                } else type = DoorType.NORMAL;
                doors.add(new Layout.Door(pair[0], pair[1], roomOf[pair[0]], roomOf[pair[1]], type));
            }
            return new Layout(seed, w, h, roomOf.clone(), outRooms, doors);
        }
    }
}
