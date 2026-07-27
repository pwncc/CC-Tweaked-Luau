// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.client.render.remoteview;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * A tiny read-only "world" assembled from streamed camera sections, sufficient for the vanilla block renderer to
 * tesselate against: block/fluid state lookups, shading, tints, and per-block light (the server streams each
 * section's block and sky light alongside its contents, so views are lit like the real world).
 */
final class RemoteViewWorld implements BlockAndTintGetter {
    /**
     * A section's streamed light data, as vanilla-style nibble arrays.
     *
     * @param block The block light nibbles.
     * @param sky   The sky light nibbles.
     */
    record SectionLight(byte[] block, byte[] sky) {
    }

    // Concurrent: the bake thread reads these while the main thread applies streamed updates.
    private final Map<Long, LevelChunkSection> sections = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, SectionLight> lights = new java.util.concurrent.ConcurrentHashMap<>();
    private final int minSectionY;
    private final int sectionCount;

    // A one-entry memo for light lookups: meshing samples light in tight spatial clusters, and the map lookup
    // (with its Long boxing) is hot enough to matter. Only touched by the bake thread.
    private long lastLightKey = Long.MIN_VALUE;
    private @Nullable SectionLight lastLight;

    private @Nullable Biome biome;

    RemoteViewWorld(int minSectionY, int sectionCount) {
        this.minSectionY = minSectionY;
        this.sectionCount = sectionCount;
    }

    void putSection(long sectionPos, @Nullable LevelChunkSection section, @Nullable SectionLight light) {
        if (section == null) {
            sections.remove(sectionPos);
        } else {
            sections.put(sectionPos, section);
        }
        if (light == null) {
            lights.remove(sectionPos);
        } else {
            lights.put(sectionPos, light);
        }
    }

    boolean isEmpty() {
        return sections.isEmpty();
    }

    int sectionCount() {
        return sections.size();
    }

    int getMinSectionYRaw() {
        return minSectionY;
    }

    /**
     * Take a shallow snapshot of the current sections, for baking on another thread. Sections are replaced whole
     * when updated, so the snapshot stays internally consistent.
     *
     * @return A copy of the section map.
     */
    Map<Long, LevelChunkSection> snapshotSections() {
        return new HashMap<>(sections);
    }

    @Nullable
    private LevelChunkSection sectionAt(BlockPos pos) {
        return sections.get(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        var section = sectionAt(pos);
        if (section == null) return Blocks.AIR.defaultBlockState();
        return section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return sectionCount * 16;
    }

    @Override
    public int getMinBuildHeight() {
        return minSectionY * 16;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        // Overworld shading constants, matching Level.getShade for non-constant-ambient-light dimensions.
        if (!shade) return 1.0f;
        return switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }

    @Override
    public LevelLightEngine getLightEngine() {
        // Never used: getBrightness/getRawBrightness are overridden below, which is all the block renderer touches.
        throw new UnsupportedOperationException("Remote views have no light engine");
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        var key = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        SectionLight light;
        if (key == lastLightKey) {
            light = lastLight;
        } else {
            light = lights.get(key);
            lastLightKey = key;
            lastLight = light;
        }
        // Unstreamed positions read as daylight, so the picture's edges are bright rather than pitch black.
        if (light == null) return layer == LightLayer.SKY ? 15 : 0;

        var index = (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15);
        var nibbles = layer == LightLayer.SKY ? light.sky() : light.block();
        return nibbles[index >> 1] >> ((index & 1) << 2) & 0xF;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return Math.max(getBrightness(LightLayer.SKY, pos) - amount, getBrightness(LightLayer.BLOCK, pos));
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        // Remote sections do not carry biome context for tinting; a fixed temperate biome gives plausible
        // grass/foliage/water colours everywhere.
        var biome = this.biome;
        if (biome == null) {
            var level = Minecraft.getInstance().level;
            if (level == null) return 0xFFFFFF;
            biome = this.biome = level.registryAccess().registryOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS);
        }
        return colorResolver.getColor(biome, pos.getX(), pos.getZ());
    }
}
