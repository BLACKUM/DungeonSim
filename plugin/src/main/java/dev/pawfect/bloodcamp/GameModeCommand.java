package dev.pawfect.bloodcamp;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class GameModeCommand implements BasicCommand {
    private final GameMode mode;

    GameModeCommand(GameMode mode) {
        this.mode = mode;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Player target;
        if (args.length > 0) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("No player named " + args[0] + " online.", NamedTextColor.RED));
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text("Name a player.", NamedTextColor.RED));
            return;
        }
        target.setGameMode(mode);
        String name = mode.name().charAt(0) + mode.name().substring(1).toLowerCase(Locale.ROOT);
        target.sendMessage(Component.text("Game mode: " + name, NamedTextColor.GRAY));
        if (target != sender) sender.sendMessage(Component.text(target.getName() + " is in " + name + " now.", NamedTextColor.GRAY));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) return List.of();
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    @Override
    public String permission() {
        return "minecraft.command.gamemode";
    }
}
