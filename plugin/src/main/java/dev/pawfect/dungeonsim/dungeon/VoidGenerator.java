package dev.pawfect.dungeonsim.dungeon;

import java.util.Random;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

final class VoidGenerator extends ChunkGenerator {
    @Override
    public boolean shouldGenerateNoise(WorldInfo info, Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface(WorldInfo info, Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves(WorldInfo info, Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations(WorldInfo info, Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs(WorldInfo info, Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures(WorldInfo info, Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, -100, 100, -100);
    }
}
