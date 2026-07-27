// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.computer.apis;

import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.Optional;

/**
 * The alert API allows computers to notify nearby players directly, without a monitor or chat integration: messages
 * appear on the action bar (above the hotbar) of every player in range, with an optional audible ping.
 * <p>
 * This is deliberately short-range and rate-limited: it is a doorbell, not a broadcast tower.
 *
 * @cc.module alert
 */
public class AlertAPI implements ILuaAPI {
    private static final int MAX_MESSAGE_LENGTH = 256;
    private static final double DEFAULT_RANGE = 16;
    private static final double MAX_RANGE = 64;
    private static final long MIN_INTERVAL_NS = 250_000_000; // 4 alerts a second.

    private final IComputerSystem computer;
    private long lastAlert = 0;

    public AlertAPI(IComputerSystem computer) {
        this.computer = computer;
    }

    @Override
    public String[] getNames() {
        return new String[]{ "alert" };
    }

    private void checkRateLimit() throws LuaException {
        var now = System.nanoTime();
        if (lastAlert != 0 && now - lastAlert < MIN_INTERVAL_NS) {
            throw new LuaException("Too many alerts (at most 4 per second)");
        }
        lastAlert = now;
    }

    private static double checkRange(Optional<Double> range) throws LuaException {
        var value = range.orElse(DEFAULT_RANGE);
        if (!Double.isFinite(value) || value < 1 || value > MAX_RANGE) {
            throw new LuaException("Range must be between 1 and " + (int) MAX_RANGE);
        }
        return value;
    }

    /**
     * Show a message on the action bar of every player near this computer.
     * <p>
     * If the computer has a label, it is shown before the message.
     *
     * @param message The message to show. At most 256 characters.
     * @param range   The distance (in blocks) players must be within, between 1 and 64. Defaults to 16.
     * @return The number of players who saw the message.
     * @throws LuaException If the message is too long, the range is invalid, or alerts are sent too quickly.
     * @cc.usage Warn anyone nearby that the reactor is having a moment.
     *
     * <pre>{@code
     * alert.broadcast("Reactor temperature critical!", 32)
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final int broadcast(String message, Optional<Double> range) throws LuaException {
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new LuaException("Message too long (at most " + MAX_MESSAGE_LENGTH + " characters)");
        }
        checkRateLimit();
        var distance = checkRange(range);

        var label = computer.getLabel();
        var shown = label == null ? message : "[" + label + "] " + message;
        var component = Component.literal(shown);

        var level = computer.getLevel();
        var centre = computer.getPosition().getCenter();
        var count = 0;
        for (var player : level.players()) {
            if (player.position().distanceToSqr(centre) <= distance * distance) {
                player.displayClientMessage(component, true);
                count++;
            }
        }
        return count;
    }

    /**
     * Play a short "ping" sound from this computer, audible to nearby players.
     *
     * @param pitch The pitch to play at, between 0.5 and 2. Defaults to 1.2.
     * @throws LuaException If the pitch is invalid or alerts are sent too quickly.
     */
    @LuaFunction(mainThread = true)
    public final void ping(Optional<Double> pitch) throws LuaException {
        var tone = pitch.orElse(1.2);
        if (!Double.isFinite(tone) || tone < 0.5 || tone > 2) throw new LuaException("Pitch must be between 0.5 and 2");
        checkRateLimit();

        var pos = computer.getPosition();
        computer.getLevel().playSound(
            null, pos, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.RECORDS, 1.0f, (float) (double) tone
        );
    }
}
