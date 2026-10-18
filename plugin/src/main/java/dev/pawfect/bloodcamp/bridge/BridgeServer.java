package dev.pawfect.bloodcamp.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

public final class BridgeServer {
    private static final int MAX_BODY = 16 * 1024 * 1024;
    private static final int MAX_VOLUME = 262_144;
    private static final int MAX_ENTITY_RADIUS = 128;
    private static final int MAX_ENTITIES = 500;
    private static final Pattern FILE_PATH = Pattern.compile("[a-z0-9][a-z0-9_./-]*\\.json");
    private static final Gson GSON = new Gson();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @FunctionalInterface
    private interface Handler {
        JsonObject handle(JsonObject request) throws Exception;
    }

    private static final class BadRequest extends Exception {
        BadRequest(String message) {
            super(message);
        }
    }

    private final Plugin plugin;
    private final ConsoleBuffer console;
    private final Auth auth;
    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, Handler> routes;
    private final long startedAt = System.currentTimeMillis();

    public BridgeServer(Plugin plugin, ConsoleBuffer console, Auth auth, String host, int port) throws IOException {
        this.plugin = plugin;
        this.console = console;
        this.auth = auth;
        this.routes = Map.of(
                "/status", this::status,
                "/command", this::command,
                "/console", this::console,
                "/players", this::players,
                "/entities", this::entities,
                "/blocks/get", this::getBlocks,
                "/blocks/paste", this::pasteBlocks,
                "/blocks/set", this::setBlocks,
                "/files/put", this::putFile,
                "/plugin/stage", this::stagePlugin);
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "Bloodcamp-Bridge");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::serve);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void serve(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("POST only"));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                send(exchange, 413, error("Body too large"));
                return;
            }

            String path = exchange.getRequestURI().getRawPath();
            Headers headers = exchange.getRequestHeaders();
            if (!auth.verify("POST", path, headers.getFirst("X-DS-Time"), headers.getFirst("X-DS-Nonce"),
                    headers.getFirst("X-DS-Signature"), body)) {
                send(exchange, 401, error("Bad signature"));
                return;
            }

            Handler handler = routes.get(path);
            if (handler == null) {
                send(exchange, 404, error("Unknown route " + path));
                return;
            }

            JsonObject request;
            try {
                request = body.length == 0 ? new JsonObject()
                        : JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException e) {
                send(exchange, 400, error("Body must be a JSON object"));
                return;
            }

            JsonObject response = handler.handle(request);
            response.addProperty("ok", true);
            send(exchange, 200, response);
        } catch (BadRequest e) {
            send(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            plugin.getLogger().warning("Bridge request failed: " + e);
            send(exchange, 500, error(e.toString()));
        } finally {
            exchange.close();
        }
    }

    private JsonObject status(JsonObject request) throws Exception {
        return sync(() -> {
            JsonObject out = new JsonObject();
            out.addProperty("server", Bukkit.getVersion());
            out.addProperty("minecraft", Bukkit.getMinecraftVersion());
            out.addProperty("plugin", plugin.getPluginMeta().getVersion());
            JsonArray tps = new JsonArray();
            for (double value : Bukkit.getTPS()) tps.add(Math.round(value * 100) / 100.0);
            out.add("tps", tps);
            out.addProperty("online", Bukkit.getOnlinePlayers().size());
            out.addProperty("maxPlayers", Bukkit.getMaxPlayers());
            JsonArray worlds = new JsonArray();
            for (World world : Bukkit.getWorlds()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", world.getName());
                entry.addProperty("environment", world.getEnvironment().name());
                entry.addProperty("players", world.getPlayers().size());
                entry.add("spawn", vec(world.getSpawnLocation()));
                entry.addProperty("minY", world.getMinHeight());
                entry.addProperty("maxY", world.getMaxHeight() - 1);
                worlds.add(entry);
            }
            out.add("worlds", worlds);
            out.addProperty("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000);
            return out;
        });
    }

    private JsonObject putFile(JsonObject request) throws Exception {
        String path = string(request, "path");
        if (!FILE_PATH.matcher(path).matches() || path.contains("..")) throw new BadRequest("Bad path: " + path);
        byte[] data;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(string(request, "gzip"))))) {
            data = in.readAllBytes();
        } catch (IllegalArgumentException | IOException e) {
            throw new BadRequest("gzip must be base64 gzip data");
        }
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root)) throw new BadRequest("Bad path: " + path);
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        Files.write(temp, data);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        JsonObject out = new JsonObject();
        out.addProperty("bytes", data.length);
        return out;
    }

    private JsonObject stagePlugin(JsonObject request) throws Exception {
        byte[] jar;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(string(request, "gzip"))))) {
            jar = in.readAllBytes();
        } catch (IllegalArgumentException | IOException e) {
            throw new BadRequest("gzip must be base64 gzip data");
        }
        if (jar.length < 4 || jar[0] != 'P' || jar[1] != 'K') throw new BadRequest("Not a jar");
        Path folder = plugin.getDataFolder().toPath().toAbsolutePath().getParent().resolve(Bukkit.getUpdateFolder());
        Files.createDirectories(folder);
        String name = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getFileName().toString();
        Path target = folder.resolve(name);
        Path temp = folder.resolve(name + ".part");
        Files.write(temp, jar);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        JsonObject out = new JsonObject();
        out.addProperty("staged", target.toString());
        out.addProperty("bytes", jar.length);
        return out;
    }

    private JsonObject command(JsonObject request) throws Exception {
        String raw = string(request, "command").trim();
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        if (command.isEmpty()) throw new BadRequest("command is empty");
        long waitMs = Math.clamp(optLong(request, "waitMs", 100), 0, 5000);

        List<String> feedback = Collections.synchronizedList(new ArrayList<>());
        boolean dispatched = sync(() -> {
            plugin.getLogger().info("Bridge ran: /" + command);
            CommandSender sender = Bukkit.createCommandSender(component -> feedback.add(PLAIN.serialize(component)));
            return Bukkit.dispatchCommand(sender, command);
        });
        if (waitMs > 0) Thread.sleep(waitMs);

        JsonObject out = new JsonObject();
        out.addProperty("dispatched", dispatched);
        synchronized (feedback) {
            out.add("feedback", GSON.toJsonTree(feedback));
        }
        return out;
    }

    private JsonObject console(JsonObject request) {
        long after = optLong(request, "after", 0);
        int max = (int) Math.clamp(optLong(request, "max", 200), 1, 2000);
        JsonArray lines = new JsonArray();
        for (ConsoleBuffer.Line line : console.since(after, max)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("seq", line.seq());
            entry.addProperty("text", line.text());
            lines.add(entry);
        }
        JsonObject out = new JsonObject();
        out.add("lines", lines);
        out.addProperty("last", console.lastSeq());
        return out;
    }

    private JsonObject players(JsonObject request) throws Exception {
        return sync(() -> {
            JsonArray players = new JsonArray();
            for (Player player : Bukkit.getOnlinePlayers()) {
                Location location = player.getLocation();
                JsonObject entry = new JsonObject();
                entry.addProperty("name", player.getName());
                entry.addProperty("uuid", player.getUniqueId().toString());
                entry.addProperty("world", location.getWorld().getName());
                entry.add("pos", vec(location));
                entry.addProperty("yaw", round(location.getYaw()));
                entry.addProperty("pitch", round(location.getPitch()));
                entry.addProperty("gameMode", player.getGameMode().name());
                entry.addProperty("health", round(player.getHealth()));
                entry.addProperty("food", player.getFoodLevel());
                entry.addProperty("sneaking", player.isSneaking());
                entry.addProperty("flying", player.isFlying());
                entry.addProperty("ping", player.getPing());
                entry.add("mainHand", item(player.getInventory().getItemInMainHand()));
                players.add(entry);
            }
            JsonObject out = new JsonObject();
            out.add("players", players);
            return out;
        });
    }

    private JsonObject entities(JsonObject request) throws Exception {
        int[] center = intVec(request, "center");
        double radius = Math.clamp(optLong(request, "radius", 16), 1, MAX_ENTITY_RADIUS);
        String type = request.has("type") ? string(request, "type").toLowerCase(Locale.ROOT) : null;
        return sync(() -> {
            World world = world(request);
            Location origin = new Location(world, center[0] + 0.5, center[1] + 0.5, center[2] + 0.5);
            JsonArray entities = new JsonArray();
            int total = 0;
            for (Entity entity : world.getNearbyEntities(origin, radius, radius, radius)) {
                if (entity.getLocation().distanceSquared(origin) > radius * radius) continue;
                String key = entity.getType().getKey().getKey();
                if (type != null && !key.equals(type) && !entity.getType().getKey().toString().equals(type)) continue;
                total++;
                if (entities.size() >= MAX_ENTITIES) continue;
                JsonObject entry = new JsonObject();
                entry.addProperty("type", key);
                entry.addProperty("uuid", entity.getUniqueId().toString());
                entry.add("pos", vec(entity.getLocation()));
                entry.addProperty("name", entity.getName());
                Component customName = entity.customName();
                if (customName != null) entry.addProperty("customName", PLAIN.serialize(customName));
                if (entity instanceof LivingEntity living) entry.addProperty("health", round(living.getHealth()));
                entities.add(entry);
            }
            JsonObject out = new JsonObject();
            out.add("entities", entities);
            out.addProperty("total", total);
            return out;
        });
    }

    private JsonObject getBlocks(JsonObject request) throws Exception {
        int[] from = intVec(request, "from");
        int[] to = intVec(request, "to");
        int minX = Math.min(from[0], to[0]), minY = Math.min(from[1], to[1]), minZ = Math.min(from[2], to[2]);
        int sizeX = Math.abs(to[0] - from[0]) + 1, sizeY = Math.abs(to[1] - from[1]) + 1, sizeZ = Math.abs(to[2] - from[2]) + 1;
        long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > MAX_VOLUME) throw new BadRequest("Box is " + volume + " blocks, max is " + MAX_VOLUME);

        return sync(() -> {
            World world = world(request);
            Map<String, Integer> palette = new LinkedHashMap<>();
            JsonArray data = new JsonArray();
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    for (int x = 0; x < sizeX; x++) {
                        String state = world.getBlockAt(minX + x, minY + y, minZ + z).getBlockData().getAsString();
                        data.add(palette.computeIfAbsent(state, key -> palette.size()));
                    }
                }
            }
            JsonObject out = new JsonObject();
            out.addProperty("world", world.getName());
            out.add("origin", ints(minX, minY, minZ));
            out.add("size", ints(sizeX, sizeY, sizeZ));
            out.addProperty("order", "yzx");
            out.add("palette", GSON.toJsonTree(new ArrayList<>(palette.keySet())));
            out.add("data", data);
            return out;
        });
    }

    private JsonObject pasteBlocks(JsonObject request) throws Exception {
        int[] origin = intVec(request, "origin");
        int[] size = intVec(request, "size");
        if (size[0] < 1 || size[1] < 1 || size[2] < 1) throw new BadRequest("size must be positive");
        long volume = (long) size[0] * size[1] * size[2];
        if (volume > MAX_VOLUME) throw new BadRequest("Box is " + volume + " blocks, max is " + MAX_VOLUME);
        JsonArray paletteJson = array(request, "palette");
        JsonArray dataJson = array(request, "data");
        if (dataJson.size() != volume) throw new BadRequest("data has " + dataJson.size() + " entries, size needs " + volume);
        boolean skipAir = request.has("skipAir") && request.get("skipAir").getAsBoolean();

        int[] data = new int[dataJson.size()];
        for (int i = 0; i < data.length; i++) {
            data[i] = dataJson.get(i).getAsInt();
            if (data[i] < 0 || data[i] >= paletteJson.size()) throw new BadRequest("data[" + i + "] is not a palette index");
        }

        return sync(() -> {
            World world = world(request);
            BlockData[] palette = new BlockData[paletteJson.size()];
            for (int i = 0; i < palette.length; i++) palette[i] = parseBlock(paletteJson.get(i).getAsString());

            int changed = 0;
            int index = 0;
            for (int y = 0; y < size[1]; y++) {
                for (int z = 0; z < size[2]; z++) {
                    for (int x = 0; x < size[0]; x++) {
                        BlockData state = palette[data[index++]];
                        if (skipAir && state.getMaterial().isAir()) continue;
                        if (place(world.getBlockAt(origin[0] + x, origin[1] + y, origin[2] + z), state)) changed++;
                    }
                }
            }
            JsonObject out = new JsonObject();
            out.addProperty("changed", changed);
            return out;
        });
    }

    private JsonObject setBlocks(JsonObject request) throws Exception {
        JsonArray blocks = array(request, "blocks");
        if (blocks.size() > MAX_VOLUME) throw new BadRequest("Too many blocks, max is " + MAX_VOLUME);
        return sync(() -> {
            World world = world(request);
            Map<String, BlockData> parsed = new LinkedHashMap<>();
            int changed = 0;
            for (JsonElement element : blocks) {
                JsonObject entry = element.getAsJsonObject();
                String state = string(entry, "block");
                BlockData data = parsed.get(state);
                if (data == null) {
                    data = parseBlock(state);
                    parsed.put(state, data);
                }
                Block block = world.getBlockAt(entry.get("x").getAsInt(), entry.get("y").getAsInt(), entry.get("z").getAsInt());
                if (place(block, data)) changed++;
            }
            JsonObject out = new JsonObject();
            out.addProperty("changed", changed);
            return out;
        });
    }

    private static boolean place(Block block, BlockData data) {
        if (block.getBlockData().equals(data)) return false;
        block.setBlockData(data, false);
        return true;
    }

    private static BlockData parseBlock(String state) throws BadRequest {
        try {
            return Bukkit.createBlockData(state);
        } catch (IllegalArgumentException e) {
            throw new BadRequest("Unknown block state: " + state);
        }
    }

    private <T> T sync(Callable<T> task) throws Exception {
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private static World world(JsonObject request) throws BadRequest {
        if (!request.has("world")) return Bukkit.getWorlds().getFirst();
        String name = string(request, "world");
        World world = Bukkit.getWorld(name);
        if (world == null) {
            throw new BadRequest("No world " + name + ", have " + Bukkit.getWorlds().stream().map(World::getName).toList());
        }
        return world;
    }

    private static String string(JsonObject object, String key) throws BadRequest {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) throw new BadRequest(key + " is required");
        return value.getAsString();
    }

    private static long optLong(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsLong();
    }

    private static JsonArray array(JsonObject object, String key) throws BadRequest {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) throw new BadRequest(key + " must be an array");
        return value.getAsJsonArray();
    }

    private static int[] intVec(JsonObject object, String key) throws BadRequest {
        JsonArray array = array(object, key);
        if (array.size() != 3) throw new BadRequest(key + " must be [x, y, z]");
        return new int[]{array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
    }

    private static JsonArray ints(int x, int y, int z) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }

    private static JsonArray vec(Location location) {
        JsonArray array = new JsonArray();
        array.add(round(location.getX()));
        array.add(round(location.getY()));
        array.add(round(location.getZ()));
        return array;
    }

    private static JsonObject item(ItemStack stack) {
        JsonObject out = new JsonObject();
        out.addProperty("type", stack.getType().getKey().toString());
        out.addProperty("amount", stack.getAmount());
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            out.addProperty("name", PLAIN.serialize(meta.displayName()));
        }
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", message);
        return out;
    }

    private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
