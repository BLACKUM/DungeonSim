package dev.pawfect.bloodcamp.dungeon;

import dev.pawfect.bloodcamp.gen.Seed;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class Lobby implements Listener {
    static final String WORLD = "bloodcamp_lobby";
    private static final int Y = 64;
    private static final int RADIUS = 6;

    private static final TextColor BRAND = TextColor.color(0xE0303A);
    private static final TextColor TITLE = TextColor.color(0xF2C14E);
    private static final TextColor LABEL = TextColor.color(0x9AA0A6);
    private static final TextColor DIM = TextColor.color(0x5F6368);
    private static final TextColor NAME = TextColor.color(0xF1F3F4);
    private static final TextColor TIME = TextColor.color(0xF7D774);
    private static final TextColor SEED = TextColor.color(0x6FD3F2);
    private static final TextColor[] PODIUM = {TextColor.color(0xF7D774), TextColor.color(0xD7DCE0), TextColor.color(0xD9955B)};

    private final JavaPlugin plugin;
    private final Consumer<Player> openMenu;
    private final Consumer<Player> openRooms;
    private final Times times;
    private final Parkour parkour;
    private final NamespacedKey npcKey;
    private World world;
    private String shownDaily;

    Lobby(JavaPlugin plugin, Consumer<Player> openMenu, Consumer<Player> openRooms, Times times) {
        this.plugin = plugin;
        this.openMenu = openMenu;
        this.openRooms = openRooms;
        this.times = times;
        this.npcKey = new NamespacedKey(plugin, "lobby_npc");
        this.parkour = new Parkour(plugin, npcKey);
    }

    void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        world = new WorldCreator(WORLD).generator(new VoidGenerator()).environment(World.Environment.NORMAL).generateStructures(false).createWorld();
        if (world == null) throw new IllegalStateException("Couldn't make the lobby world");
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.FALL_DAMAGE, false);
        world.setTime(6000);
        for (int cx = -1; cx <= 0; cx++) for (int cz = -1; cz <= 0; cz++) world.setChunkForceLoaded(cx, cz, true);
        build();
        world.setSpawnLocation(spawn());
        spawnNpc();
        spawnRoomSelector();
        parkour.build(world);
        spawnBoards();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!Seed.today().code().equals(shownDaily)) refreshBoards();
        }, 1200, 1200);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            parkour.tick();
            for (Player p : world.getPlayers()) {
                double y = p.getLocation().getY();
                if (y < -1 || (y < Y - 6 && !parkour.running(p))) {
                    p.teleport(spawn());
                    p.setFallDistance(0);
                }
            }
        }, 1, 1);
    }

    private void build() {
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                boolean rim = Math.abs(x) == RADIUS || Math.abs(z) == RADIUS;
                world.getBlockAt(x, Y, z).setType(rim ? Material.POLISHED_DEEPSLATE : Material.SMOOTH_STONE, false);
            }
        }
    }

    Location spawn() {
        return new Location(world, 0.5, Y + 1, 0.5, 0f, 0f);
    }

    private void spawnNpc() {
        for (Entity e : world.getEntities()) {
            if (e.getPersistentDataContainer().has(npcKey)) e.remove();
        }
        Location at = new Location(world, -2.5, Y + 1, 4.5, 180f, 0f);
        world.spawn(at, Villager.class, v -> {
            v.setProfession(Villager.Profession.CARTOGRAPHER);
            v.setVillagerType(Villager.Type.PLAINS);
            v.setVillagerLevel(5);
            v.setAI(false);
            v.setSilent(true);
            v.setInvulnerable(true);
            v.setPersistent(false);
            v.setRemoveWhenFarAway(false);
            v.setCollidable(false);
            v.customName(Component.text("Dungeon Maps", TextColor.color(0xF2C14E), TextDecoration.BOLD));
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, MAPS);
        });
        world.spawn(at.clone().add(0, 2.7, 0), TextDisplay.class, t -> {
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setPersistent(false);
            t.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
            t.getPersistentDataContainer().set(boardKey(), PersistentDataType.STRING, "daily");
        });
        world.spawn(at.clone().add(0, 2.45, 0), TextDisplay.class, t -> {
            t.text(Component.text("Right-click to browse", TextColor.color(0x9AA0A6)));
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setPersistent(false);
            t.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private static final byte MAPS = 1;
    private static final byte ROOMS = 2;

    private void spawnRoomSelector() {
        Location at = new Location(world, 0.5, Y + 1, -4.5, 0f, 0f);
        world.spawn(at, Villager.class, v -> {
            v.setProfession(Villager.Profession.MASON);
            v.setVillagerType(Villager.Type.PLAINS);
            v.setVillagerLevel(5);
            v.setAI(false);
            v.setSilent(true);
            v.setInvulnerable(true);
            v.setPersistent(false);
            v.setRemoveWhenFarAway(false);
            v.setCollidable(false);
            v.customName(Component.text("Room Selector", TITLE, TextDecoration.BOLD));
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, ROOMS);
        });
        world.spawn(at.clone().add(0, 2.45, 0), TextDisplay.class, t -> {
            t.text(Component.text("Practice any room", LABEL));
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setPersistent(false);
            t.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 0);
        });
    }

    private void spawnBoards() {
        world.spawn(new Location(world, 3.0, Y + 1.4, 4.5), TextDisplay.class, t -> {
            t.setBillboard(Display.Billboard.VERTICAL);
            t.setBackgroundColor(Color.fromARGB(150, 10, 6, 7));
            t.setShadowed(true);
            t.setLineWidth(400);
            t.setPersistent(false);
            t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                new org.joml.Vector3f(0.6f, 0.6f, 0.6f), new org.joml.AxisAngle4f()));
            t.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
            t.getPersistentDataContainer().set(boardKey(), PersistentDataType.STRING, Seed.FLOOR.name());
        });
        refreshBoards();
    }

    void refreshBoards() {
        if (world == null) return;
        shownDaily = Seed.today().code();
        for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
            String which = display.getPersistentDataContainer().get(boardKey(), PersistentDataType.STRING);
            if (which == null) continue;
            if (which.equals("daily")) {
                display.text(Component.text("Daily Seed ", TITLE, TextDecoration.BOLD).append(Component.text(shownDaily, SEED).decoration(TextDecoration.BOLD, false)));
            } else {
                display.text(board(which));
            }
        }
    }

    private NamespacedKey boardKey() {
        return new NamespacedKey(plugin, "board");
    }

    private Component board(String floor) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("BLOODCAMP", BRAND, TextDecoration.BOLD));
        lines.add(Component.text("Fastest Clears", TITLE));
        lines.add(Component.text("Season " + dev.pawfect.bloodcamp.gen.LayoutGenerator.VERSION, DIM));
        lines.add(Component.empty());
        List<Times.Clear> top = times.top(Times.onFloor(floor), 10);
        for (int i = 0; i < 10; i++) {
            TextColor rankColor = i < PODIUM.length ? PODIUM[i] : LABEL;
            Component rank = Component.text("#" + (i + 1) + "  ", rankColor, TextDecoration.BOLD);
            if (i >= top.size()) {
                lines.add(rank.append(Component.text("---", DIM).decoration(TextDecoration.BOLD, false)));
                continue;
            }
            Times.Clear c = top.get(i);
            lines.add(rank.append(Component.text(c.name(), NAME).decoration(TextDecoration.BOLD, false))
                .append(Component.text("  " + Times.format(c.millis()), TIME).decoration(TextDecoration.BOLD, false))
                .append(Component.text("  " + c.seed(), SEED).decoration(TextDecoration.BOLD, false))
                .append(Component.text("  ✦" + c.speed() + (c.party() > 1 ? " · " + c.party() + "p" : ""), DIM).decoration(TextDecoration.BOLD, false)));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
        if (world == null || !event.getWorld().equals(world)) return;
        for (Entity e : event.getEntities()) {
            if (e.isPersistent() && e.getPersistentDataContainer().has(npcKey)) e.remove();
        }
    }

    @EventHandler
    public void onClickNpc(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getPersistentDataContainer().has(npcKey)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Byte which = event.getRightClicked().getPersistentDataContainer().get(npcKey, PersistentDataType.BYTE);
        if (which != null && which == ROOMS) openRooms.accept(event.getPlayer());
        else openMenu.accept(event.getPlayer());
    }

    @EventHandler
    public void onPlate(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.PHYSICAL || event.getClickedBlock() == null) return;
        if (!isLobby(event.getPlayer().getWorld())) return;
        parkour.stepped(event.getPlayer(), event.getClickedBlock());
    }

    @EventHandler
    public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (isLobby(event.getBlock().getWorld()) && event.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) event.setCancelled(true);
    }

    @EventHandler
    public void onPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (isLobby(event.getBlock().getWorld()) && event.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        parkour.forget(event.getPlayer());
    }

    boolean isLobby(World w) {
        return w.equals(world);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        event.setRespawnLocation(spawn());
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (isLobby(event.getEntity().getWorld())) event.setCancelled(true);
    }
}
