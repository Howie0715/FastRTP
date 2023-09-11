package me.wesley1808.fastrtp.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import me.wesley1808.fastrtp.config.Config;
import me.wesley1808.fastrtp.config.ConfigHandler;
import me.wesley1808.fastrtp.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.arguments.DimensionArgument.dimension;
import static net.minecraft.commands.arguments.DimensionArgument.getDimension;
import static net.minecraft.commands.arguments.EntityArgument.getPlayer;
import static net.minecraft.commands.arguments.EntityArgument.player;

public final class RandomTeleportCommand {
    private static final TicketType<Integer> PRE_TELEPORT = TicketType.create("pre_teleport", Integer::compareTo, 70);
    private static final Object2ObjectOpenHashMap<UUID, Pair<ServerLevel, Vec3>> RTP_COORDS = new Object2ObjectOpenHashMap<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("rtp")
                .requires(src -> !Config.instance().requirePermission || Permission.check(src, Permission.COMMAND_RTP, 2))
                .executes(ctx -> execute(ctx.getSource()))

                .then(literal("reload")
                        .requires(Permission.require(Permission.COMMAND_RELOAD, 2))
                        .executes(ctx -> reloadConfig(ctx.getSource()))
                )

                .then(argument("player", player())
                        .requires(Permission.require(Permission.COMMAND_RTP_ADVANCED, 2))
                        .executes(ctx -> execute(ctx.getSource(), getPlayer(ctx, "player")))

                        .then(argument("world", dimension())
                                .executes(ctx -> execute(ctx.getSource(), getPlayer(ctx, "player"), getDimension(ctx, "world")))

                                .then(argument("radius", integer(0))
                                        .executes(ctx -> execute(ctx.getSource(), getPlayer(ctx, "player"), getDimension(ctx, "world"), getInteger(ctx, "radius")))

                                        .then(argument("minRadius", integer(0))
                                                .executes(ctx -> execute(ctx.getSource(), getPlayer(ctx, "player"), getDimension(ctx, "world"), getInteger(ctx, "radius"), getInteger(ctx, "minRadius")))
                                        )
                                )
                        )
                )
        );

        dispatcher.register(literal("rtpback").executes(ctx -> executeBack(ctx.getSource().getPlayerOrException())));
    }

    private static int execute(CommandSourceStack source) throws CommandSyntaxException {
        ResourceKey<Level> playerLevel = source.getPlayerOrException().level().dimension();
        if (playerLevel.equals(Level.OVERWORLD) || playerLevel.equals(Level.NETHER) || playerLevel.equals(Level.END)){
            return execute(source, source.getPlayerOrException());
        }else {
            source.getPlayerOrException().sendSystemMessage(Component.literal("您無法在此維度隨機傳送"),true);
            return 0;
        }
    }

    private static int execute(CommandSourceStack source, ServerPlayer player) {
        return execute(source, player, Util.getLevel(player));
    }

    private static int execute(CommandSourceStack source, ServerPlayer player, ServerLevel level) {
        return execute(source, player, level, Util.getRadius(level));
    }

    private static int execute(CommandSourceStack source, ServerPlayer player, ServerLevel level, int radius) {
        return execute(source, player, level, radius, Config.instance().minRadius);
    }

    private static int execute(CommandSourceStack source, ServerPlayer player, ServerLevel level, int radius, int minRadius) {
        return execute(source, player, level, radius, minRadius, source.getPlayer() != player);
    }

    private static int execute(CommandSourceStack source, ServerPlayer player, ServerLevel level, int radius, int minRadius, boolean force) {
        if (minRadius > radius) {
            source.sendFailure(Component.literal("The minimum radius cannot be larger than the maximum radius!"));
            return 0;
        }

        if (PositionLocator.isLocating(player) || !Scheduler.canSchedule(player.getUUID())) {
            // If the player is already locating a random position, don't start a new one.
            return 0;
        }

        Config.Messages messages = Config.instance().messages;
        if (!force && CooldownManager.hasCooldown(player.getUUID())) {
            String seconds = String.valueOf(CooldownManager.getCooldownInSeconds(player.getUUID()));
            player.displayClientMessage(Util.format(messages.rtpOnCooldown.replace("${seconds}", seconds)), false);
            return 0;
        }

        String error = force ? null : Util.mayTeleport(player);
        if (error != null) {
            player.sendSystemMessage(Util.format(messages.preventedRtp.replace("${reason}", error)));
            return 0;
        }

        player.displayClientMessage(Util.format(messages.rtpStartSearch), true);
        CooldownManager.addCooldown(player);

        long startTime = System.currentTimeMillis();
        PositionLocator locator = new PositionLocator(level, player.getUUID(), radius, minRadius);

        locator.findPosition((pos) -> {
            if (pos == null) {
                player.sendSystemMessage(Util.format(messages.rtpLocNotFound));
                CooldownManager.removeCooldown(player.getUUID());
            } else {
                long elapsedTime = System.currentTimeMillis() - startTime;
                player.displayClientMessage(Util.format(messages.rtpLocFound.replace("${seconds}", String.format("%.1f", elapsedTime / 1000F))), true);

                if (force || !Config.instance().useStrictTeleportCheck) {
                    teleportPlayer(player, level, pos);
                } else {
                    Scheduler.scheduleTeleport(player,
                            () -> teleportPlayer(player, level, pos),
                            () -> player.displayClientMessage(Util.format(messages.tpCancelled), false)
                    );
                }
            }
        });

        return 1;
    }

    private static void teleportPlayer(ServerPlayer player, ServerLevel level, Vec3 pos) {
        if (player.isAlive()) {
            player.teleportTo(level, pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());
            player.connection.resetPosition();
            RTP_COORDS.put(player.getUUID(), new ObjectObjectImmutablePair<>(level, pos));

            player.sendSystemMessage(Util.format(Config.instance().messages.rtpTeleportPlayer
                    .replace("${x}", String.format("%.0f", pos.x))
                    .replace("${y}", String.format("%.0f", pos.y))
                    .replace("${z}", String.format("%.0f", pos.z))
                    .replace("${world}", level.dimension().location().getPath())
            ));
        }
    }

    private static int executeBack(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!Scheduler.canSchedule(uuid)) {
            return 0;
        }

        Config.Messages messages = Config.instance().messages;
        Pair<ServerLevel, Vec3> pair = RTP_COORDS.get(uuid);
        if (pair == null) {
            player.sendSystemMessage(Util.format(messages.rtpBackLocNotFound));
            return 0;
        }

        String error = Util.mayTeleport(player);
        if (error != null) {
            player.sendSystemMessage(Util.format(messages.preventedRtpBack.replace("${reason}", error)));
            return 0;
        }

        Vec3 pos = pair.right();
        ServerLevel level = pair.left();

        level.getChunkSource().addRegionTicket(PRE_TELEPORT, new ChunkPos(BlockPos.containing(pos)), 1, player.getId());
        Scheduler.scheduleTeleport(player, () -> {
            player.teleportTo(level, pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());
            player.connection.resetPosition();
            player.sendSystemMessage(Util.format(messages.rtpBackSuccess));
        }, () -> {
            player.displayClientMessage(Util.format(messages.tpCancelled), false);
        });

        return 1;
    }

    private static int reloadConfig(CommandSourceStack source) {
        ConfigHandler.load();
        source.sendSuccess(() -> Component.literal("Config reloaded!").withStyle(ChatFormatting.GREEN), false);
        return Command.SINGLE_SUCCESS;
    }
}