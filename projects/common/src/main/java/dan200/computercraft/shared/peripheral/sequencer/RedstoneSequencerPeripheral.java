// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.sequencer;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.AttachedComputerSet;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The redstone sequencer plays redstone patterns without any further help from a computer. A computer uploads a
 * pattern once, and the sequencer then plays it back tick-accurately, with none of the timing jitter that comes from
 * driving redstone from Lua.
 * <p>
 * Each side of the block stores its own pattern. A pattern is a list of durations (in game ticks, so 20ths of a
 * second), which alternate between powered and unpowered, starting powered. For instance, the pattern {@code {2, 18}}
 * emits a two tick pulse every second.
 * <p>
 * When a non-looping pattern finishes, a {@code sequencer_done} event is queued on all attached computers, with the
 * name of the peripheral and the side that finished.
 *
 * @cc.module redstone_sequencer
 */
public final class RedstoneSequencerPeripheral implements IPeripheral {
    /**
     * The maximum number of durations in a single pattern.
     */
    private static final int MAX_SEGMENTS = 100;

    /**
     * The maximum length of a single duration, in ticks.
     */
    private static final int MAX_SEGMENT_TICKS = 600;

    private final RedstoneSequencerBlockEntity sequencer;
    private final AttachedComputerSet computers = new AttachedComputerSet();

    RedstoneSequencerPeripheral(RedstoneSequencerBlockEntity sequencer) {
        this.sequencer = sequencer;
    }

    @Override
    public String getType() {
        return "redstone_sequencer";
    }

    /**
     * Set the pattern for one side of the block, and immediately start playing it.
     * <p>
     * The durations alternate between powered and unpowered, starting powered. Patterns loop by default, and keep
     * playing even when no computer is attached.
     *
     * @param side      The side to set the pattern for. One of "north", "south", "east", "west", "up" or "down".
     * @param durations A list of 1-100 durations, each between 1 and 600 game ticks.
     * @param loop      Whether the pattern should loop. Defaults to {@code true}.
     * @throws LuaException If the side or pattern is invalid.
     * @cc.usage Blink the block north of the sequencer, half a second on and half a second off.
     *
     * <pre>{@code
     * local sequencer = peripheral.find("redstone_sequencer")
     * sequencer.setPattern("north", { 10, 10 })
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public void setPattern(String side, Map<?, ?> durations, Optional<Boolean> loop) throws LuaException {
        var direction = parseSide(side);
        var pattern = parseDurations(durations);
        sequencer.setPattern(direction, pattern, loop.orElse(true));
    }

    /**
     * Stop playback and remove the pattern for one side, or all sides.
     *
     * @param side The side to clear. If not given, all sides are cleared.
     * @throws LuaException If the side is invalid.
     */
    @LuaFunction(mainThread = true)
    public void clear(Optional<String> side) throws LuaException {
        sequencer.clear(side.isPresent() ? parseSide(side.get()) : null);
    }

    /**
     * Check whether a side is currently playing a pattern.
     *
     * @param side The side to check.
     * @return Whether this side is currently playing.
     * @throws LuaException If the side is invalid.
     */
    @LuaFunction(mainThread = true)
    public boolean isRunning(String side) throws LuaException {
        return sequencer.isRunning(parseSide(side));
    }

    /**
     * Get the pattern stored for a side.
     *
     * @param side The side to query.
     * @return A copy of this side's durations, or {@code nil} if no pattern is stored.
     * @throws LuaException If the side is invalid.
     */
    @LuaFunction(mainThread = true)
    public @Nullable List<Integer> getPattern(String side) throws LuaException {
        var pattern = sequencer.getPattern(parseSide(side));
        return pattern == null ? null : Arrays.stream(pattern).boxed().toList();
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return this == other || (other instanceof RedstoneSequencerPeripheral o && sequencer == o.sequencer);
    }

    @Override
    public void attach(IComputerAccess computer) {
        computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        computers.remove(computer);
    }

    void queueDone(Direction side) {
        computers.forEach(c -> c.queueEvent("sequencer_done", c.getAttachmentName(), side.getName()));
    }

    private static Direction parseSide(String side) throws LuaException {
        var direction = Direction.byName(side);
        if (direction == null) throw new LuaException("Invalid side '" + side + "'");
        return direction;
    }

    private static int[] parseDurations(Map<?, ?> table) throws LuaException {
        var count = 0;
        while (count < MAX_SEGMENTS && getIndex(table, count + 1) != null) count++;
        if (count == 0) throw new LuaException("Pattern must contain at least one duration");
        if (count == MAX_SEGMENTS && getIndex(table, MAX_SEGMENTS + 1) != null) {
            throw new LuaException("Pattern is too long (at most " + MAX_SEGMENTS + " durations)");
        }

        var durations = new int[count];
        for (var i = 0; i < count; i++) {
            if (!(getIndex(table, i + 1) instanceof Number number)) {
                throw new LuaException("Bad duration #" + (i + 1) + " (number expected)");
            }

            var ticks = number.doubleValue();
            if (!Double.isFinite(ticks) || ticks < 1 || ticks > MAX_SEGMENT_TICKS) {
                throw new LuaException("Bad duration #" + (i + 1) + " (expected number between 1 and " + MAX_SEGMENT_TICKS + ")");
            }
            durations[i] = (int) ticks;
        }
        return durations;
    }

    private static @Nullable Object getIndex(Map<?, ?> table, int index) {
        var value = table.get((double) index);
        return value != null ? value : table.get(index);
    }
}
