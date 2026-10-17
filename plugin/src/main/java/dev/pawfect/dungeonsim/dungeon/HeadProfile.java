package dev.pawfect.dungeonsim.dungeon;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonObject;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.jspecify.annotations.Nullable;

record HeadProfile(@Nullable UUID id, String texture, @Nullable String signature) {
    static final HeadProfile WITHER_ESSENCE;
    static final HeadProfile REDSTONE_KEY;

    private static final Map<HeadProfile, PlayerProfile> PROFILES = new ConcurrentHashMap<>();

    static {
        try (InputStream in = HeadProfile.class.getResourceAsStream("/secret-heads.json")) {
            if (in == null) throw new IllegalStateException("secret-heads.json missing from the jar");
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            WITHER_ESSENCE = read(json.getAsJsonObject("wither_essence"));
            REDSTONE_KEY = read(json.getAsJsonObject("redstone_key"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Couldn't read secret-heads.json", e);
        }
    }

    private static HeadProfile read(JsonObject json) {
        return new HeadProfile(UUID.fromString(json.get("id").getAsString()), json.get("value").getAsString(), json.get("signature").getAsString());
    }

    PlayerProfile profile() {
        return PROFILES.computeIfAbsent(this, p -> {
            UUID uuid = p.id() != null ? p.id() : UUID.nameUUIDFromBytes(p.texture().getBytes(StandardCharsets.UTF_8));
            PlayerProfile profile = Bukkit.createProfile(uuid, null);
            profile.setProperty(new ProfileProperty("textures", p.texture(), p.signature()));
            return profile;
        });
    }

    void applyTo(Block block) {
        if (block.getState() instanceof Skull skull) {
            skull.setProfile(ResolvableProfile.resolvableProfile(profile()));
            skull.update(true, false);
        }
    }
}
