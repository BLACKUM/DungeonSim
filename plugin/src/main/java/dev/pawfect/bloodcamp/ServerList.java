package dev.pawfect.bloodcamp;

import dev.pawfect.bloodcamp.gen.Seed;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.CachedServerIcon;

public final class ServerList implements Listener {
    private record Look(CachedServerIcon icon, TextColor main, TextColor dim) {}

    private static final String[][] LOOKS = {
        {"botw-zelda.png", "#50c8eb", "#397a8c"},
        {"link.png", "#ebc559", "#8c783e"},
        {"orihime.png", "#eb923d", "#8c6138"},
        {"tp-zelda.png", "#ebc178", "#8c764f"},
        {"riruka.png", "#eb4a76", "#8c384f"},
    };

    private final List<Look> looks = new ArrayList<>();
    private final AtomicInteger next = new AtomicInteger();

    public ServerList(JavaPlugin plugin) {
        for (String[] look : LOOKS) {
            try (InputStream in = plugin.getResource("icons/" + look[0])) {
                if (in == null) {
                    plugin.getLogger().warning("Server icon missing from the jar: " + look[0]);
                    continue;
                }
                BufferedImage image = ImageIO.read(in);
                looks.add(new Look(Bukkit.loadServerIcon(image), TextColor.fromHexString(look[1]), TextColor.fromHexString(look[2])));
            } catch (Exception e) {
                plugin.getLogger().warning("Server icon " + look[0] + " failed to load: " + e.getMessage());
            }
        }
    }

    private static Component motd(TextColor main, TextColor dim) {
        return Component.text()
            .append(Component.text("BLOODCAMP", main, TextDecoration.BOLD))
            .append(Component.text(" │ ", dim))
            .append(Component.text("daily seed ", NamedTextColor.GRAY))
            .append(Component.text(Seed.today().code(), main))
            .append(Component.newline())
            .append(Component.text("stop being ass. ", main))
            .append(Component.text("go practice.", NamedTextColor.GRAY))
            .build();
    }

    @EventHandler
    public void onPing(ServerListPingEvent event) {
        if (looks.isEmpty()) return;
        Look look = looks.get(Math.floorMod(next.getAndIncrement(), looks.size()));
        event.setServerIcon(look.icon());
        event.motd(motd(look.main(), look.dim()));
    }
}
