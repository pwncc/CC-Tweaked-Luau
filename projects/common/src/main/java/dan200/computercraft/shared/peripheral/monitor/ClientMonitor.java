// SPDX-FileCopyrightText: 2018 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.peripheral.monitor;

import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.shared.computer.terminal.NetworkedTerminal;
import dan200.computercraft.shared.computer.terminal.TerminalState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The client-side state of an in-world terminal, used by monitors and billboards.
 * <p>
 * This holds the current {@link NetworkedTerminal} (updated from {@linkplain TerminalState terminal states} sent by
 * the server), and an opaque {@link RenderState} for the renderer's use.
 */
public final class ClientMonitor {
    private final BlockEntity origin;

    private @Nullable NetworkedTerminal terminal;
    private boolean terminalChanged;
    private @Nullable RenderState state;

    public ClientMonitor(BlockEntity origin) {
        this.origin = origin;
    }

    public BlockEntity getOrigin() {
        return origin;
    }

    /**
     * Get or create the current render state.
     *
     * @param create A factory to create the render state.
     * @param <T>    The current render state. This type parameter should only be inhabited by a single class.
     * @return This monitor's render state.
     */
    @SuppressWarnings("unchecked")
    public <T extends RenderState> T getRenderState(Supplier<T> create) {
        var state = this.state;
        return (T) (state != null ? state : (this.state = create.get()));
    }

    public void destroy() {
        if (state != null) state.close();
        state = null;
    }

    public boolean pollTerminalChanged() {
        var changed = terminalChanged;
        terminalChanged = false;
        return changed;
    }

    public @Nullable Terminal getTerminal() {
        return terminal;
    }

    public void read(@Nullable TerminalState state) {
        if (state != null) {
            if (terminal == null) {
                terminal = state.create();
            } else {
                state.apply(terminal);
            }
            terminalChanged = true;
        } else {
            if (terminal != null) {
                terminal = null;
                terminalChanged = true;
            }
        }
    }

    /**
     * An interface representing the current state of the monitor renderer.
     * <p>
     * This interface should only be inhabited by {@link dan200.computercraft.client.render.monitor.MonitorRenderState}:
     * it exists solely to avoid referencing client-side classes in common code.
     */
    public interface RenderState extends AutoCloseable {
        @Override
        void close();
    }
}
