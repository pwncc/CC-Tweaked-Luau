// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.cassette;

import dan200.computercraft.api.lua.Coerced;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.AttachedComputerSet;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.core.util.StringUtil;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Cassette decks store large amounts of data on cassette tapes. Unlike floppy disks, a cassette does not hold a file
 * system, but a single stream of raw bytes, which is read and written sequentially from the current tape position.
 * <p>
 * Moving the tape head with {@link #seek} or {@link #rewind} happens in "real time": the tape winds at 512&nbsp;kB/s,
 * so travelling a long way across a large tape takes several seconds. While the tape is winding, reads, writes and
 * label changes fail, and {@link #isReady} returns {@code false}. When winding finishes, a {@code cassette_ready}
 * event is queued on all attached computers, with the name of the peripheral.
 * <p>
 * When a cassette is inserted or removed, a {@code cassette} event is queued, with the name of the peripheral and
 * either {@code "inserted"} or {@code "ejected"}.
 * <p>
 * ## Recipe
 * <div class="recipe-container">
 *     <mc-recipe recipe="computercraft:cassette_deck"></mc-recipe>
 *     <mc-recipe recipe="computercraft:cassette"></mc-recipe>
 * </div>
 *
 * @cc.module cassette_deck
 */
public final class CassetteDeckPeripheral implements IPeripheral {
    /**
     * The default number of bytes read by {@link #read}.
     */
    private static final int DEFAULT_READ_COUNT = 8192;

    /**
     * The maximum number of bytes read or written in a single call.
     */
    private static final int MAX_IO_COUNT = 65536;

    private final CassetteDeckBlockEntity deck;
    private final AttachedComputerSet computers = new AttachedComputerSet();

    CassetteDeckPeripheral(CassetteDeckBlockEntity deck) {
        this.deck = deck;
    }

    @Override
    public String getType() {
        return "cassette_deck";
    }

    /**
     * Check whether the deck is ready to read or write.
     * <p>
     * Unlike the other methods on the deck, this does not error when no cassette is present, and so can be used to
     * poll for the tape becoming usable.
     *
     * @return Whether a cassette is inserted and the tape is not winding.
     */
    @LuaFunction(mainThread = true)
    public boolean isReady() {
        return deck.isReady();
    }

    /**
     * Get the total capacity of the inserted cassette, in bytes.
     *
     * @return The capacity of the cassette.
     * @throws LuaException If no cassette is inserted.
     */
    @LuaFunction(mainThread = true)
    public long getSize() throws LuaException {
        return deck.getCapacity();
    }

    /**
     * Get the current position of the tape head, in bytes from the start of the tape.
     *
     * @return The current tape position.
     * @throws LuaException If no cassette is inserted.
     */
    @LuaFunction(mainThread = true)
    public long getPosition() throws LuaException {
        return deck.getPosition();
    }

    /**
     * Get the number of bytes between the current position and the end of the tape.
     *
     * @return The remaining number of bytes.
     * @throws LuaException If no cassette is inserted.
     */
    @LuaFunction(mainThread = true)
    public long getRemaining() throws LuaException {
        return deck.getRemaining();
    }

    /**
     * Read some bytes from the tape, starting at the current position.
     * <p>
     * This advances the position by the number of bytes actually read. Regions of the tape which have never been
     * written read as {@code \0} bytes.
     *
     * @param count The number of bytes to read, between 0 and 65536. Defaults to 8192.
     * @return The bytes read, or the empty string if at the end of the tape.
     * @throws LuaException If no cassette is inserted, or the tape is winding.
     */
    @LuaFunction(mainThread = true)
    public byte[] read(Optional<Integer> count) throws LuaException {
        var toRead = count.orElse(DEFAULT_READ_COUNT);
        if (toRead < 0) throw new LuaException("Cannot read a negative number of bytes");
        return deck.read(Math.min(toRead, MAX_IO_COUNT));
    }

    /**
     * Write some bytes to the tape at the current position.
     * <p>
     * This advances the position by the number of bytes written. Writes are truncated at the end of the tape, in
     * which case fewer bytes than provided are written.
     *
     * @param data The bytes to write, at most 65536 per call.
     * @return The number of bytes actually written.
     * @throws LuaException If no cassette is inserted, the tape is winding, or too much data was provided.
     */
    @LuaFunction(mainThread = true)
    public int write(Coerced<ByteBuffer> data) throws LuaException {
        var bytes = data.value();
        if (bytes.remaining() > MAX_IO_COUNT) throw new LuaException("Cannot write more than " + MAX_IO_COUNT + " bytes");
        return deck.write(bytes);
    }

    /**
     * Start winding the tape by a relative offset.
     * <p>
     * The target position is clamped to the bounds of the tape. Winding happens over time (at 512&nbsp;kB/s), during
     * which the deck is not {@linkplain #isReady() ready}. Seeking while the tape is already winding replaces the
     * previous target, computing the new one from the current position. Once the target is reached, a
     * {@code cassette_ready} event is queued.
     *
     * @param offset The number of bytes to wind by. Positive values wind forwards, negative backwards.
     * @return The time the wind will take, in seconds. This is 0 if the tape is already at the target position.
     * @throws LuaException If no cassette is inserted.
     */
    @LuaFunction(mainThread = true)
    public double seek(long offset) throws LuaException {
        return deck.seek(offset);
    }

    /**
     * Start winding the tape back to the start, equivalent to {@code seek(-getPosition())}.
     *
     * @return The time the wind will take, in seconds. This is 0 if the tape is already at the start.
     * @throws LuaException If no cassette is inserted.
     */
    @LuaFunction(mainThread = true)
    public double rewind() throws LuaException {
        return deck.rewind();
    }

    /**
     * Get the label of the inserted cassette.
     *
     * @return The label of the cassette, or {@code nil} if it has none.
     * @throws LuaException If no cassette is inserted, or the tape is winding.
     * @cc.treturn string|nil The label of the cassette, or {@code nil} if it has none.
     */
    @LuaFunction(mainThread = true)
    public @Nullable String getLabel() throws LuaException {
        return deck.getLabel();
    }

    /**
     * Set or clear the label of the inserted cassette.
     *
     * @param label The new label of the cassette, or {@code nil} to clear it.
     * @throws LuaException If no cassette is inserted, or the tape is winding.
     */
    @LuaFunction(mainThread = true)
    public void setLabel(Optional<String> label) throws LuaException {
        deck.setLabel(label.map(StringUtil::normaliseLabel).orElse(null));
    }

    /**
     * Eject the cassette from the deck, dropping it into the world.
     *
     * @throws LuaException If no cassette is inserted.
     */
    @LuaFunction(mainThread = true)
    public void eject() throws LuaException {
        deck.eject();
    }

    @Override
    public void attach(IComputerAccess computer) {
        computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        computers.remove(computer);
    }

    void queueCassetteChanged(String change) {
        computers.forEach(c -> c.queueEvent("cassette", c.getAttachmentName(), change));
    }

    void queueCassetteReady() {
        computers.forEach(c -> c.queueEvent("cassette_ready", c.getAttachmentName()));
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return this == other || (other instanceof CassetteDeckPeripheral o && deck == o.deck);
    }

    @Override
    public Object getTarget() {
        return deck;
    }
}
