package me.wesley1808.fastrtp.util;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import me.wesley1808.fastrtp.config.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.UUID;
import java.util.function.Consumer;

public final class PositionLocator {
    private static final Object2ObjectOpenHashMap<UUID, PositionLocator> LOCATORS = new Object2ObjectOpenHashMap<>();
    private static final ObjectOpenHashSet<UUID> PENDING_REMOVAL = new ObjectOpenHashSet<>();
    private static final TicketType<ChunkPos> LOCATE = TicketType.create("locate", Comparator.comparingLong(ChunkPos::toLong), 200);
    private static final RandomSource RANDOM = RandomSource.createNewThreadLocalInstance();
    private static final int MAX_SAFETY_CHECK_RADIUS = 4;
    private static final int MAX_ATTEMPTS = 256;
    private final ServerLevel level;
    private final UUID uuid;
    private final int minRadius;
    private final int radius;
    private final int centerX;
    private final int centerZ;
    private Consumer<Vec3> callback;
    private ChunkPos queuedPos;
    private long stopTime;
    private int attempts;
    private int x;
    private int z;

    public static boolean isLocating(ServerPlayer player) {
        return LOCATORS.containsKey(player.getUUID());
    }

    public static void update() {
        if (LOCATORS.size() > 0) {
            for (PositionLocator locator : LOCATORS.values()) {
                locator.tick();
            }

            for (UUID uuid : PENDING_REMOVAL) {
                LOCATORS.remove(uuid);
            }

            PENDING_REMOVAL.clear();
        }
    }

    public PositionLocator(ServerLevel level, UUID uuid, int radius, int minRadius) {
        this.level = level;
        this.uuid = uuid;
        this.radius = radius >> 4;
        this.minRadius = minRadius >> 4;

        WorldBorder border = this.level.getWorldBorder();
        this.centerX = (int) border.getCenterX() >> 4;
        this.centerZ = (int) border.getCenterZ() >> 4;
    }

    private void tick() {
        if (System.currentTimeMillis() <= this.stopTime) {
            LevelChunk chunk = Util.getChunkIfLoaded(this.level, this.queuedPos.x, this.queuedPos.z);
            if (chunk != null) {
                this.onChunkLoaded(chunk);
            }
        } else {
            this.onChunkLoaded(null);
        }
    }

    public void findPosition(Consumer<Vec3> callback) {
        this.callback = callback;
        this.stopTime = System.currentTimeMillis() + 10000;
        this.newPosition();
    }

    private void newPosition() {
        if (++this.attempts > MAX_ATTEMPTS || System.currentTimeMillis() > this.stopTime) {
            this.callback.accept(null);
            return;
        }

        ChunkPos pos = RANDOM.nextBoolean()
                ? new ChunkPos(this.nextRandomValueWithMinimum(this.centerX), this.nextRandomValue(this.centerZ))
                : new ChunkPos(this.nextRandomValue(this.centerX), this.nextRandomValueWithMinimum(this.centerZ));

        this.x = pos.getMiddleBlockX();
        this.z = pos.getMiddleBlockZ();

        // Quick checks to skip a lot of unnecessary chunk loading.
        if (this.isValid(pos)) {
            this.queueChunk(pos);
        } else {
            this.newPosition();
        }
    }

    private void queueChunk(ChunkPos pos) {
        this.level.getChunkSource().addRegionTicket(LOCATE, pos, 0, pos);
        this.queuedPos = pos;
        PENDING_REMOVAL.remove(this.uuid);
        LOCATORS.put(this.uuid, this);
    }

    private void onChunkLoaded(LevelChunk chunk) {
        PENDING_REMOVAL.add(this.uuid);

        if (chunk == null) {
            this.callback.accept(null);
            return;
        }

        Vec3 pos = this.findSafePositionInChunk(chunk, this.x, this.z);
        if (pos != null) {
            this.callback.accept(pos);
            return;
        }

        this.newPosition();
    }

    private Vec3 findSafePositionInChunk(LevelChunk chunk, int centerX, int centerZ) {
        int negativeDiff = 8 - Mth.clamp(Config.instance().safetyCheckRadius, 1, MAX_SAFETY_CHECK_RADIUS);
        int positiveDiff = negativeDiff - 1;

        for (int x = centerX - negativeDiff; x <= centerX + positiveDiff; x++) {
            for (int z = centerZ - negativeDiff; z <= centerZ + positiveDiff; z++) {
                int y = this.getY(chunk, x, z);
                if (this.isSafe(chunk, x, y, z)) {
                    return new Vec3(x + 0.5D, y + 1, z + 0.5D);
                }
            }
        }

        return null;
    }

    private boolean isSafe(LevelChunk chunk, int centerX, int y, int centerZ) {
        // Early return if the landing position isn't safe.
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(centerX, y, centerZ);
        if (!this.isSafeBelowPlayer(chunk.getBlockState(mutable)) ||
            !this.isSafeSurroundingPlayer(chunk.getBlockState(mutable.move(Direction.UP))) ||
            !this.level.noCollision(EntityType.PLAYER.getSpawnAABB(centerX + 0.5D, y + 1, centerZ + 0.5D))
        ) {
            return false;
        }

        int radius = Math.min(Config.instance().safetyCheckRadius, MAX_SAFETY_CHECK_RADIUS);
        if (radius > 0) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    if (x != centerX || z != centerZ) {
                        BlockState state = chunk.getBlockState(mutable.set(x, y, z));
                        if (!this.isSafeSurroundingBelowPlayer(state) || !this.isSafeSurroundingPlayer(chunk.getBlockState(mutable.move(Direction.UP)))) {
                            return false;
                        }
                    }
                }
            }
        }

        return true;
    }

    private boolean isSafeBelowPlayer(BlockState state) {
        Block block = state.getBlock();
        return this.isSafeSurroundingBelowPlayer(state) && block != Blocks.BAMBOO;
    }

    private boolean isSafeSurroundingBelowPlayer(BlockState state) {
        Block block = state.getBlock();
        return (state.blocksMotion() || block == Blocks.SNOW) &&
               block != Blocks.CACTUS &&
               block != Blocks.MAGMA_BLOCK;
    }

    private boolean isSafeSurroundingPlayer(BlockState state) {
        Block block = state.getBlock();
        return !state.is(BlockTags.FIRE) &&
               !state.is(BlockTags.CAMPFIRES) &&
               block != Blocks.LAVA &&
               block != Blocks.POWDER_SNOW &&
               block != Blocks.MAGMA_BLOCK &&
               block != Blocks.CACTUS &&
               block != Blocks.SWEET_BERRY_BUSH;
    }

    private int getY(LevelChunk chunk, int x, int z) {
        if (!this.level.dimensionType().hasCeiling()) {
            return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }

        int bottomY = chunk.getMinBuildHeight();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(x, this.level.getLogicalHeight(), z);

        boolean isAir = false;
        boolean isAirBelow = false;
        while (mutable.getY() >= bottomY && isAirBelow || !isAir) {
            isAir = isAirBelow;
            isAirBelow = chunk.getBlockState(mutable.move(Direction.DOWN)).isAir();
        }

        return mutable.getY();
    }

    private boolean isValid(ChunkPos pos) {
        if (this.level.getWorldBorder().isWithinBounds(pos)) {
            return this.isBiomeValid(this.level.getBiome(new BlockPos(this.x, this.level.getLogicalHeight(), this.z)));
        }
        return false;
    }

    private boolean isBiomeValid(Holder<Biome> biome) {
        for (TagKey<Biome> biomeTag : Config.instance().blackListedBiomeTags) {
            if (biome.is(biomeTag)) {
                return false;
            }
        }

        ResourceKey<Biome> key = this.level.registryAccess().registryOrThrow(Registries.BIOME).getResourceKey(biome.value()).orElse(null);
        return !Config.instance().blackListedBiomes.contains(key);
    }

    private int nextRandomValue(int center) {
        return Mth.nextInt(RANDOM, center - this.radius, center + this.radius);
    }

    private int nextRandomValueWithMinimum(int center) {
        return RANDOM.nextBoolean()
                ? Mth.nextInt(RANDOM, center + this.minRadius, center + this.radius)
                : Mth.nextInt(RANDOM, center - this.radius, center - this.minRadius);
    }
}