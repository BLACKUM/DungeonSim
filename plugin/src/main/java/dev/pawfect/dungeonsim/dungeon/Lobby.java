package dev.pawfect.dungeonsim.dungeon;

import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
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
    static final String WORLD = "dungeonsim_lobby";
    private static final int Y = 64;
    private static final int RADIUS = 6;

    private final JavaPlugin plugin;
    private final Consumer<Player> openMenu;
    private final NamespacedKey npcKey;
    private World world;

    Lobby(JavaPlugin plugin, Consumer<Player> openMenu) {
        this.plugin = plugin;
        this.openMenu = openMenu;
        this.npcKey = new NamespacedKey(plugin, "lobby_npc");
    }

    void enable() {
        world = new WorldCreator(WORLD).generator(new VoidGenerator()).environment(World.Environment.NORMAL).generateStructures(false).createWorld();
        if (world == null) throw new IllegalStateException("Couldn't make the lobby world");
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.FALL_DAMAGE, false);
        world.setTime(6000);
        build();
        world.setSpawnLocation(spawn());
        spawnNpc();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (var p : world.getPlayers()) if (p.getLocation().getY() < Y - 20) p.teleport(spawn());
        }, 20, 10);
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
        Location at = new Location(world, 0.5, Y + 1, 4.5, 180f, 0f);
        world.spawn(at, Villager.class, v -> {
            v.setProfession(Villager.Profession.CARTOGRAPHER);
            v.setVillagerType(Villager.Type.PLAINS);
            v.setVillagerLevel(5);
            v.setAI(false);
            v.setSilent(true);
            v.setInvulnerable(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.setCollidable(false);
            v.customName(Component.text("Dungeon Maps", TextColor.color(0xF2C14E), TextDecoration.BOLD));
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
        });
        world.spawn(at.clone().add(0, 2.45, 0), TextDisplay.class, t -> {
            t.text(Component.text("Right-click to browse", TextColor.color(0x9AA0A6)));
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setPersistent(true);
            t.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    @EventHandler
    public void onClickNpc(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getPersistentDataContainer().has(npcKey)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        openMenu.accept(event.getPlayer());
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
