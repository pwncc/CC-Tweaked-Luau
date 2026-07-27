// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.seismograph;

import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The block entity for seismographs. This periodically scans for moving entities near the block, tracking a rolling
 * "activity" level and reporting the largest vibration of each scan to attached computers.
 */
public final class SeismographBlockEntity extends BlockEntity {
    private static final String NBT_SENSITIVITY = "Sensitivity";

    static final int MIN_SENSITIVITY = 0;
    static final int MAX_SENSITIVITY = 5;
    private static final int DEFAULT_SENSITIVITY = 2;

    /**
     * How often (in ticks) we scan for nearby entities.
     */
    private static final int SCAN_INTERVAL = 5;

    /**
     * The minimum magnitude a vibration must have to be reported.
     */
    private static final double THRESHOLD = 0.05;

    /**
     * How much of the previous activity level is kept each scan.
     */
    private static final double ACTIVITY_DECAY = 0.8;

    private final SeismographPeripheral peripheral = new SeismographPeripheral(this);

    private volatile int sensitivity = DEFAULT_SENSITIVITY;
    private volatile double activity;
    private long clock;

    public SeismographBlockEntity(BlockEntityType<SeismographBlockEntity> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    void serverTick() {
        if (++clock % SCAN_INTERVAL != 0) return;

        var level = getLevel();
        if (level == null) return;

        var radius = 4 + 2 * sensitivity;
        var centre = Vec3.atCenterOf(getBlockPos());
        var bounds = new AABB(
            centre.x - radius, centre.y - radius, centre.z - radius,
            centre.x + radius, centre.y + radius, centre.z + radius
        );

        Entity largest = null;
        var largestMagnitude = 0.0;
        for (var entity : level.getEntitiesOfClass(Entity.class, bounds)) {
            var magnitude = getMagnitude(entity);
            if (magnitude >= THRESHOLD && magnitude > largestMagnitude) {
                largest = entity;
                largestMagnitude = magnitude;
            }
        }

        activity = activity * ACTIVITY_DECAY + largestMagnitude * (1 - ACTIVITY_DECAY);

        if (largest != null) {
            peripheral.queueSeismicEvent(
                Math.round(largestMagnitude * 100) / 100.0,
                Math.round((largest.getX() - centre.x) * 10) / 10.0,
                Math.round((largest.getZ() - centre.z) * 10) / 10.0,
                EntityType.getKey(largest.getType()).toString()
            );
        }
    }

    /**
     * Compute the magnitude of an entity's "vibration", based on its speed and size.
     *
     * @param entity The entity to measure.
     * @return The vibration magnitude, between 0 and 10.
     */
    private static double getMagnitude(Entity entity) {
        var speed = entity.getDeltaMovement().length();
        var bounds = entity.getBoundingBox();
        var volume = Mth.clamp(bounds.getXsize() * bounds.getYsize() * bounds.getZsize(), 0.2, 5.0);
        return Math.min(10, speed * 10 * volume);
    }

    int getSensitivity() {
        return sensitivity;
    }

    void setSensitivity(int sensitivity) {
        this.sensitivity = sensitivity;
        setChanged();
    }

    double getActivity() {
        return activity;
    }

    public IPeripheral peripheral() {
        return peripheral;
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        sensitivity = tag.contains(NBT_SENSITIVITY, Tag.TAG_ANY_NUMERIC)
            ? Mth.clamp(tag.getInt(NBT_SENSITIVITY), MIN_SENSITIVITY, MAX_SENSITIVITY)
            : DEFAULT_SENSITIVITY;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(NBT_SENSITIVITY, sensitivity);
    }
}
