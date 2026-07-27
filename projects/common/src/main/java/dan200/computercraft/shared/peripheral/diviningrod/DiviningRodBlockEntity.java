// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.diviningrod;

import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block entity for divining rods. This is mostly passive, just tracking when the rod was last used so that
 * surveys can be rate-limited.
 */
public final class DiviningRodBlockEntity extends BlockEntity {
    private final DiviningRodPeripheral peripheral = new DiviningRodPeripheral(this);

    private long lastSurvey = Long.MIN_VALUE;

    public DiviningRodBlockEntity(BlockEntityType<DiviningRodBlockEntity> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    long getLastSurvey() {
        return lastSurvey;
    }

    void setLastSurvey(long time) {
        lastSurvey = time;
    }

    public IPeripheral peripheral() {
        return peripheral;
    }
}
