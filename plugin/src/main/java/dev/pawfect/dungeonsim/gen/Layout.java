package dev.pawfect.dungeonsim.gen;

import java.util.ArrayList;
import java.util.List;

public final class Layout {
    public enum RoomType { ENTRANCE, FAIRY, BLOOD, PUZZLE, TRAP, MINIBOSS, NORMAL }

    public enum Shape {
        S1X1("1x1"), S1X2("1x2"), S1X3("1x3"), S1X4("1x4"), S2X2("2x2"), L("L");

        public final String label;

        Shape(String label) {
            this.label = label;
        }
    }

    public enum DoorType { NORMAL, WITHER, BLOOD, ENTRANCE }

    public record Room(int id, RoomType type, Shape shape, int[] cells) {}

    public record Door(int cellA, int cellB, int roomA, int roomB, DoorType type) {}

    public final Seed seed;
    public final int width;
    public final int height;
    public final int[] roomOf;
    public final List<Room> rooms;
    public final List<Door> doors;

    Layout(Seed seed, int width, int height, int[] roomOf, List<Room> rooms, List<Door> doors) {
        this.seed = seed;
        this.width = width;
        this.height = height;
        this.roomOf = roomOf;
        this.rooms = List.copyOf(rooms);
        this.doors = List.copyOf(doors);
    }

    public Door doorBetween(int cellA, int cellB) {
        for (Door d : doors) {
            if ((d.cellA == cellA && d.cellB == cellB) || (d.cellA == cellB && d.cellB == cellA)) return d;
        }
        return null;
    }

    public String toLegacyString() {
        if (width != 6 || height != 6) throw new IllegalStateException("Legacy format is 6x6 only");
        int[] legacyId = new int[rooms.size()];
        int next = 7;
        for (int c = 0; c < roomOf.length; c++) {
            Room r = rooms.get(roomOf[c]);
            if (legacyId[r.id] != 0) continue;
            legacyId[r.id] = switch (r.type) {
                case ENTRANCE -> 1;
                case FAIRY -> 2;
                case BLOOD -> 3;
                case PUZZLE -> 4;
                case TRAP -> 5;
                case MINIBOSS -> 6;
                case NORMAL -> next++;
            };
        }
        StringBuilder sb = new StringBuilder(132);
        for (int c = 0; c < roomOf.length; c++) {
            int id = legacyId[roomOf[c]];
            if (id < 10) sb.append('0');
            sb.append(id);
        }
        for (int z2 = 0; z2 < 11; z2++) {
            for (int x2 = 0; x2 < 11; x2++) {
                if ((x2 + z2) % 2 == 0) continue;
                int a, b;
                if (x2 % 2 == 1) {
                    a = (z2 / 2) * 6 + (x2 - 1) / 2;
                    b = a + 1;
                } else {
                    a = ((z2 - 1) / 2) * 6 + x2 / 2;
                    b = a + 6;
                }
                Door d = doorBetween(a, b);
                sb.append(d == null ? '9' : (char) ('0' + d.type.ordinal()));
            }
        }
        return sb.toString();
    }

    public List<String> asciiMap() {
        char[] glyph = new char[rooms.size()];
        char nextNormal = 'a';
        for (Room r : rooms) {
            glyph[r.id] = switch (r.type) {
                case ENTRANCE -> 'E';
                case FAIRY -> 'F';
                case BLOOD -> 'B';
                case PUZZLE -> 'P';
                case TRAP -> 'T';
                case MINIBOSS -> 'M';
                case NORMAL -> nextNormal++;
            };
        }
        List<String> lines = new ArrayList<>();
        for (int z = 0; z < height; z++) {
            StringBuilder row = new StringBuilder();
            StringBuilder below = new StringBuilder();
            for (int x = 0; x < width; x++) {
                int c = z * width + x;
                row.append(glyph[roomOf[c]]);
                if (x + 1 < width) row.append(link(c, c + 1, true));
                if (z + 1 < height) {
                    below.append(link(c, c + width, false));
                    if (x + 1 < width) below.append(' ');
                }
            }
            lines.add(row.toString());
            if (z + 1 < height) lines.add(below.toString());
        }
        return lines;
    }

    private char link(int a, int b, boolean horizontal) {
        if (roomOf[a] == roomOf[b]) return '.';
        Door d = doorBetween(a, b);
        if (d == null) return ' ';
        return switch (d.type) {
            case NORMAL -> horizontal ? '-' : '|';
            case WITHER -> horizontal ? '=' : 'H';
            case BLOOD -> '#';
            case ENTRANCE -> '+';
        };
    }
}
