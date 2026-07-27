// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.camera;

import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Optional server-side glue for Sable's physics structures: force-load tickets.
 * <p>
 * Sable unloads a sub-level (serialising it into its holding chunk, freezing its physics) as soon as that world
 * chunk unloads - which happens the moment a fast structure outruns every nearby player. A structure being
 * watched through a camera must keep simulating regardless, so while its camera has receiving viewers we hold a
 * Sable force-load ticket on it, keyed by the broadcast channel.
 * <p>
 * Everything here is reflective and fails soft, like {@code SableClientBridge}: without Sable there is nothing to
 * unload and nothing to do. The ticket type is registered lazily on first use, which has a helpful side effect:
 * tickets persisted from a previous run fail to deserialise (unknown type) and are dropped, so a crash can never
 * leave a structure force-loaded forever - live tickets are simply re-added while somebody watches.
 */
final class SableServerBridge {
    private static final Logger LOG = LoggerFactory.getLogger(SableServerBridge.class);

    private static boolean broken = false;
    private static @Nullable Object ticketType;
    private static @Nullable Method getContainer;
    private static @Nullable Method getSubLevel;
    private static @Nullable Method addTicket;
    private static @Nullable Method removeTicket;

    private SableServerBridge() {
    }

    /**
     * The unique id of the structure containing a (structure-local) position, if any.
     *
     * @param level The level to look in.
     * @param local The structure-local position.
     * @return The structure's id, or {@code null}.
     */
    static @Nullable UUID structureAt(ServerLevel level, Vec3 local) {
        var subLevel = SableCompanion.INSTANCE.getContaining(level, local);
        return subLevel == null ? null : subLevel.getUniqueId();
    }

    /**
     * Add or remove the watched-camera force-load ticket on a structure.
     *
     * @param level     The structure's level.
     * @param structure The structure's id.
     * @param channel   The broadcast channel holding the ticket (the ticket's key).
     * @param add       Whether to add ({@code true}) or remove ({@code false}) the ticket.
     * @return Whether the ticket state changed.
     */
    static boolean setTicket(ServerLevel level, UUID structure, int channel, boolean add) {
        if (broken) return false;
        try {
            if (ticketType == null) resolve();
            var getContainer = SableServerBridge.getContainer;
            var getSubLevel = SableServerBridge.getSubLevel;
            var ticket = add ? addTicket : removeTicket;
            var type = ticketType;
            if (getContainer == null || getSubLevel == null || ticket == null || type == null) return false;

            var container = getContainer.invoke(null, level);
            if (container == null) return false;
            var subLevel = getSubLevel.invoke(container, structure);
            if (subLevel == null) return false;

            var changed = (Boolean) ticket.invoke(container, subLevel, type, channel);
            if (changed && add) LOG.info("[camera] Force-loading structure {} while channel {} is watched", structure, channel);
            if (changed && !add) LOG.info("[camera] Released the force-load on structure {} (channel {})", structure, channel);
            return changed;
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            LOG.warn("[camera] Could not manage Sable force-load tickets; fast structures may freeze when unwatched players unload them", e);
            return false;
        }
    }

    @SuppressWarnings("NullAway")
    private static void resolve() throws ReflectiveOperationException {
        var containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
        var serverContainerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer");
        var subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
        var typeClass = Class.forName("dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType");

        getContainer = containerClass.getMethod("getContainer", ServerLevel.class);
        getSubLevel = containerClass.getMethod("getSubLevel", UUID.class);
        addTicket = serverContainerClass.getMethod("addForceLoadTicket", subLevelClass, typeClass, Object.class);
        removeTicket = serverContainerClass.getMethod("removeForceLoadTicket", subLevelClass, typeClass, Object.class);
        ticketType = typeClass
            .getMethod("create", ResourceLocation.class, Codec.class)
            .invoke(null, ResourceLocation.fromNamespaceAndPath("computercraft", "watched_camera"), Codec.INT);
    }
}
