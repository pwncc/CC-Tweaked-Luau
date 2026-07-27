// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.camera;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.shared.network.client.CameraCaptureMessage;
import dan200.computercraft.shared.network.server.CameraFrameMessage;
import dan200.computercraft.shared.network.server.ServerNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns camera views into pixels for Lua: the server-side half of {@code camera.capture()}.
 * <p>
 * The server cannot render, so a capture <em>leases</em> a connected player's client: the client watches the
 * camera's channel through the ordinary streaming path, renders it off-screen, and uploads the pixels back in
 * {@linkplain CameraFrameMessage chunks}. Once reassembled, a {@code camera_frame} event wakes the computer that
 * asked, and the frame is handed over as an RGB332 byte string.
 * <p>
 * Requests are queued from computer threads and processed on the server tick, so all mutable state here is either
 * concurrent (the queue and results) or touched only by the server thread (the in-flight table).
 */
public final class CameraSnapshots {
    private static final Logger LOG = LoggerFactory.getLogger(CameraSnapshots.class);

    public static final String EVENT = "camera_frame";
    public static final int MAX_WIDTH = 1280;
    public static final int MAX_HEIGHT = 720;

    /** How long a capture may take end-to-end before it fails. The client gives up sooner (15s). */
    private static final long REQUEST_TIMEOUT_NS = 20_000_000_000L;
    /** How long a finished frame waits for its computer to collect it. */
    private static final long RESULT_TIMEOUT_NS = 60_000_000_000L;
    private static final int MAX_IN_FLIGHT = 16;

    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private static final ConcurrentLinkedQueue<Request> pending = new ConcurrentLinkedQueue<>();
    private static final Map<Long, Request> inFlight = new HashMap<>();
    private static final ConcurrentHashMap<Long, Result> results = new ConcurrentHashMap<>();

    private CameraSnapshots() {
    }

    /**
     * Begin a capture and wait for its frame. Called from a computer thread.
     *
     * @param camera   The camera to capture from.
     * @param computer The computer to wake when the frame is ready.
     * @param width    The requested width, in pixels.
     * @param height   The requested height, in pixels.
     * @return The {@code camera.capture()} method result.
     * @throws LuaException If the resolution is out of range.
     */
    public static MethodResult capture(CameraSource camera, IComputerAccess computer, int width, int height) throws LuaException {
        if (width < 1 || height < 1 || width > MAX_WIDTH || height > MAX_HEIGHT) {
            throw new LuaException("Resolution out of range (expected 1x1 to " + MAX_WIDTH + "x" + MAX_HEIGHT + ")");
        }

        var request = new Request(NEXT_ID.getAndIncrement(), camera, computer, width, height, System.nanoTime() + REQUEST_TIMEOUT_NS);
        pending.add(request);
        return waitForFrame(request.id, width, height);
    }

    private static MethodResult waitForFrame(long id, int width, int height) {
        return MethodResult.pullEvent(EVENT, event -> {
            if (event.length < 2 || !(event[1] instanceof Number number) || number.longValue() != id) {
                return waitForFrame(id, width, height); // Someone else's frame: keep waiting.
            }

            var result = results.remove(id);
            if (result == null || result.frame == null) {
                return MethodResult.of(null, result == null ? "Capture failed" : result.error);
            }

            var frame = new HashMap<String, Object>(4);
            frame.put("width", width);
            frame.put("height", height);
            frame.put("format", "rgb332");
            frame.put("data", result.frame);
            return MethodResult.of(frame);
        });
    }

    /**
     * Process queued requests and expire stale state. Called once per server tick.
     *
     * @param server The current server.
     */
    public static void tick(MinecraftServer server) {
        var now = System.nanoTime();

        Request queued;
        while ((queued = pending.poll()) != null) start(server, queued, now);

        if (!inFlight.isEmpty()) {
            for (var iterator = inFlight.values().iterator(); iterator.hasNext(); ) {
                var request = iterator.next();
                if (now >= request.deadline) {
                    iterator.remove();
                    fail(request, "Timeout");
                } else if (request.lease == null || server.getPlayerList().getPlayer(request.lease) == null) {
                    iterator.remove();
                    fail(request, "The rendering player disconnected");
                }
            }
        }

        if (!results.isEmpty()) results.values().removeIf(result -> now >= result.deadline);
    }

    /**
     * Whether a player currently renders a capture for the server, exempting them from receive checks.
     *
     * @param player The player to look up.
     * @return Whether they hold a capture lease.
     */
    public static boolean isLeased(java.util.UUID player) {
        for (var request : inFlight.values()) {
            if (player.equals(request.lease)) return true;
        }
        return false;
    }

    /** Drop all state, e.g. when the server stops. */
    public static void reset() {
        pending.clear();
        inFlight.clear();
        results.clear();
    }

    private static void start(MinecraftServer server, Request request, long now) {
        var camera = request.camera;
        var level = camera.cameraLevel();
        if (now >= request.deadline || camera.isSourceRemoved() || level == null || level.getServer() != server) {
            fail(request, "The camera is gone");
            return;
        }

        var channel = camera.getChannel();
        if (channel == CameraSource.NO_CHANNEL) {
            fail(request, "The camera is not broadcasting (call setChannel first)");
            return;
        }

        if (!BroadcastChannels.canTransmit(camera)) {
            fail(request, "No modem attached to the camera");
            return;
        }

        if (inFlight.size() >= MAX_IN_FLIGHT) {
            fail(request, "Too many captures in progress");
            return;
        }

        var lease = chooseRenderer(server, channel);
        if (lease == null) {
            fail(request, "No player is online to render the view");
            return;
        }

        request.lease = lease.getUUID();
        inFlight.put(request.id, request);
        ServerNetworking.sendToPlayer(new CameraCaptureMessage(request.id, channel, request.width, request.height), lease);
    }

    /**
     * Pick the client to render a capture: ideally one already watching the channel (its picture is already
     * streamed), otherwise one not watching anything (leasing it disturbs nobody), otherwise anyone.
     *
     * @param server  The current server.
     * @param channel The channel to be rendered.
     * @return The chosen player, or {@code null} if nobody is online.
     */
    private static @Nullable ServerPlayer chooseRenderer(MinecraftServer server, int channel) {
        var channels = BroadcastChannels.get(server);
        ServerPlayer idle = null, any = null;
        for (var player : server.getPlayerList().getPlayers()) {
            if (player.hasDisconnected()) continue;
            var intent = channels.getIntent(player);
            if (intent != null && intent == channel) return player;
            if (intent == null && idle == null) idle = player;
            if (any == null) any = player;
        }
        return idle != null ? idle : any;
    }

    /**
     * Handle one uploaded chunk of a frame.
     *
     * @param sender  The player who sent the chunk.
     * @param message The received chunk.
     */
    public static void handleUpload(ServerPlayer sender, CameraFrameMessage message) {
        var request = inFlight.get(message.requestId());
        if (request == null || !sender.getUUID().equals(request.lease)) return;

        if (message.totalLength() == -1) {
            inFlight.remove(request.id);
            fail(request, "The rendering client could not draw the view");
            return;
        }

        var expected = request.width * request.height;
        var data = message.data();
        if (message.totalLength() != expected || message.offset() < 0 || data.length > CameraFrameMessage.MAX_CHUNK
            || message.offset() + data.length > expected) {
            inFlight.remove(request.id);
            fail(request, "Malformed frame upload");
            return;
        }

        if (request.buffer == null) request.buffer = new byte[expected];
        System.arraycopy(data, 0, request.buffer, message.offset(), data.length);
        request.received += data.length;

        if (request.received >= expected) {
            inFlight.remove(request.id);
            complete(request, new Result(request.buffer, null, System.nanoTime() + RESULT_TIMEOUT_NS));
        }
    }

    private static void fail(Request request, String message) {
        complete(request, new Result(null, message, System.nanoTime() + RESULT_TIMEOUT_NS));
    }

    private static void complete(Request request, Result result) {
        results.put(request.id, result);
        try {
            request.computer.queueEvent(EVENT, request.id);
        } catch (RuntimeException e) {
            // The computer has detached since it asked; nobody is left to collect the frame.
            results.remove(request.id);
            LOG.debug("Dropping camera frame {}: {}", request.id, e.toString());
        }
    }

    private static final class Request {
        final long id;
        final CameraSource camera;
        final IComputerAccess computer;
        final int width;
        final int height;
        final long deadline;

        @Nullable UUID lease;
        byte @Nullable [] buffer;
        int received;

        Request(long id, CameraSource camera, IComputerAccess computer, int width, int height, long deadline) {
            this.id = id;
            this.camera = camera;
            this.computer = computer;
            this.width = width;
            this.height = height;
            this.deadline = deadline;
        }
    }

    private record Result(byte @Nullable [] frame, @Nullable String error, long deadline) {
    }
}
