package me.wesley1808.fastrtp.util;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import me.wesley1808.fastrtp.config.Config;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class Scheduler {
    private static final ScheduledThreadPoolExecutor SCHEDULER = new ScheduledThreadPoolExecutor(0);
    private static final ObjectOpenHashSet<UUID> ACTIVE = new ObjectOpenHashSet<>();

    public static boolean canSchedule(UUID uuid) {
        return !ACTIVE.contains(uuid);
    }

    public static void schedule(long millis, Runnable runnable) {
        SCHEDULER.schedule(runnable, millis, TimeUnit.MILLISECONDS);
    }

    public static void shutdown() {
        SCHEDULER.shutdown();
    }

    public static void scheduleTeleport(ServerPlayer player, Runnable onSuccess, Runnable onFail) {
        ACTIVE.add(player.getUUID());
        teleportLoop(3, player.server, player.getUUID(), player.position(), onSuccess, onFail);
    }

    private static void teleportLoop(int seconds, MinecraftServer server, UUID uuid, Vec3 oldPos, Runnable onSuccess, Runnable onFail) {
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null || !player.position().closerThan(oldPos, 2)) {
            ACTIVE.remove(uuid);
            onFail.run();
            return;
        }

        player.displayClientMessage(Util.format(Config.instance().messages.tpSecondsLeft
                .replace("${seconds}", String.valueOf(seconds))
                .replace("seconds", seconds == 1 ? "second" : "seconds")
        ), false);

        if (seconds == 1) {
            schedule(1000, () -> {
                onSuccess.run();
                ACTIVE.remove(uuid);
            });
        } else {
            schedule(1000, () -> teleportLoop(seconds - 1, server, uuid, oldPos, onSuccess, onFail));
        }
    }
}