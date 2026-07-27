// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.chromalamp;

import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.shared.util.BlockEntityHelpers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block entity for chroma lamps. This stores the lamp's colour (synced to clients, where it is applied as a block
 * tint) and brightness, along with the state of any in-progress {@linkplain ChromaLampPeripheral#pulse(java.util.Optional)
 * pulse}.
 */
public final class ChromaLampBlockEntity extends BlockEntity {
    private static final String NBT_COLOUR = "Colour";
    private static final String NBT_BRIGHTNESS = "Brightness";
    private static final String NBT_PULSE = "Pulse";

    public static final int DEFAULT_COLOUR = 0xFFFFFF;
    static final int MAX_BRIGHTNESS = 15;

    private final ChromaLampPeripheral peripheral = new ChromaLampPeripheral(this);

    private int colour = DEFAULT_COLOUR;
    private int brightness;
    private int pulseTicks;

    public ChromaLampBlockEntity(BlockEntityType<ChromaLampBlockEntity> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    void serverTick() {
        if (pulseTicks > 0 && --pulseTicks == 0) {
            setLightLevel(brightness);
            setChanged();
        }
    }

    public int getColour() {
        return colour;
    }

    void setColour(int colour) {
        if (this.colour == colour) return;
        this.colour = colour;
        BlockEntityHelpers.updateBlock(this);
    }

    int getBrightness() {
        return brightness;
    }

    void setBrightness(int brightness) {
        this.brightness = brightness;
        pulseTicks = 0;
        setLightLevel(brightness);
        setChanged();
    }

    void pulse(int ticks) {
        pulseTicks = ticks;
        setLightLevel(MAX_BRIGHTNESS);
        setChanged();
    }

    private void setLightLevel(int light) {
        var level = getLevel();
        if (level == null) return;

        var state = getBlockState();
        if (state.getValue(ChromaLampBlock.LEVEL) != light) {
            level.setBlock(getBlockPos(), state.setValue(ChromaLampBlock.LEVEL, light), Block.UPDATE_ALL);
        }
    }

    public IPeripheral peripheral() {
        return peripheral;
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        var oldColour = colour;
        if (tag.contains(NBT_COLOUR, Tag.TAG_ANY_NUMERIC)) colour = tag.getInt(NBT_COLOUR) & 0xFFFFFF;
        brightness = Mth.clamp(tag.getInt(NBT_BRIGHTNESS), 0, MAX_BRIGHTNESS);
        pulseTicks = Math.max(0, tag.getInt(NBT_PULSE));

        // If the colour has changed on the client, re-render the block to apply the new tint.
        var level = getLevel();
        if (colour != oldColour && level != null && level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putInt(NBT_COLOUR, colour);
        if (brightness != 0) tag.putInt(NBT_BRIGHTNESS, brightness);
        if (pulseTicks != 0) tag.putInt(NBT_PULSE, pulseTicks);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag = super.getUpdateTag(registries);
        tag.putInt(NBT_COLOUR, colour);
        return tag;
    }
}
