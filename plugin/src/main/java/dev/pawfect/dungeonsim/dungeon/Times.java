package dev.pawfect.dungeonsim.dungeon;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

final class Times {
    record Entry(String name, long millis) {}

    private final File file;
    private final Logger log;
    private final YamlConfiguration yaml;

    Times(File file, Logger log) {
        this.file = file;
        this.log = log;
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    synchronized long record(String code, UUID player, String name, long millis) {
        String path = code + "." + player;
        long old = yaml.getLong(path + ".ms", -1);
        if (old < 0 || millis < old) {
            yaml.set(path + ".ms", millis);
            yaml.set(path + ".name", name);
            try {
                yaml.save(file);
            } catch (IOException e) {
                log.warning("Couldn't save times: " + e.getMessage());
            }
        }
        return old;
    }

    synchronized List<Entry> top(String code, int limit) {
        ConfigurationSection section = yaml.getConfigurationSection(code);
        List<Entry> out = new ArrayList<>();
        if (section == null) return out;
        for (String key : section.getKeys(false)) {
            out.add(new Entry(section.getString(key + ".name", "?"), section.getLong(key + ".ms")));
        }
        out.sort(Comparator.comparingLong(Entry::millis));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    static String format(long millis) {
        long minutes = millis / 60000;
        double seconds = (millis % 60000) / 1000.0;
        return minutes > 0 ? String.format("%d:%06.3f", minutes, seconds) : String.format("%.3fs", seconds);
    }
}
