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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;

final class RoomData {
    record Head(int rx, int y, int rz, HeadProfile profile) {}

    private static final Pattern PROFILE_ID = Pattern.compile("id:\\[I;(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern TEXTURE = Pattern.compile("value:\"([^\"]+)\"");
    private static final Pattern SIGNATURE = Pattern.compile("signature:\"([^\"]+)\"");

    final String id;
    final String name;
    final String room;
    final List<Head> heads;
    final int ox, oy, oz;
    final int sx, sy, sz;
    final int[] data;
    volatile int openMask = -1;
    private final BlockData[][] palette;
    private final int air;

    private RoomData(String id, String name, String room, List<Head> heads, int[] origin, int[] size, BlockData[] base, int[] data) {
        this.id = id;
        this.name = name;
        this.room = room;
        this.heads = heads;
        this.ox = origin[0];
        this.oy = origin[1];
        this.oz = origin[2];
        this.sx = size[0];
        this.sy = size[1];
        this.sz = size[2];
        this.data = data;
        this.palette = new BlockData[4][];
        this.palette[0] = base;
        int airIndex = -1;
        for (int i = 0; i < base.length; i++) if (base[i].getMaterial() == Material.AIR) airIndex = i;
        this.air = airIndex;
    }

    static RoomData load(Path file, String id, Logger log) throws IOException {
        JsonObject json;
        try (Reader reader = Files.newBufferedReader(file)) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonArray rawPalette = json.getAsJsonArray("palette");
        BlockData[] palette = new BlockData[rawPalette.size()];
        for (int i = 0; i < palette.length; i++) palette[i] = parseState(rawPalette.get(i).getAsString(), log);
        JsonArray rawData = json.getAsJsonArray("data");
        int[] data = new int[rawData.size()];
        for (int i = 0; i < data.length; i++) data[i] = rawData.get(i).getAsInt();
        String name = json.has("name") && !json.get("name").isJsonNull() ? json.get("name").getAsString() : id;
        String room = json.has("room") && !json.get("room").isJsonNull() ? json.get("room").getAsString() : id;
        return new RoomData(id, name, room, heads(json), ints(json.getAsJsonArray("origin")), ints(json.getAsJsonArray("size")), palette, data);
    }

    private static List<Head> heads(JsonObject json) {
        List<Head> out = new ArrayList<>();
        if (!json.has("blockEntities")) return out;
        for (var element : json.getAsJsonArray("blockEntities")) {
            JsonObject entity = element.getAsJsonObject();
            String nbt = entity.get("nbt").getAsString();
            Matcher texture = TEXTURE.matcher(nbt);
            if (!nbt.contains("profile:") || !texture.find()) continue;
            UUID uuid = null;
            Matcher id = PROFILE_ID.matcher(nbt);
            if (id.find()) {
                long most = ((long) Integer.parseInt(id.group(1)) << 32) | (Integer.parseInt(id.group(2)) & 0xFFFFFFFFL);
                long least = ((long) Integer.parseInt(id.group(3)) << 32) | (Integer.parseInt(id.group(4)) & 0xFFFFFFFFL);
                uuid = new UUID(most, least);
            }
            Matcher signature = SIGNATURE.matcher(nbt);
            int[] pos = ints(entity.getAsJsonArray("pos"));
            out.add(new Head(pos[0], pos[1], pos[2], new HeadProfile(uuid, texture.group(1), signature.find() ? signature.group(1) : null)));
        }
        return out;
    }

    static BlockData parseState(String state, Logger log) {
        try {
            return Bukkit.createBlockData(state);
        } catch (IllegalArgumentException e) {
            int bracket = state.indexOf('[');
            if (bracket > 0) {
                try {
                    return Bukkit.createBlockData(state.substring(0, bracket));
                } catch (IllegalArgumentException ignored) {
                }
            }
            log.warning("Unknown block state " + state + ", using air");
            return Material.AIR.createBlockData();
        }
    }

    private static int[] ints(JsonArray array) {
        int[] out = new int[array.size()];
        for (int i = 0; i < out.length; i++) out[i] = array.get(i).getAsInt();
        return out;
    }

    BlockData at(int rx, int y, int rz, int turns) {
        int x = rx - ox, dy = y - oy, z = rz - oz;
        if (x < 0 || dy < 0 || z < 0 || x >= sx || dy >= sy || z >= sz) return null;
        int index = data[(dy * sz + z) * sx + x];
        if (index == air) return null;
        return state(index, turns);
    }

    boolean isAir(int index) {
        return index == air;
    }

    synchronized BlockData state(int index, int turns) {
        BlockData[] turned = palette[turns];
        if (turned == null) {
            turned = new BlockData[palette[0].length];
            StructureRotation rotation = switch (turns) {
                case 1 -> StructureRotation.CLOCKWISE_90;
                case 2 -> StructureRotation.CLOCKWISE_180;
                default -> StructureRotation.COUNTERCLOCKWISE_90;
            };
            for (int i = 0; i < turned.length; i++) {
                BlockData copy = palette[0][i].clone();
                copy.rotate(rotation);
                turned[i] = copy;
            }
            palette[turns] = turned;
        }
        return turned[index];
    }
}
