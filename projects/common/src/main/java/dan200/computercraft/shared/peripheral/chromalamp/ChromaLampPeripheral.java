// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.chromalamp;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

import static dan200.computercraft.api.lua.LuaValues.checkFinite;

/**
 * The chroma lamp is a computer-controlled RGB light. Computers can set its colour (a 24-bit RGB value, applied as a
 * tint to the block) and its brightness (the light level it emits, from 0 to 15), or briefly {@link #pulse} it to full
 * brightness.
 *
 * @cc.usage Turn the lamp orange, then flash it when a redstone signal arrives.
 *
 * <pre>{@code
 * local lamp = peripheral.find("chroma_lamp")
 * lamp.setColour(0xFF8800)
 * lamp.setBrightness(7)
 * while true do
 *   os.pullEvent("redstone")
 *   if redstone.getInput("top") then lamp.pulse() end
 * end
 * }</pre>
 * @cc.module chroma_lamp
 */
public final class ChromaLampPeripheral implements IPeripheral {
    private static final double DEFAULT_PULSE_SECONDS = 0.5;
    private static final double MAX_PULSE_SECONDS = 5;

    private final ChromaLampBlockEntity lamp;

    ChromaLampPeripheral(ChromaLampBlockEntity lamp) {
        this.lamp = lamp;
    }

    @Override
    public String getType() {
        return "chroma_lamp";
    }

    /**
     * Set the colour of the lamp.
     *
     * @param colour The new colour, as a 24-bit RGB value (between 0 and 0xFFFFFF).
     * @throws LuaException If the colour is out of range.
     */
    @LuaFunction(value = { "setColour", "setColor" }, mainThread = true)
    public void setColour(int colour) throws LuaException {
        if (colour < 0 || colour > 0xFFFFFF) throw new LuaException("Colour out of range (expected 0-0xFFFFFF)");
        lamp.setColour(colour);
    }

    /**
     * Get the colour of the lamp.
     *
     * @return The current colour, as a 24-bit RGB value.
     */
    @LuaFunction(value = { "getColour", "getColor" }, mainThread = true)
    public int getColour() {
        return lamp.getColour();
    }

    /**
     * Set the brightness of the lamp.
     *
     * @param brightness The light level to emit, between 0 and 15.
     * @throws LuaException If the brightness is out of range.
     */
    @LuaFunction(mainThread = true)
    public void setBrightness(int brightness) throws LuaException {
        if (brightness < 0 || brightness > ChromaLampBlockEntity.MAX_BRIGHTNESS) {
            throw new LuaException("Brightness out of range (expected 0-" + ChromaLampBlockEntity.MAX_BRIGHTNESS + ")");
        }
        lamp.setBrightness(brightness);
    }

    /**
     * Get the brightness of the lamp, as set by {@link #setBrightness}.
     *
     * @return The current brightness, between 0 and 15.
     */
    @LuaFunction(mainThread = true)
    public int getBrightness() {
        return lamp.getBrightness();
    }

    /**
     * Briefly flash the lamp at full brightness, before returning to the current
     * {@linkplain #setBrightness brightness}.
     *
     * @param duration The duration of the pulse in seconds, between 0 and 5. Defaults to 0.5 seconds.
     * @throws LuaException If the duration is out of range.
     */
    @LuaFunction(mainThread = true)
    public void pulse(Optional<Double> duration) throws LuaException {
        var seconds = checkFinite(0, duration.orElse(DEFAULT_PULSE_SECONDS));
        if (seconds <= 0 || seconds > MAX_PULSE_SECONDS) {
            throw new LuaException("Duration out of range (expected 0-" + (int) MAX_PULSE_SECONDS + " seconds)");
        }
        lamp.pulse(Math.max(1, (int) Math.round(seconds * 20)));
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return this == other || (other instanceof ChromaLampPeripheral o && lamp == o.lamp);
    }
}
