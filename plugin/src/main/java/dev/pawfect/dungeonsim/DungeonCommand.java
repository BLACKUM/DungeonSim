package dev.pawfect.dungeonsim;

import dev.pawfect.dungeonsim.dungeon.DungeonManager;
import dev.pawfect.dungeonsim.gen.Floor;
import dev.pawfect.dungeonsim.gen.LayoutGenerator;
import dev.pawfect.dungeonsim.gen.Seed;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class DungeonCommand implements BasicCommand {
    private static final List<String> SUBCOMMANDS = List.of("menu", "map", "gen", "view", "leave", "reset", "join", "times", "class", "reload");

    private final DungeonManager manager;

    DungeonCommand(DungeonManager manager) {
        this.manager = manager;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!sender.isOp()) {
                sender.sendMessage(Component.text("Ops only.", NamedTextColor.RED));
                return;
            }
            sender.sendMessage(Component.text("Rooms: " + manager.reload(), NamedTextColor.GRAY));
            return;
        }
        if (sub.equals("gen")) {
            gen(sender, args);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return;
        }
        switch (sub) {
            case "menu" -> manager.openMenu(player);
            case "map" -> {
                String problem = manager.giveMap(player);
                if (problem != null) player.sendMessage(Component.text(problem, NamedTextColor.GRAY));
            }
            case "cancelseed" -> manager.cancelSeed(player);
            case "view" -> {
                if (!player.isOp()) player.sendMessage(Component.text("Ops only.", NamedTextColor.RED));
                else manager.view(player, args.length < 2 ? null : args[1].toLowerCase(Locale.ROOT));
            }
            case "leave" -> {
                if (!manager.leave(player)) player.sendMessage(Component.text("You're not in a map.", NamedTextColor.GRAY));
            }
            case "reset" -> {
                if (!manager.reset(player)) player.sendMessage(Component.text("You're not in a map.", NamedTextColor.GRAY));
            }
            case "join" -> {
                Player target = args.length < 2 ? null : Bukkit.getPlayerExact(args[1]);
                if (target == null) player.sendMessage(Component.text("/ds join <player>", NamedTextColor.GRAY));
                else if (!manager.join(player, target)) player.sendMessage(Component.text(target.getName() + " isn't in a map.", NamedTextColor.GRAY));
            }
            case "class" -> {
                String pick = args.length < 2 ? null : DungeonManager.CLASSES.stream().filter(c -> c.equalsIgnoreCase(args[1])).findFirst().orElse(null);
                if (pick == null) {
                    player.sendMessage(Component.text("/ds class <" + String.join("|", DungeonManager.CLASSES) + ">", NamedTextColor.GRAY));
                } else {
                    manager.setDungeonClass(player, pick);
                    player.sendMessage(Component.text("You're a " + pick + " in the tab list now.", NamedTextColor.GRAY));
                }
            }
            case "times" -> {
                String code = args.length >= 2 ? args[1].toUpperCase(Locale.ROOT) : manager.codeAt(player.getWorld());
                if (code == null) player.sendMessage(Component.text("/ds times <code>", NamedTextColor.GRAY));
                else manager.showTimes(player, code);
            }
            default -> player.sendMessage(Component.text("/ds menu | map | gen [floor|code] | view [room] | leave | reset | join <player> | times [code] | class <name>", NamedTextColor.GRAY));
        }
    }

    private void gen(CommandSender sender, String[] args) {
        Seed seed;
        try {
            if (args.length < 2) seed = randomSeed(Floor.F7);
            else if (args[1].contains("-")) seed = Seed.parse(args[1]);
            else seed = randomSeed(Floor.parse(args[1]));
            if (!seed.floor().supported()) throw new IllegalArgumentException(seed.floor() + " isn't supported yet, only F7 and M7");
            if (seed.version() != LayoutGenerator.VERSION) throw new IllegalArgumentException("That code is from generator v" + seed.version() + ", this server runs v" + LayoutGenerator.VERSION);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
            return;
        }
        manager.generate(sender, seed);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(typed)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("gen")) {
            String typed = args[1].toUpperCase(Locale.ROOT);
            return Arrays.stream(Floor.values()).filter(Floor::supported).map(Floor::name).filter(f -> f.startsWith(typed)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("view")) {
            return manager.recordedRooms().stream().filter(r -> r.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("class")) {
            return DungeonManager.CLASSES.stream().filter(c -> c.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }

    private static Seed randomSeed(Floor floor) {
        return new Seed(floor, LayoutGenerator.VERSION, ThreadLocalRandom.current().nextLong());
    }
}
