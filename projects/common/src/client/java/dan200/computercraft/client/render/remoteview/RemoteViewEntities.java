// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.client.render.remoteview;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The entities visible in a cross-dimension camera view, mirrored from server snapshots.
 * <p>
 * Each remote entity is backed by a lazily-created local entity instance (never added to any level), posed from
 * the latest snapshot purely for rendering with the real entity renderers - player skins and all.
 */
final class RemoteViewEntities {
    private final Map<Integer, RemoteEntity> entities = new HashMap<>();

    /**
     * Apply a snapshot payload.
     *
     * @param buf The payload to read.
     */
    void read(FriendlyByteBuf buf) {
        for (var entity : entities.values()) entity.seen = false;

        while (buf.isReadable()) {
            var id = buf.readVarInt();
            var type = BuiltInRegistries.ENTITY_TYPE.byId(buf.readVarInt());
            var entity = entities.computeIfAbsent(id, i -> new RemoteEntity(type));
            if (entity.type != type) {
                // The id was reused for a different entity; start over.
                entity = new RemoteEntity(type);
                entities.put(id, entity);
            }

            entity.x = buf.readFloat();
            entity.y = buf.readFloat();
            entity.z = buf.readFloat();
            entity.yRot = buf.readFloat();
            entity.xRot = buf.readFloat();
            entity.yHeadRot = buf.readFloat();
            entity.yBodyRot = buf.readFloat();
            if (buf.readBoolean()) {
                entity.playerId = buf.readUUID();
                entity.playerName = buf.readUtf();
            }
            entity.seen = true;
        }

        for (Iterator<RemoteEntity> iterator = entities.values().iterator(); iterator.hasNext(); ) {
            if (!iterator.next().seen) iterator.remove();
        }
    }

    /**
     * Get the current remote entities.
     *
     * @return The entities to draw.
     */
    List<RemoteEntity> list() {
        return entities.isEmpty() ? List.of() : new ArrayList<>(entities.values());
    }

    boolean isEmpty() {
        return entities.isEmpty();
    }

    /**
     * One entity in a remote view: the latest snapshot state, plus the local instance used to render it.
     */
    static final class RemoteEntity {
        final EntityType<?> type;
        float x;
        float y;
        float z;
        float yRot;
        float xRot;
        float yHeadRot;
        float yBodyRot;
        @Nullable UUID playerId;
        @Nullable String playerName;
        boolean seen = true;

        private @Nullable Entity rendered;

        RemoteEntity(EntityType<?> type) {
            this.type = type;
        }

        /**
         * Get (creating if needed) the local entity instance used to render this snapshot, posed at its latest
         * state in camera-relative coordinates.
         *
         * @param level  The viewer's level, used only to satisfy entity constructors.
         * @param originX The camera block's x position.
         * @param originY The camera block's y position.
         * @param originZ The camera block's z position.
         * @return The posed entity, or {@code null} if this entity type cannot be instantiated client-side.
         */
        @Nullable
        Entity rendered(ClientLevel level, int originX, int originY, int originZ) {
            var entity = rendered;
            if (entity == null || entity.level() != level) {
                entity = rendered = playerId != null && playerName != null
                    ? new RemotePlayer(level, new GameProfile(playerId, playerName))
                    : type.create(level);
                if (entity == null) return null;
            }

            entity.setPos(originX + x, originY + y, originZ + z);
            entity.setYRot(yRot);
            entity.setXRot(xRot);
            if (entity instanceof LivingEntity living) {
                living.yHeadRot = yHeadRot;
                living.yHeadRotO = yHeadRot;
                living.yBodyRot = yBodyRot;
                living.yBodyRotO = yBodyRot;
            }
            entity.setOldPosAndRot();
            return entity;
        }
    }
}
