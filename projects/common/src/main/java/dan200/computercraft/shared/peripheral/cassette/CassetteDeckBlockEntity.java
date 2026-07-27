// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.cassette;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.shared.ModRegistry;
import dan200.computercraft.shared.common.AbstractContainerBlockEntity;
import dan200.computercraft.shared.computer.core.ServerContext;
import dan200.computercraft.shared.config.ConfigSpec;
import dan200.computercraft.shared.container.BasicContainer;
import dan200.computercraft.shared.util.DataComponentUtil;
import dan200.computercraft.shared.util.NonNegativeId;
import dan200.computercraft.shared.util.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The block entity for cassette decks. This holds the deck's single cassette slot, the current tape position and the
 * winding state, and performs the actual tape IO.
 * <p>
 * Tape data is stored in a raw binary file per cassette id ({@code computercraft/cassette/<id>.bin} inside the save
 * folder). The file is sparse: reads beyond the data written so far yield zero bytes, so a blank cassette reads as an
 * endless stream of {@code \0} up to its capacity. Ids are only allocated on the first write, so cassettes which have
 * never been written don't take up space on disk.
 * <p>
 * Unlike the disk drive, all peripheral methods run on the main thread, so this class needs none of the disk drive's
 * cross-thread machinery.
 *
 * @see CassetteDeckPeripheral
 */
public final class CassetteDeckBlockEntity extends AbstractContainerBlockEntity implements BasicContainer {
    /**
     * The speed the tape winds at, in bytes per second.
     */
    public static final long WIND_SPEED = 512_000;

    private static final long WIND_SPEED_PER_TICK = WIND_SPEED / 20;

    /**
     * The sub-path used for both allocating cassette ids and storing their data.
     */
    private static final String SUB_PATH = "cassette";

    private static final String NBT_ITEM = "Item";
    private static final String NBT_POSITION = "Position";
    private static final String NBT_TARGET = "Target";
    private static final String NBT_WINDING = "Winding";

    private final CassetteDeckPeripheral peripheral = new CassetteDeckPeripheral(this);

    private final NonNullList<ItemStack> inventory = NonNullList.withSize(1, ItemStack.EMPTY);

    /**
     * The cassette stack we last observed, used to detect inventory changes from {@link #setChanged()}.
     */
    private ItemStack lastCassette = ItemStack.EMPTY;

    private long position = 0;
    private long target = 0;
    private boolean winding = false;

    public CassetteDeckBlockEntity(BlockEntityType<CassetteDeckBlockEntity> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public IPeripheral peripheral() {
        return peripheral;
    }

    public Direction getDirection() {
        return getBlockState().getValue(CassetteDeckBlock.FACING);
    }

    void serverTick() {
        if (!winding) return;

        if (position < target) {
            position = Math.min(target, position + WIND_SPEED_PER_TICK);
        } else if (position > target) {
            position = Math.max(target, position - WIND_SPEED_PER_TICK);
        }

        if (position == target) {
            winding = false;
            peripheral.queueCassetteReady();
        }

        setChanged();
    }

    @Override
    public NonNullList<ItemStack> getItems() {
        return inventory;
    }

    @Override
    public void setItems(NonNullList<ItemStack> items) {
        BasicContainer.defaultSetItems(inventory, items);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return stack.is(ModRegistry.Items.CASSETTE.get());
    }

    @Override
    public void setChanged() {
        if (level != null && !level.isClientSide) cassetteChanged();
        super.setChanged();
    }

    /**
     * Called on the server when the inventory may have changed. If the cassette has, this resets the tape position
     * and queues {@code cassette} events on all attached computers.
     */
    private void cassetteChanged() {
        var stack = getCassette();
        if (ItemStack.isSameItemSameComponents(stack, lastCassette)) return;

        var hadCassette = !lastCassette.isEmpty();
        var hasCassette = !stack.isEmpty();
        lastCassette = stack.copy();

        // Any change to the cassette rewinds the deck and stops any winding in progress.
        position = 0;
        target = 0;
        winding = false;

        if (hadCassette) peripheral.queueCassetteChanged("ejected");
        if (hasCassette) peripheral.queueCassetteChanged("inserted");
    }

    ItemStack getCassette() {
        return getItem(0);
    }

    /**
     * Set the current cassette stack, firing events if needed.
     *
     * @param stack The new cassette stack.
     */
    void setCassette(ItemStack stack) {
        setItem(0, stack);
        setChanged();
    }

    /**
     * Mark the block entity as dirty after mutating the current cassette's components in place, without treating the
     * mutation as an insert/eject.
     */
    private void markCassetteDirty() {
        lastCassette = getCassette().copy();
        super.setChanged();
    }

    boolean isReady() {
        return !getCassette().isEmpty() && !winding;
    }

    long getCapacity() throws LuaException {
        checkCassette();
        return capacity();
    }

    long getPosition() throws LuaException {
        checkCassette();
        return position;
    }

    long getRemaining() throws LuaException {
        checkCassette();
        return Math.max(0, capacity() - position);
    }

    byte[] read(int count) throws LuaException {
        checkCassette();
        checkNotWinding();

        var length = (int) Math.min(count, capacity() - position);
        if (length <= 0) return new byte[0];

        var bytes = new byte[length];
        var id = NonNegativeId.getId(getCassette().get(ModRegistry.DataComponents.CASSETTE_ID.get()));
        if (id >= 0) {
            var path = getStoragePath(id);
            if (Files.isRegularFile(path)) {
                try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
                    var buffer = ByteBuffer.wrap(bytes);
                    var offset = position;
                    while (buffer.hasRemaining()) {
                        var read = channel.read(buffer, offset);
                        if (read < 0) break;
                        offset += read;
                    }
                } catch (IOException e) {
                    throw new LuaException("Failed to read cassette");
                }
            }
        }

        position += length;
        setChanged();
        return bytes;
    }

    int write(ByteBuffer data) throws LuaException {
        checkCassette();
        checkNotWinding();

        var length = (int) Math.min(data.remaining(), capacity() - position);
        if (length <= 0) return 0;

        var server = ((ServerLevel) getLevel()).getServer();
        var id = NonNegativeId.getOrCreate(server, getCassette(), ModRegistry.DataComponents.CASSETTE_ID.get(), SUB_PATH);

        var path = getStoragePath(id);
        try {
            Files.createDirectories(path.getParent());
            try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                var buffer = data.duplicate();
                buffer.limit(buffer.position() + length);
                var offset = position;
                while (buffer.hasRemaining()) {
                    offset += channel.write(buffer, offset);
                }
            }
        } catch (IOException e) {
            throw new LuaException("Failed to write cassette");
        }

        position += length;
        markCassetteDirty();
        return length;
    }

    double seek(long offset) throws LuaException {
        checkCassette();

        long newTarget;
        try {
            newTarget = Math.addExact(position, offset);
        } catch (ArithmeticException ignored) {
            newTarget = offset > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return windTo(newTarget);
    }

    double rewind() throws LuaException {
        checkCassette();
        return windTo(0);
    }

    private double windTo(long newTarget) {
        var clamped = Math.min(Math.max(newTarget, 0), capacity());
        if (clamped == position) {
            target = position;
            winding = false;
            setChanged();
            return 0;
        }

        target = clamped;
        winding = true;
        setChanged();
        return Math.abs(clamped - position) / (double) WIND_SPEED;
    }

    @Nullable
    String getLabel() throws LuaException {
        checkCassette();
        checkNotWinding();
        return DataComponentUtil.getCustomName(getCassette());
    }

    void setLabel(@Nullable String label) throws LuaException {
        checkCassette();
        checkNotWinding();
        DataComponentUtil.setCustomName(getCassette(), label);
        markCassetteDirty();
    }

    void eject() throws LuaException {
        checkCassette();

        var stack = getCassette();
        setCassette(ItemStack.EMPTY);

        WorldUtil.dropItemStack(getLevel(), getBlockPos(), getDirection(), stack);
        getLevel().levelEvent(LevelEvent.SOUND_DISPENSER_DISPENSE, getBlockPos(), 0);
    }

    private long capacity() {
        return ConfigSpec.cassetteCapacity.get();
    }

    private void checkCassette() throws LuaException {
        if (getCassette().isEmpty()) throw new LuaException("No cassette inserted");
    }

    private void checkNotWinding() throws LuaException {
        if (winding) throw new LuaException("The tape is winding");
    }

    private Path getStoragePath(int id) {
        var server = ((ServerLevel) getLevel()).getServer();
        return ServerContext.get(server).storageDir().resolve(SUB_PATH).resolve(id + ".bin");
    }

    @Override
    public void loadAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.loadAdditional(nbt, registries);

        var stack = nbt.contains(NBT_ITEM) ? ItemStack.parseOptional(registries, nbt.getCompound(NBT_ITEM)) : ItemStack.EMPTY;
        inventory.set(0, stack);
        lastCassette = stack.copy();

        position = Math.max(0, nbt.getLong(NBT_POSITION));
        target = Math.max(0, nbt.getLong(NBT_TARGET));
        winding = nbt.getBoolean(NBT_WINDING);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        var stack = getCassette();
        if (!stack.isEmpty()) tag.put(NBT_ITEM, stack.save(registries));

        tag.putLong(NBT_POSITION, position);
        tag.putLong(NBT_TARGET, target);
        tag.putBoolean(NBT_WINDING, winding);
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return new CassetteDeckMenu(id, inventory, this);
    }
}
