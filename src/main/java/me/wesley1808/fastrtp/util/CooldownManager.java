package me.wesley1808.fastrtp.util;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import me.lucko.fabric.api.permissions.v0.Permissions;
import me.wesley1808.fastrtp.config.Config;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class CooldownManager {
    private static final Object2LongOpenHashMap<UUID> COOLDOWNS = new Object2LongOpenHashMap<>();

    public static long getCooldownInSeconds(UUID uuid) {
        return (COOLDOWNS.getLong(uuid) - System.currentTimeMillis()) / 1000;
    }

    public static boolean hasCooldown(UUID uuid) {
        long time = COOLDOWNS.getLong(uuid);
        if (time == 0L) return false;

        if (System.currentTimeMillis() > time - 1000) {
            COOLDOWNS.removeLong(uuid);
            return false;
        }

        return true;
    }

    public static void addCooldown(ServerPlayer player) {
        int cooldown = Config.instance().cooldown;
        if (cooldown != -1 && !Permissions.check(player, Permission.BYPASS_COOLDOWN, 2)) {
            COOLDOWNS.put(player.getUUID(), System.currentTimeMillis() + (cooldown * 1000L));
        }
    }

    public static void removeCooldown(UUID uuid) {
        COOLDOWNS.removeLong(uuid);
    }
}
