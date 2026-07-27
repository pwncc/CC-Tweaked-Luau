// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.client.render.remoteview;

import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional client-side glue for Sable's physics structures in cross-dimension views.
 * <p>
 * Sable attaches a sub-level container to every {@link ClientLevel} (via its own mixin) and renders whatever that
 * container holds when the level is drawn. The server syncs a watched structure to the viewer even across a
 * dimension boundary, but those packets always land in the container of the level the viewer is standing in.
 * Sharing that container with the view's puppet level makes the structure render inside the puppet pass, at its
 * true pose, with the puppet's camera - the rocket appears in its own camera view.
 * <p>
 * Everything here is reflective and fails soft: without Sable installed (or if its internals change) views simply
 * render without physics structures.
 */
final class SableClientBridge {
    private static final Logger LOG = LoggerFactory.getLogger(SableClientBridge.class);

    private static final String HOLDER_GETTER = "sable$getPlotContainer";

    private static boolean broken = false;
    private static @Nullable Method getter;
    private static @Nullable Field containerField;

    private SableClientBridge() {
    }

    /**
     * Give a puppet level the same Sable sub-level container as the viewer's own level, so structures synced to
     * the viewer render inside the puppet's dimension view.
     *
     * @param puppet The puppet level to share into.
     * @param main   The viewer's own level.
     */
    static void shareSubLevels(ClientLevel puppet, ClientLevel main) {
        if (broken) return;
        try {
            if (getter == null) {
                getter = ClientLevel.class.getMethod(HOLDER_GETTER);
            }
            // Force the main level's container to exist, then find the (mixin-added) backing field by identity.
            var container = getter.invoke(main);
            if (container == null) return;

            if (containerField == null) {
                for (var field : ClientLevel.class.getDeclaredFields()) {
                    if (!field.getType().isInstance(container)) continue;
                    field.setAccessible(true);
                    if (field.get(main) == container) {
                        containerField = field;
                        break;
                    }
                }
                if (containerField == null) throw new NoSuchFieldException("No field on ClientLevel holds the sub-level container");
            }

            containerField.set(puppet, container);
            LOG.info("[camera] Puppet level shares the viewer's Sable sub-level container");
        } catch (NoSuchMethodException e) {
            // Sable is not installed: nothing to do, and nothing to warn about.
            broken = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            LOG.warn("[camera] Could not share Sable sub-levels with a puppet level; structures will not appear in cross-dimension views", e);
        }
    }
}
