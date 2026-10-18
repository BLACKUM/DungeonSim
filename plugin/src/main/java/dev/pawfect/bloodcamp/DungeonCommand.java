package dev.pawfect.bloodcamp;

import dev.pawfect.bloodcamp.dungeon.DungeonManager;
import dev.pawfect.bloodcamp.gen.Seed;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
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
    private static final List<String> SUBCOMMANDS = List.of("menu", "rooms", "map", "gen", "daily", "view", "leave", "reset", "join", "times", "class", "reload");

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
        if (sub.equals("daily")) {
            manager.generate(sender, Seed.today());
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return;
        }
        switch (sub) {
            case "menu" -> manager.openMenu(player);
            case "rooms" -> manager.openRooms(player);
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
                if (target == null) player.sendMessage(Component.text("/bc join <player>", NamedTextColor.GRAY));
                else if (!manager.join(player, target)) player.sendMessage(Component.text(target.getName() + " isn't in a map.", NamedTextColor.GRAY));
            }
            case "class" -> {
                String pick = args.length < 2 ? null : DungeonManager.CLASSES.stream().filter(c -> c.equalsIgnoreCase(args[1])).findFirst().orElse(null);
                if (pick == null) {
                    player.sendMessage(Component.text("/bc class <" + String.join("|", DungeonManager.CLASSES) + ">", NamedTextColor.GRAY));
                } else {
                    manager.setDungeonClass(player, pick);
                    player.sendMessage(Component.text("You're a " + pick + " in the tab list now.", NamedTextColor.GRAY));
                }
            }
            case "times" -> {
                Seed here = manager.seedAt(player.getWorld());
                try {
                    Seed seed = args.length >= 2 ? Seed.parse(args[1]) : here;
                    if (seed == null) player.sendMessage(Component.text("/bc times <seed>", NamedTextColor.GRAY));
                    else manager.showTimes(player, seed);
                } catch (IllegalArgumentException e) {
                    player.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
                }
            }
            default -> player.sendMessage(Component.text("/bc menu | rooms | map | gen [seed] | daily | view [room] | leave | reset | join <player> | times [seed] | class <name>", NamedTextColor.GRAY));
        }
    }

    private void gen(CommandSender sender, String[] args) {
        Seed seed;
        try {
            seed = args.length >= 2 ? Seed.parse(args[1]) : Seed.random(new dev.pawfect.bloodcamp.gen.Rng(ThreadLocalRandom.current().nextLong()));
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
}
