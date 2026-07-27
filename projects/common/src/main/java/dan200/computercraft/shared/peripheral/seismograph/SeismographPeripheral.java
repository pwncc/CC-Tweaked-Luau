// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.seismograph;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.AttachedComputerSet;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jspecify.annotations.Nullable;

/**
 * The seismograph detects the movement of nearby entities, streaming "vibrations" to attached computers.
 * <p>
 * Every quarter of a second, the seismograph scans for entities near the block. The radius scales with the
 * {@linkplain #setSensitivity(int) sensitivity}. If any entity moved this scan, a {@code seismic_event} event is
 * queued on all attached computers for the strongest vibration, with the name of the peripheral, the magnitude of the
 * vibration, the horizontal offset of its source from the block, and the type of entity that caused it.
 * <p>
 * The seismograph also tracks a rolling average of recent vibrations, available via {@link #getActivity()}.
 *
 * @cc.usage Wait for something to move nearby, and report where the vibration came from.
 *
 * <pre>{@code
 * local _, name, magnitude, dx, dz, entity = os.pullEvent("seismic_event")
 * print(("%s felt a %.2f vibration from %s at (%.1f, %.1f)"):format(name, magnitude, entity, dx, dz))
 * }</pre>
 * @cc.module seismograph
 */
public final class SeismographPeripheral implements IPeripheral {
    private final SeismographBlockEntity seismograph;
    private final AttachedComputerSet computers = new AttachedComputerSet();

    SeismographPeripheral(SeismographBlockEntity seismograph) {
        this.seismograph = seismograph;
    }

    @Override
    public String getType() {
        return "seismograph";
    }

    /**
     * Set the sensitivity of the seismograph.
     * <p>
     * Sensitivity controls the radius of the scan, which is {@code 4 + 2 * sensitivity} blocks.
     *
     * @param sensitivity The new sensitivity, between 0 and 5.
     * @throws LuaException If the sensitivity is out of range.
     */
    @LuaFunction(mainThread = true)
    public void setSensitivity(int sensitivity) throws LuaException {
        if (sensitivity < SeismographBlockEntity.MIN_SENSITIVITY || sensitivity > SeismographBlockEntity.MAX_SENSITIVITY) {
            throw new LuaException("Sensitivity out of range (expected " + SeismographBlockEntity.MIN_SENSITIVITY + "-" + SeismographBlockEntity.MAX_SENSITIVITY + ")");
        }
        seismograph.setSensitivity(sensitivity);
    }

    /**
     * Get the current sensitivity of the seismograph.
     *
     * @return The current sensitivity, between 0 and 5.
     */
    @LuaFunction
    public int getSensitivity() {
        return seismograph.getSensitivity();
    }

    /**
     * Get the rolling average of recent vibrations.
     * <p>
     * This rises while entities move nearby, and decays back towards zero when things are quiet.
     *
     * @return The current activity level.
     */
    @LuaFunction
    public double getActivity() {
        return Math.round(seismograph.getActivity() * 100) / 100.0;
    }

    /**
     * Determine whether the area around the seismograph is calm.
     *
     * @return Whether the current {@linkplain #getActivity() activity} is below 0.1.
     */
    @LuaFunction
    public boolean isCalm() {
        return seismograph.getActivity() < 0.1;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return this == other || (other instanceof SeismographPeripheral o && seismograph == o.seismograph);
    }

    @Override
    public void attach(IComputerAccess computer) {
        computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        computers.remove(computer);
    }

    void queueSeismicEvent(double magnitude, double dx, double dz, String entity) {
        computers.forEach(c -> c.queueEvent("seismic_event", c.getAttachmentName(), magnitude, dx, dz, entity));
    }
}
