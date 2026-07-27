// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.computer.blocks;

import dan200.computercraft.api.component.ComputerComponent;
import dan200.computercraft.shared.computer.core.ComputerFamily;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.computer.core.TerminalSize;
import dan200.computercraft.shared.computer.terminal.TerminalState;
import dan200.computercraft.shared.display.PixelBuffer;
import dan200.computercraft.shared.network.client.BillboardClientMessage;
import dan200.computercraft.shared.network.client.PixelDisplayMessage;
import dan200.computercraft.shared.network.server.ServerNetworking;
import dan200.computercraft.shared.peripheral.monitor.ClientMonitor;
import dan200.computercraft.shared.util.BlockEntityHelpers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A computer whose terminal is rendered on the front face of the block, like a monitor built into the computer.
 * <p>
 * Right clicking the block still opens the normal computer GUI. The server periodically pushes the terminal contents
 * to all players tracking the chunk (see {@link #serverTick()}), which the client stores in a {@link ClientMonitor}
 * (see {@link #readDisplayState(TerminalState)}) for the block entity renderer to draw.
 */
public class BillboardBlockEntity extends ComputerBlockEntity {
    /**
     * The component identifying billboards, used to expose the {@code display} API to their computers.
     */
    public static final ComputerComponent<BillboardBlockEntity> COMPONENT = ComputerComponent.create("computercraft", "billboard");

    public static final int NO_CHANNEL = -1;

    // 26x12 characters (156x108 pixels) matches the aspect ratio of the screen inset on the block's front
    // texture, so the in-world display fills it edge to edge.
    private static final TerminalSize TERMINAL_SIZE = new TerminalSize(26, 12);

    private static final String NBT_CHANNEL = "ViewChannel";

    /**
     * How often (in ticks) the terminal contents are pushed to clients.
     */
    private static final int SYNC_INTERVAL = 5;

    /**
     * The in-world display state. This is only defined on the client.
     */
    private @Nullable ClientMonitor clientDisplay;

    /**
     * The camera channel this billboard shows instead of its terminal, or {@link #NO_CHANNEL}. Synced to clients.
     */
    private int viewChannel = NO_CHANNEL;

    /**
     * How often (in ticks) at most the pixel buffer is pushed to clients.
     */
    private static final int PIXEL_SYNC_INTERVAL = 4;

    /**
     * The Lua-owned pixel buffer, when the screen is in graphics mode. Server-side only.
     */
    private @Nullable PixelBuffer graphics;
    private long lastPixelSync = Long.MIN_VALUE;

    public BillboardBlockEntity(BlockEntityType<? extends ComputerBlockEntity> type, BlockPos pos, BlockState state, ComputerFamily family) {
        super(type, pos, state, family);
    }

    @Override
    protected void configureComputer(ServerComputer.Properties properties) {
        properties.addComponent(COMPONENT, this);
    }

    public int getViewChannel() {
        return viewChannel;
    }

    /**
     * Tune this billboard to a camera channel (or back to its own terminal), syncing the change to viewers.
     *
     * @param channel The channel to show, or {@link #NO_CHANNEL} for the terminal.
     */
    public void setViewChannel(int channel) {
        if (viewChannel == channel) return;
        viewChannel = channel;
        if (getLevel() != null && !getLevel().isClientSide) BlockEntityHelpers.updateBlock(this);
    }

    @Override
    protected TerminalSize defaultTerminalSize() {
        return TERMINAL_SIZE;
    }

    /**
     * Enter (or resize) graphics mode.
     *
     * @param width  The buffer width, in pixels.
     * @param height The buffer height, in pixels.
     */
    public void setGraphicsMode(int width, int height) {
        graphics = new PixelBuffer(width, height);
    }

    /**
     * Leave graphics mode, returning the screen to its terminal (or tuned channel).
     */
    public void clearGraphicsMode() {
        if (graphics == null) return;
        graphics = null;
        if (getLevel() instanceof ServerLevel level) {
            ServerNetworking.sendToAllTracking(PixelDisplayMessage.cleared(getBlockPos()), level.getChunkAt(getBlockPos()));
        }
    }

    /**
     * Get the pixel buffer, if in graphics mode.
     *
     * @return The pixel buffer.
     */
    public @Nullable PixelBuffer getGraphics() {
        return graphics;
    }

    /**
     * Get the client-side display for this billboard, creating it if needed.
     *
     * @return The client-side display, or {@code null} when called on the server.
     */
    public @Nullable ClientMonitor getClientDisplay() {
        var level = getLevel();
        if (level == null || !level.isClientSide) return null;

        if (clientDisplay == null) clientDisplay = new ClientMonitor(this);
        return clientDisplay;
    }

    /**
     * Read a terminal state received from the server.
     *
     * @param state The new terminal state.
     */
    public void readDisplayState(TerminalState state) {
        var display = getClientDisplay();
        if (display != null) display.read(state);
    }

    @Override
    public void saveAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.saveAdditional(nbt, registries);
        if (viewChannel != NO_CHANNEL) nbt.putInt(NBT_CHANNEL, viewChannel);
    }

    @Override
    protected void loadServer(CompoundTag nbt, HolderLookup.Provider registries) {
        super.loadServer(nbt, registries);
        viewChannel = nbt.contains(NBT_CHANNEL) ? nbt.getInt(NBT_CHANNEL) : NO_CHANNEL;
    }

    @Override
    protected void loadClient(CompoundTag nbt, HolderLookup.Provider registries) {
        super.loadClient(nbt, registries);
        viewChannel = nbt.contains(NBT_CHANNEL) ? nbt.getInt(NBT_CHANNEL) : NO_CHANNEL;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag = super.getUpdateTag(registries);
        if (viewChannel != NO_CHANNEL) tag.putInt(NBT_CHANNEL, viewChannel);
        return tag;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (clientDisplay != null) clientDisplay.destroy();
    }

    @Override
    protected void serverTick() {
        super.serverTick();
        if (!(getLevel() instanceof ServerLevel level)) return;

        syncPixels(level);

        // Periodically push the terminal contents to all players tracking this chunk. We don't create a computer just
        // to render its (empty) terminal - only running (or previously run) computers are sent.
        if (level.getGameTime() % SYNC_INTERVAL != 0) return;

        var computer = getServerComputer();
        if (computer == null) return;

        var chunk = level.getChunkAt(getBlockPos());
        if (level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false).isEmpty()) return;

        ServerNetworking.sendToAllTracking(new BillboardClientMessage(getBlockPos(), computer.getTerminalState()), chunk);
    }

    private void syncPixels(ServerLevel level) {
        var graphics = this.graphics;
        if (graphics == null) return;

        // Push when dirty (rate-capped), plus a periodic refresh so players who came in range see the picture.
        var refresh = level.getGameTime() % 100 == 0;
        if (!graphics.pollDirty() && !refresh) return;
        if (!refresh && level.getGameTime() - lastPixelSync < PIXEL_SYNC_INTERVAL) {
            graphics.markDirty();
            return;
        }

        lastPixelSync = level.getGameTime();
        var chunk = level.getChunkAt(getBlockPos());
        if (level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false).isEmpty()) return;
        ServerNetworking.sendToAllTracking(PixelDisplayMessage.of(getBlockPos(), graphics), chunk);
    }
}
