package dev.pawfect.bloodcamp.dungeon;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class Parkour {
    private static final TextColor BRAND = TextColor.color(0xE0303A);
    private static final TextColor GOLD = TextColor.color(0xF7D774);
    private static final TextColor LABEL = TextColor.color(0x9AA0A6);

    private enum Kind { JUMP, CHECKPOINT, FINISH }

    private record Step(int x, int y, int z, Kind kind, Material block) {}

    private static final Step[] COURSE = {
        new Step(12, 64, 0, Kind.JUMP, Material.TUFF),
        new Step(15, 65, 0, Kind.JUMP, Material.TUFF),
        new Step(15, 65, 3, Kind.JUMP, Material.TUFF),
        new Step(15, 66, 6, Kind.JUMP, Material.TUFF),
        new Step(13, 66, 8, Kind.JUMP, Material.TUFF),
        new Step(11, 67, 10, Kind.JUMP, Material.TUFF),
        new Step(8, 67, 10, Kind.JUMP, Material.TUFF),
        new Step(4, 67, 10, Kind.CHECKPOINT, Material.POLISHED_TUFF),
        new Step(1, 68, 10, Kind.JUMP, Material.TUFF_BRICKS),
        new Step(-2, 68, 10, Kind.JUMP, Material.TUFF_BRICKS),
        new Step(-5, 69, 10, Kind.JUMP, Material.TUFF_BRICKS),
        new Step(-8, 69, 11, Kind.JUMP, Material.TUFF_BRICKS),
        new Step(-11, 70, 11, Kind.JUMP, Material.TUFF_BRICKS),
        new Step(-12, 70, 8, Kind.JUMP, Material.TUFF_BRICKS),
        new Step(-12, 71, 5, Kind.JUMP, Material.TUFF_BRICKS),
        new Step(-12, 71, 2, Kind.JUMP, Material.TUFF_BRICKS),
        new Step(-13, 71, -2, Kind.CHECKPOINT, Material.POLISHED_TUFF),
        new Step(-12, 72, -5, Kind.JUMP, Material.CHISELED_TUFF),
        new Step(-12, 72, -8, Kind.JUMP, Material.CHISELED_TUFF),
        new Step(-10, 72, -10, Kind.JUMP, Material.CHISELED_TUFF),
        new Step(-7, 73, -10, Kind.JUMP, Material.CHISELED_TUFF),
        new Step(-4, 73, -10, Kind.JUMP, Material.CHISELED_TUFF),
        new Step(-1, 74, -10, Kind.JUMP, Material.CHISELED_TUFF),
        new Step(2, 74, -10, Kind.JUMP, Material.CHISELED_TUFF),
        new Step(5, 74, -11, Kind.CHECKPOINT, Material.POLISHED_TUFF),
        new Step(9, 75, -10, Kind.JUMP, Material.RED_NETHER_BRICKS),
        new Step(12, 75, -10, Kind.JUMP, Material.RED_NETHER_BRICKS),
        new Step(14, 76, -8, Kind.JUMP, Material.RED_NETHER_BRICKS),
        new Step(14, 76, -5, Kind.JUMP, Material.RED_NETHER_BRICKS),
        new Step(14, 77, -2, Kind.JUMP, Material.RED_NETHER_BRICKS),
        new Step(11, 77, -2, Kind.JUMP, Material.RED_NETHER_BRICKS),
        new Step(7, 78, -2, Kind.FINISH, Material.CHISELED_TUFF_BRICKS),
    };

    private static final int START_X = 8, START_Y = 64, START_Z = 0;
    private static final int CHECKPOINTS = 3;

    private static final class Run {
        final long started = System.nanoTime();
        int checkpoint;
        Location respawn;

        Run(Location respawn) {
            this.respawn = respawn;
        }
    }

    private final JavaPlugin plugin;
    private final NamespacedKey bestKey;
    private final NamespacedKey markerKey;
    private final Map<UUID, Run> runs = new HashMap<>();
    private World world;
    private long ticks;

    Parkour(JavaPlugin plugin, NamespacedKey markerKey) {
        this.plugin = plugin;
        this.markerKey = markerKey;
        this.bestKey = new NamespacedKey(plugin, "parkour_best");
    }

    void build(World world) {
        this.world = world;
        for (int x = 7; x <= 9; x++) for (int z = -1; z <= 1; z++) world.getBlockAt(x, START_Y, z).setType(Material.POLISHED_TUFF, false);
        world.getBlockAt(START_X, START_Y + 1, START_Z).setType(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, false);
        for (Step s : COURSE) {
            switch (s.kind()) {
                case JUMP -> world.getBlockAt(s.x(), s.y(), s.z()).setType(s.block(), false);
                case CHECKPOINT -> {
                    for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) world.getBlockAt(s.x() + dx, s.y(), s.z() + dz).setType(s.block(), false);
                    world.getBlockAt(s.x(), s.y() + 1, s.z()).setType(Material.HEAVY_WEIGHTED_PRESSURE_PLATE, false);
                }
                case FINISH -> {
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        world.getBlockAt(s.x() + dx, s.y(), s.z() + dz).setType(dx == 0 && dz == 0 ? s.block() : Material.RED_NETHER_BRICKS, false);
                    }
                    world.getBlockAt(s.x(), s.y() + 1, s.z()).setType(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, false);
                }
            }
        }
        sign(new Location(world, START_X + 0.5, START_Y + 2.6, START_Z + 0.5), Component.join(JoinConfiguration.newlines(),
            Component.text("PARKOUR", BRAND, TextDecoration.BOLD),
            Component.text("Step on the gold plate to start", LABEL)));
        Step finish = COURSE[COURSE.length - 1];
        sign(new Location(world, finish.x() + 0.5, finish.y() + 2.4, finish.z() + 0.5), Component.text("FINISH", GOLD, TextDecoration.BOLD));
    }

    private void sign(Location at, Component text) {
        world.spawn(at, TextDisplay.class, t -> {
            t.text(text);
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setPersistent(false);
            t.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    boolean running(Player player) {
        return runs.containsKey(player.getUniqueId());
    }

    void stepped(Player player, Block plate) {
        if (world == null || !plate.getWorld().equals(world)) return;
        int x = plate.getX(), y = plate.getY(), z = plate.getZ();
        if (x == START_X && y == START_Y + 1 && z == START_Z) {
            if (!eligible(player)) return;
            Run old = runs.get(player.getUniqueId());
            if (old != null && System.nanoTime() - old.started < 1_000_000_000L) return;
            runs.put(player.getUniqueId(), new Run(plate.getLocation().add(0.5, 0, 0.5)));
            player.sendActionBar(Component.text("Go!", NamedTextColor.GREEN));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.6f);
            return;
        }
        Run run = runs.get(player.getUniqueId());
        if (run == null) return;
        int checkpoint = 0;
        for (Step s : COURSE) {
            if (s.kind() == Kind.CHECKPOINT) checkpoint++;
            if (s.x() != x || s.y() + 1 != y || s.z() != z) continue;
            if (s.kind() == Kind.CHECKPOINT && checkpoint > run.checkpoint) {
                run.checkpoint = checkpoint;
                run.respawn = plate.getLocation().add(0.5, 0, 0.5);
                player.sendMessage(Component.text("Checkpoint " + checkpoint + "/" + CHECKPOINTS + "  ", GOLD)
                    .append(Component.text(Times.format(elapsed(run)), LABEL)));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.2f + checkpoint * 0.15f);
            } else if (s.kind() == Kind.FINISH) {
                finish(player, run);
            }
            return;
        }
    }

    private void finish(Player player, Run run) {
        if (run.checkpoint < CHECKPOINTS) {
            player.sendMessage(Component.text("You skipped a checkpoint, that one doesn't count.", NamedTextColor.RED));
            runs.remove(player.getUniqueId());
            return;
        }
        long millis = elapsed(run);
        runs.remove(player.getUniqueId());
        Long best = player.getPersistentDataContainer().get(bestKey, PersistentDataType.LONG);
        boolean pb = best == null || millis < best;
        if (pb) player.getPersistentDataContainer().set(bestKey, PersistentDataType.LONG, millis);
        player.sendMessage(Component.text("Parkour done in " + Times.format(millis), GOLD, TextDecoration.BOLD)
            .append(Component.text(pb ? (best == null ? "  first finish!" : "  new best, was " + Times.format(best)) : "  best is " + Times.format(best), LABEL)
                .decoration(TextDecoration.BOLD, false)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
    }

    void tick() {
        if (world == null) return;
        ticks++;
        runs.entrySet().removeIf(e -> {
            Player p = plugin.getServer().getPlayer(e.getKey());
            if (p == null || !p.getWorld().equals(world) || !eligible(p)) {
                if (p != null && p.isOnline()) p.sendActionBar(Component.text("Parkour cancelled", LABEL));
                return true;
            }
            Run run = e.getValue();
            if (p.getLocation().getY() < run.respawn.getY() - 5) {
                Location back = run.respawn.clone();
                back.setYaw(p.getLocation().getYaw());
                back.setPitch(p.getLocation().getPitch());
                p.teleport(back);
                p.setFallDistance(0);
            }
            if (ticks % 2 == 0) p.sendActionBar(Component.text(Times.format(elapsed(run)), GOLD)
                .append(Component.text("  " + run.checkpoint + "/" + CHECKPOINTS, LABEL)));
            return false;
        });
    }

    void forget(Player player) {
        runs.remove(player.getUniqueId());
    }

    private static boolean eligible(Player p) {
        return (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.isFlying();
    }

    private static long elapsed(Run run) {
        return (System.nanoTime() - run.started) / 1_000_000;
    }
}
