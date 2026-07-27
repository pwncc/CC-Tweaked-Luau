// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.camera;

import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

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
        } catch (ClassNotFoundException e) {
            broken = true;
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            LOG.warn("[camera] Could not manage Sable force-load tickets; fast structures may freeze when unwatched players unload them", e);
            return false;
        }
    }

    private static final Set<Object> observedContainers = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Watch a level for newly added physics structures, and briefly force their plot chunks to tick.
     * <p>
     * A structure that arrives without players nearby - most importantly one recreated by a dimension warp - has
     * its plot chunks written but never ticking, so a camera aboard never gets a tick to adopt its channel, re-arm
     * its chunk loader or re-ticket the new structure: the broadcast dies in a bootstrap deadlock. A short ticking
     * ticket over the plot breaks it; the camera's own machinery takes over from the first tick.
     *
     * @param level The level to watch.
     */
    static void watchForNewStructures(ServerLevel level) {
        if (broken) return;
        try {
            if (ticketType == null) resolve();
            var getContainer = SableServerBridge.getContainer;
            if (getContainer == null) return;
            var container = getContainer.invoke(null, level);
            if (container == null) return;
            synchronized (observedContainers) {
                if (!observedContainers.add(container)) return;
            }

            var observerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelObserver");
            var proxy = Proxy.newProxyInstance(SableServerBridge.class.getClassLoader(), new Class<?>[]{ observerClass }, (p, method, args) -> {
                switch (method.getName()) {
                    case "onSubLevelAdded" -> {
                        if (args != null && args.length == 1) wakeStructure(level, args[0]);
                        return null;
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(p);
                    }
                    case "equals" -> {
                        return args != null && args.length == 1 && p == args[0];
                    }
                    case "toString" -> {
                        return "ComputerCraftCameraWake";
                    }
                    default -> {
                        return null;
                    }
                }
            });
            container.getClass().getMethod("addObserver", observerClass).invoke(container, proxy);
            LOG.info("[camera] Watching {} for newly arrived physics structures", level.dimension().location());
        } catch (ClassNotFoundException e) {
            broken = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            LOG.warn("[camera] Could not watch for new Sable structures; cameras may not survive dimension warps", e);
        }
    }

    /**
     * Give a freshly added structure's plot a short ticking ticket, so block entities aboard get to run.
     *
     * @param level    The structure's level.
     * @param subLevel The added structure (a Sable {@code SubLevel}).
     */
    private static void wakeStructure(ServerLevel level, Object subLevel) {
        try {
            var plot = subLevel.getClass().getMethod("getPlot").invoke(subLevel);
            var bounds = (BoundingBox3ic) plot.getClass().getMethod("getBoundingBox").invoke(plot);
            var centre = new ChunkPos(((bounds.minX() + bounds.maxX()) / 2) >> 4, ((bounds.minZ() + bounds.maxZ()) / 2) >> 4);
            var radius = Math.min(6, Math.max(2, ((bounds.maxX() - bounds.minX()) >> 4) / 2 + 1));
            level.getChunkSource().addRegionTicket(BroadcastChannels.CAMERA_TICKET, centre, radius, Unit.INSTANCE);
            LOG.info("[camera] Waking structure plot at {} in {} so cameras aboard can resume", centre, level.dimension().location());
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("[camera] Could not wake a newly arrived structure's plot", e);
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
