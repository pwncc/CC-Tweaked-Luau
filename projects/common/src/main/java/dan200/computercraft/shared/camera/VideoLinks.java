// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.camera;

import dan200.computercraft.impl.network.wired.WiredNodeImpl;
import dan200.computercraft.shared.ModRegistry;
import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import dan200.computercraft.shared.config.Config;
import dan200.computercraft.shared.peripheral.modem.wired.CableBlockEntity;
import dan200.computercraft.shared.peripheral.modem.wired.WiredModemFullBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Works out whether video can flow between a camera and a screen, using the same modems rednet does: wired modems
 * on a shared cable network carry it privately, wireless modems use their ordinary radio range, and ender modems
 * reach anywhere - across dimensions included.
 */
public final class VideoLinks {
    public static final int WIRED = 0;
    public static final int WIRELESS = 1;
    public static final int ENDER = 2;

    /**
     * One modem that can carry video.
     *
     * @param kind    The modem's kind; one of {@link #WIRED}, {@link #WIRELESS} or {@link #ENDER}.
     * @param pos     The modem's position (used for wireless range).
     * @param network The wired network this modem belongs to, for {@link #WIRED} modems.
     */
    public record Modem(int kind, BlockPos pos, @Nullable Object network) {
    }

    private VideoLinks() {
    }

    /**
     * Find the modems touching a block: wireless/ender modem blocks, and wired modems (whose cable network is
     * resolved for private closed-circuit links).
     *
     * @param level The level to search in.
     * @param pos   The block to search around.
     * @return The modems adjacent to that block.
     */
    public static List<Modem> modemsAround(ServerLevel level, BlockPos pos) {
        var found = new ArrayList<Modem>(2);
        for (var direction : Direction.values()) {
            var neighbour = pos.relative(direction);
            var state = level.getBlockState(neighbour);
            if (state.is(ModRegistry.Blocks.WIRELESS_MODEM_NORMAL.get())) {
                found.add(new Modem(WIRELESS, neighbour, null));
            } else if (state.is(ModRegistry.Blocks.WIRELESS_MODEM_ADVANCED.get())) {
                found.add(new Modem(ENDER, neighbour, null));
            } else {
                var network = wiredNetworkAt(level, neighbour);
                if (network != null) found.add(new Modem(WIRED, neighbour, network));
            }
        }
        return found;
    }

    /**
     * Find the modems serving a rig: those touching any of the rig's own blocks, plus those touching any computer
     * attached to the rig. Peripherals don't talk to the network themselves - the computer driving them does - so
     * a modem on the computer under a camera (or beside a monitor's computer) carries the video just as well as
     * one on the camera itself.
     *
     * @param level   The level to search in.
     * @param anchors The rig's own blocks (a camera, or every block of a monitor).
     * @return The modems serving the rig.
     */
    public static List<Modem> modemsForRig(ServerLevel level, Iterable<BlockPos> anchors) {
        var rig = new LinkedHashSet<BlockPos>();
        for (var anchor : anchors) {
            rig.add(anchor.immutable());
            for (var direction : Direction.values()) {
                var neighbour = anchor.relative(direction);
                if (level.getBlockEntity(neighbour) instanceof AbstractComputerBlockEntity) rig.add(neighbour);
            }
        }

        var seen = new LinkedHashSet<BlockPos>();
        var found = new ArrayList<Modem>(2);
        for (var pos : rig) {
            for (var modem : modemsAround(level, pos)) {
                if (seen.add(modem.pos())) found.add(modem);
            }
        }
        return found;
    }

    /**
     * The wired network of a cable or wired modem at a position.
     *
     * @param level The level to look in.
     * @param pos   The position to inspect.
     * @return The network's identity, or {@code null} when there is no wired modem here.
     */
    public static @Nullable Object wiredNetworkAt(ServerLevel level, BlockPos pos) {
        var element = switch (level.getBlockEntity(pos)) {
            case WiredModemFullBlockEntity modem -> modem.getElement();
            case CableBlockEntity cable -> cable.getWiredElement(null);
            case null, default -> null;
        };
        return element != null && element.getNode() instanceof WiredNodeImpl node ? node.getNetwork() : null;
    }

    /**
     * Whether video can flow from a camera's modems to a screen's modems.
     *
     * @param cameraLevel The camera's level.
     * @param transmitters The camera-side modems.
     * @param screenLevel The screen's level.
     * @param receivers   The screen-side modems.
     * @return Whether any transmitter reaches any receiver.
     */
    public static boolean linked(ServerLevel cameraLevel, List<Modem> transmitters, ServerLevel screenLevel, List<Modem> receivers) {
        for (var tx : transmitters) {
            for (var rx : receivers) {
                if (tx.kind() == ENDER || rx.kind() == ENDER) return true;

                if (tx.kind() == WIRELESS && rx.kind() == WIRELESS) {
                    if (cameraLevel != screenLevel) continue;
                    var range = wirelessRange(cameraLevel, tx.pos());
                    if (tx.pos().distSqr(rx.pos()) <= range * range) return true;
                }

                if (tx.kind() == WIRED && rx.kind() == WIRED && tx.network() == rx.network()) return true;
            }
        }
        return false;
    }

    /**
     * The radio range of a wireless modem, matching rednet's rules: better with altitude, worse in storms.
     *
     * @param level The modem's level.
     * @param pos   The modem's position.
     * @return The modem's range, in blocks.
     */
    public static double wirelessRange(ServerLevel level, BlockPos pos) {
        double minRange = Config.modemRange;
        double maxRange = Config.modemHighAltitudeRange;
        if (level.isRaining() && level.isThundering()) {
            minRange = Config.modemRangeDuringStorm;
            maxRange = Config.modemHighAltitudeRangeDuringStorm;
        }
        if (pos.getY() > 96 && maxRange > minRange) {
            return minRange + (pos.getY() - 96.0) * ((maxRange - minRange) / ((level.getMaxBuildHeight() - 1) - 96.0));
        }
        return minRange;
    }
}
