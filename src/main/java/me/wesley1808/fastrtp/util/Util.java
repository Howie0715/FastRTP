package me.wesley1808.fastrtp.util;

import eu.pb4.placeholders.api.TextParserUtils;
import me.wesley1808.fastrtp.config.Config;
import me.wesley1808.fastrtp.mixins.ServerChunkCacheAccessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTypeTest;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class Util {

    @Nullable
    public static LevelChunk getChunkIfLoaded(ServerLevel level, int chunkX, int chunkZ) {
        final ChunkHolder holder = getChunkHolder(level.getChunkSource(), chunkX, chunkZ);
        return holder != null ? holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).left().orElse(null) : null;
    }

    @Nullable
    private static ChunkHolder getChunkHolder(ServerChunkCache chunkCache, int chunkX, int chunkZ) {
        ServerChunkCacheAccessor accessor = (ServerChunkCacheAccessor) chunkCache;
        return accessor.getHolder(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static Component format(String string) {
        return TextParserUtils.formatTextSafe(string);
    }

    public static ServerLevel getLevel(ServerPlayer player) {
        ResourceLocation location;
        if (Config.instance().useCurrentWorld || (location = ResourceLocation.tryParse(Config.instance().defaultDimension)) == null) {
            return player.serverLevel();
        }

        return player.server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
    }

    public static int getRadius(ServerLevel level) {
        final int borderRadius = (int) (level.getWorldBorder().getSize() / 2) - 16;
        final int radius = Config.instance().radius;
        if (radius < 0) {
            return borderRadius;
        }

        return Math.min(borderRadius, radius);
    }

    @Nullable
    public static String mayTeleport(ServerPlayer player) {
        if (player.gameMode.isSurvival() && Config.instance().useStrictTeleportCheck) {
            if (player.hasEffect(MobEffects.LEVITATION)) {
                return "Levitation Effect";
            }

            if (player.hasEffect(MobEffects.DARKNESS)) {
                return "Darkness Effect";
            }

            List<Monster> monsters = player.level().getEntities(EntityTypeTest.forClass(Monster.class), player.getBoundingBox().inflate(64D), EntitySelector.NO_SPECTATORS);
            for (Monster monster : monsters) {
                if (isTargeted(player, monster)) {
                    if (monster instanceof Warden) {
                        return "Hunted by warden";
                    }

                    float distance = player.distanceTo(monster);
                    if (distance < 24 && monster.getSensing().hasLineOfSight(player)) {
                        return String.format("Hunted by %s (%.0f blocks away)", EntityType.getKey(monster.getType()).getPath(), distance);
                    }
                }
            }
        }

        return null;
    }

    public static boolean isTargeted(ServerPlayer target, Mob mob) {
        boolean isTargeted = mob.getTarget() == target;

        // Check the memory for mobs that don't use the target selector.
        if (!isTargeted) {
            Brain<?> brain = mob.getBrain();
            MemoryModuleType<?> module = MemoryModuleType.ATTACK_TARGET;
            isTargeted = brain.hasMemoryValue(module) && brain.getMemory(module).orElse(null) == target;
        }

        // Check if the mob is a warden that is sniffing out the player.
        if (!isTargeted && mob instanceof Warden warden) {
            isTargeted = warden.getAngerManagement().getActiveEntity().orElse(null) == target;
        }

        return isTargeted;
    }
}
