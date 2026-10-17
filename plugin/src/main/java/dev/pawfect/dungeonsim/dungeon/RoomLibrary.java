package dev.pawfect.dungeonsim.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.block.data.BlockData;

final class RoomLibrary {
    static final List<String> POOLS = List.of("1x1", "1x2", "1x3", "1x4", "2x2", "l-shape", "puzzle", "trap", "miniboss", "special");

    record Entry(String id, Path file) {}

    record Secret(String name, String category, int x, int y, int z) {}

    record RoomSecrets(int count, List<Secret> secrets) {}

    private final Path root;
    private final Logger log;
    private final Map<String, List<Entry>> pools = new TreeMap<>();
    private final Map<Path, RoomData> loaded = new ConcurrentHashMap<>();
    private List<BlockData[]> doorFrames = List.of();
    private Map<String, RoomSecrets> secrets = Map.of();

    RoomLibrary(Path root, Logger log) {
        this.root = root;
        this.log = log;
    }

    synchronized void reload() {
        pools.clear();
        loaded.clear();
        Path rooms = root.resolve("rooms");
        for (String pool : POOLS) {
            List<Entry> entries = new ArrayList<>();
            Path dir = rooms.resolve(pool);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.walk(dir)) {
                    files.filter(f -> f.toString().endsWith(".json") && !f.toString().endsWith(".entities.json"))
                        .sorted()
                        .forEach(f -> entries.add(new Entry(idOf(dir, f), f)));
                } catch (IOException e) {
                    log.warning("Couldn't list " + dir + ": " + e.getMessage());
                }
            }
            pools.put(pool, entries);
        }
        doorFrames = loadDoors(root.resolve("doors.json"));
        secrets = loadSecrets(root.resolve("secrets.json"));
    }

    synchronized RoomSecrets secrets(String room) {
        return secrets.get(room);
    }

    private static String idOf(Path poolDir, Path file) {
        Path rel = poolDir.relativize(file);
        String first = rel.getName(0).toString().replace(".json", "");
        return first.replaceAll("-(high|low)$", "");
    }

    synchronized List<Entry> pool(String name) {
        return pools.getOrDefault(name, List.of());
    }

    synchronized List<BlockData[]> doorFrames() {
        return doorFrames;
    }

    synchronized String summary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Entry>> e : pools.entrySet()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(e.getKey()).append(' ').append(e.getValue().size());
        }
        return sb + ", door frames " + doorFrames.size() + ", rooms with secrets " + secrets.size();
    }

    RoomData load(Entry entry) throws IOException {
        RoomData data = loaded.get(entry.file());
        if (data != null) return data;
        data = RoomData.load(entry.file(), entry.id(), log);
        loaded.put(entry.file(), data);
        return data;
    }

    private Map<String, RoomSecrets> loadSecrets(Path file) {
        if (!Files.exists(file)) return Map.of();
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject rooms = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("rooms");
            Map<String, RoomSecrets> out = new java.util.HashMap<>();
            for (var room : rooms.entrySet()) {
                JsonObject json = room.getValue().getAsJsonObject();
                List<Secret> list = new ArrayList<>();
                for (var element : json.getAsJsonArray("secrets")) {
                    JsonObject s = element.getAsJsonObject();
                    list.add(new Secret(s.get("name").getAsString(), s.get("category").getAsString(),
                        s.get("x").getAsInt(), s.get("y").getAsInt(), s.get("z").getAsInt()));
                }
                out.put(room.getKey(), new RoomSecrets(json.get("count").getAsInt(), List.copyOf(list)));
            }
            return out;
        } catch (IOException | RuntimeException e) {
            log.warning("Couldn't read secrets.json: " + e.getMessage());
            return Map.of();
        }
    }

    private List<BlockData[]> loadDoors(Path file) {
        if (!Files.exists(file)) return List.of();
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            List<BlockData[]> out = new ArrayList<>();
            for (var frame : json.getAsJsonArray("frames")) {
                JsonArray states = frame.getAsJsonArray();
                BlockData[] blocks = new BlockData[states.size()];
                for (int i = 0; i < blocks.length; i++) blocks[i] = RoomData.parseState(states.get(i).getAsString(), log);
                out.add(blocks);
            }
            return out;
        } catch (IOException | RuntimeException e) {
            log.warning("Couldn't read doors.json: " + e.getMessage());
            return List.of();
        }
    }
}
