package dev.pawfect.dungeonsim;

import dev.pawfect.dungeonsim.bridge.Auth;
import dev.pawfect.dungeonsim.bridge.BridgeServer;
import dev.pawfect.dungeonsim.bridge.ConsoleBuffer;
import dev.pawfect.dungeonsim.dungeon.DungeonManager;
import java.io.IOException;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

public final class DungeonSimPlugin extends JavaPlugin {
    private ConsoleBuffer console;
    private BridgeServer bridge;
    private DungeonManager dungeons;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        console = ConsoleBuffer.attach(4000);
        dungeons = new DungeonManager(this);
        dungeons.enable();
        registerCommand("ds", "DungeonSim maps", List.of("dungeonsim"), new DungeonCommand(dungeons));
        DungeonManager manager = dungeons;
        registerCommand("locraw", "Where Hypixel would say you are", (source, args) -> {
            if (source.getSender() instanceof org.bukkit.entity.Player player) manager.locraw(player);
        });

        if (!getConfig().getBoolean("bridge.enabled", true)) {
            getLogger().info("Bridge disabled in config");
            return;
        }

        String secret = getConfig().getString("bridge.secret", "");
        if (secret == null || secret.isBlank()) {
            secret = Auth.newSecret();
            getConfig().set("bridge.secret", secret);
            saveConfig();
            getLogger().info("Generated a bridge secret in plugins/DungeonSim/config.yml");
        }

        String host = getConfig().getString("bridge.host", "0.0.0.0");
        int port = getConfig().getInt("bridge.port", 8765);
        try {
            bridge = new BridgeServer(this, console, new Auth(secret), host, port);
            bridge.start();
            getLogger().info("Bridge listening on " + host + ":" + port);
        } catch (IOException e) {
            getLogger().severe("Bridge failed to start on " + host + ":" + port + ": " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (dungeons != null) {
            dungeons.disable();
            dungeons = null;
        }
        if (bridge != null) {
            bridge.stop();
            bridge = null;
        }
        if (console != null) {
            console.detach();
            console = null;
        }
    }
}
