// Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
//
// SPDX-License-Identifier: LicenseRef-CCPL

package dan200.computercraft.shared.computer.core;

import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.component.AdminComputer;
import dan200.computercraft.api.component.ComputerComponent;
import dan200.computercraft.api.component.ComputerComponents;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.WorkMonitor;
import dan200.computercraft.core.computer.Computer;
import dan200.computercraft.core.computer.ComputerEnvironment;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.core.input.EventComputerInput;
import dan200.computercraft.core.input.UserComputerInput;
import dan200.computercraft.core.metrics.MetricsObserver;
import dan200.computercraft.impl.ApiFactories;
import dan200.computercraft.shared.computer.menu.ComputerMenu;
import dan200.computercraft.shared.computer.terminal.NetworkedTerminal;
import dan200.computercraft.shared.computer.terminal.TerminalState;
import dan200.computercraft.shared.config.Config;
import dan200.computercraft.shared.network.NetworkMessage;
import dan200.computercraft.shared.network.client.ClientNetworkContext;
import dan200.computercraft.shared.network.client.ComputerTerminalClientMessage;
import dan200.computercraft.shared.network.server.ServerNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class ServerComputer implements ComputerEnvironment {
    public static final ComputerComponent<MetricsObserver> METRICS = ComputerComponent.create("computercraft", "metrics");

    private final int instanceID;
    private final UUID instanceUUID = UUID.randomUUID();

    private ServerLevel level;
    private BlockPos position;

    private final ComputerFamily family;
    private final MetricsObserver metrics;
    private final Computer computer;

    private final NetworkedTerminal terminal;
    private final AtomicBoolean terminalChanged = new AtomicBoolean(false);

    // The sub-tick terminal sync: terminal changes are pushed to watching
    // players the moment they happen (rate-limited), rather than waiting for
    // the next server tick, so screens update at display rather than tick
    // rate. The watcher set is refreshed once per tick on the server thread;
    // sends happen from the computer thread, which Netty hands off safely.
    private static final long FAST_SYNC_INTERVAL_NS = fastSyncInterval();
    private final ConcurrentHashMap<ServerPlayer, AbstractContainerMenu> terminalWatchers = new ConcurrentHashMap<>();
    private volatile long lastFastSync;

    private static long fastSyncInterval() {
        var hz = Integer.getInteger("cc.terminal_sync_hz", 60);
        return hz <= 0 ? Long.MAX_VALUE : 1_000_000_000L / hz;
    }

    private int ticksSincePing;

    public ServerComputer(ServerLevel level, BlockPos position, Properties properties) {
        this.level = level;
        this.position = position;
        this.family = properties.family;

        var context = ServerContext.get(level.getServer());
        instanceID = context.registry().getUnusedInstanceID();
        terminal = new NetworkedTerminal(properties.terminalWidth, properties.terminalHeight, family != ComputerFamily.NORMAL, this::markTerminalChanged);
        metrics = context.metrics().createMetricObserver(this);

        properties.addComponent(METRICS, metrics);
        if (family == ComputerFamily.COMMAND) {
            properties.addComponent(ComputerComponents.ADMIN_COMPUTER, new AdminComputer() {
            });
        }
        var components = Map.copyOf(properties.components);

        computer = new Computer(context.computerContext(), this, terminal, properties.computerID);
        computer.setLabel(properties.label);

        // Load in the externally registered APIs.
        for (var factory : ApiFactories.getAll()) {
            var system = new ComputerSystem(this, computer.getAPIEnvironment(), components);
            var api = factory.create(system);
            if (api == null) continue;

            system.activate();
            computer.addApi(api, system);
        }
    }

    public final ComputerFamily getFamily() {
        return family;
    }

    public final ServerLevel getLevel() {
        return level;
    }

    public final BlockPos getPosition() {
        return position;
    }

    public final void setPosition(ServerLevel level, BlockPos pos) {
        this.level = level;
        position = pos.immutable();
    }

    protected final void markTerminalChanged() {
        terminalChanged.set(true);

        if (!terminalWatchers.isEmpty()) {
            var now = System.nanoTime();
            if (now - lastFastSync >= FAST_SYNC_INTERVAL_NS && terminalChanged.getAndSet(false)) {
                lastFastSync = now;
                var state = getTerminalState();
                terminalWatchers.forEach((player, menu) -> {
                    if (!player.hasDisconnected()) {
                        ServerNetworking.sendToPlayer(new ComputerTerminalClientMessage(menu, state), player);
                    }
                });
            }
        }
    }

    protected void tickServer() {
        ticksSincePing++;

        // Refresh the players watching this terminal. Menus opened or closed
        // mid-tick reach the fast path within a tick; the client discards
        // updates for menus it no longer has open.
        terminalWatchers.clear();
        for (var player : level.getServer().getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof ComputerMenu menu && menu.getComputer() == this) {
                terminalWatchers.put(player, player.containerMenu);
            }
        }

        computer.tick();
        if (terminalChanged.getAndSet(false)) onTerminalChanged();
    }

    protected void onTerminalChanged() {
        sendToAllInteracting(c -> new ComputerTerminalClientMessage(c, getTerminalState()));
    }

    public final TerminalState getTerminalState() {
        return TerminalState.create(terminal);
    }

    public final void keepAlive() {
        ticksSincePing = 0;
    }

    boolean hasTimedOut() {
        return ticksSincePing > 100;
    }

    /**
     * Get a bitmask returning which sides on the computer have changed, resetting the internal state.
     *
     * @return What sides on the computer have changed.
     */
    public final int pollRedstoneChanges() {
        return computer.pollRedstoneChanges();
    }

    public UUID register() {
        ServerContext.get(level.getServer()).registry().add(this);
        return instanceUUID;
    }

    void unload() {
        computer.unload();
    }

    public final void close() {
        unload();
        ServerContext.get(level.getServer()).registry().remove(this);
    }

    /**
     * Check whether this computer is usable by a player.
     *
     * @param player The player trying to use this computer.
     * @return Whether this computer can be used.
     */
    public final boolean checkUsable(Player player) {
        return ServerContext.get(level.getServer()).registry().get(instanceUUID) == this
            && getFamily().checkUsable(player);
    }

    private void sendToAllInteracting(Function<AbstractContainerMenu, NetworkMessage<ClientNetworkContext>> createPacket) {
        var server = level.getServer();

        for (var player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof ComputerMenu menu && menu.getComputer() == this) {
                ServerNetworking.sendToPlayer(createPacket.apply(player.containerMenu), player);
            }
        }
    }

    protected void onRemoved() {
    }

    public final int getInstanceID() {
        return instanceID;
    }

    public final UUID getInstanceUUID() {
        return instanceUUID;
    }

    public final int getID() {
        return computer.getID();
    }

    public final @Nullable String getLabel() {
        return computer.getLabel();
    }

    public final boolean isOn() {
        return computer.isOn();
    }

    public final ComputerState getState() {
        if (!computer.isOn()) return ComputerState.OFF;
        return computer.isBlinking() ? ComputerState.BLINKING : ComputerState.ON;
    }

    public final void turnOn() {
        computer.turnOn();
    }

    public final void shutdown() {
        computer.shutdown();
    }

    public final void reboot() {
        computer.reboot();
    }

    public final void queueEvent(String event, @Nullable Object @Nullable [] arguments) {
        computer.queueEvent(event, arguments);
    }

    public final void queueEvent(String event) {
        queueEvent(event, null);
    }

    public final UserComputerInput createComputerInput() {
        return new UserComputerInput(new EventComputerInput(computer), terminal);
    }

    public final int getRedstoneOutput(ComputerSide side) {
        return computer.isOn() ? computer.getRedstone().getExternalOutput(side) : 0;
    }

    public final void setRedstoneInput(ComputerSide side, int level, int bundledState) {
        computer.getRedstone().setInput(side, level, bundledState);
    }

    public final int getBundledRedstoneOutput(ComputerSide side) {
        return computer.isOn() ? computer.getRedstone().getExternalBundledOutput(side) : 0;
    }

    public final void setPeripheral(ComputerSide side, @Nullable IPeripheral peripheral) {
        computer.getEnvironment().setPeripheral(side, peripheral);
    }

    @Nullable
    public final IPeripheral getPeripheral(ComputerSide side) {
        return computer.getEnvironment().getPeripheral(side);
    }

    public final void setLabel(@Nullable String label) {
        computer.setLabel(label);
    }

    @Override
    public final double getTimeOfDay() {
        return (level.getDayTime() + 6000) % 24000 / 1000.0;
    }

    @Override
    public final int getDay() {
        return (int) ((level.getDayTime() + 6000) / 24000) + 1;
    }

    @Override
    public final MetricsObserver getMetrics() {
        return metrics;
    }

    public final WorkMonitor getMainThreadMonitor() {
        return computer.getMainThreadMonitor();
    }

    @Override
    public final WritableMount createRootMount() {
        return ComputerCraftAPI.createSaveDirMount(level.getServer(), "computer/" + computer.getID(), Config.computerSpaceLimit);
    }

    public static Properties properties(int computerID, ComputerFamily family) {
        return new Properties(computerID, family);
    }

    public static final class Properties {
        private final int computerID;
        private @Nullable String label;
        private final ComputerFamily family;

        private int terminalWidth = Config.DEFAULT_COMPUTER_TERM_WIDTH;
        private int terminalHeight = Config.DEFAULT_COMPUTER_TERM_HEIGHT;
        private final Map<ComputerComponent<?>, Object> components = new HashMap<>();

        private Properties(int computerID, ComputerFamily family) {
            this.computerID = computerID;
            this.family = family;
        }

        public Properties label(@Nullable String label) {
            this.label = label;
            return this;
        }

        public Properties terminalSize(int width, int height) {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Terminal size must be positive");
            this.terminalWidth = width;
            this.terminalHeight = height;
            return this;
        }

        public <T> Properties addComponent(ComputerComponent<T> component, T value) {
            if (components.containsKey(component)) throw new IllegalArgumentException(component + " is already set");
            components.put(component, value);
            return this;
        }
    }
}
