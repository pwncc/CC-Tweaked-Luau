// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.mixin;

import dan200.computercraft.shared.camera.BroadcastChannels;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3dc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Compatibility with Sable's physics structures: keep a structure synced to players who are <em>watching it
 * through a camera</em>, not just those standing near it.
 * <p>
 * Sable's {@code SubLevelTrackingSystem} runs per level and only ever considers that level's own players: it
 * collects trackers from {@code level.players()}, resolves them with {@code level.getPlayerByUUID} and gates both
 * acquiring and dropping on one distance check, {@code shouldLoad(player, position)}. That breaks remote piloting
 * twice over - fly a camera-carrying rocket beyond tracking range (or into another dimension) and the watcher's
 * client deletes the whole structure, so it vanishes from the world and from its own camera view.
 * <p>
 * Three small hooks make "receiving a live broadcast from a camera riding that structure" count as presence:
 * <ul>
 * <li>{@code shouldLoad} treats such a watcher as in range, wherever they stand.</li>
 * <li>{@code level.players()} is augmented with cross-dimension watchers of cameras in this level, so the
 * system's own loops adopt them and send the full sync.</li>
 * <li>{@code level.getPlayerByUUID} falls back to the server-wide player list for those watchers, so they are
 * neither dropped as "left the level" nor skipped for movement updates.</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem", remap = false)
class CompatSableTrackingMixin {
    @Shadow(remap = false)
    @Final
    private ServerLevel level;

    @Inject(method = "shouldLoad", at = @At("HEAD"), cancellable = true, require = 0, expect = 1, remap = false)
    @SuppressWarnings("UnusedMethod")
    private void computercraft$trackForRemoteViewers(Player player, Vector3dc position, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof ServerPlayer viewer
            && BroadcastChannels.isWatchingNear(viewer, level, position.x(), position.y(), position.z())
        ) {
            cir.setReturnValue(true);
        }
    }

    @Redirect(
        method = { "tick", "collectPlayers" },
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;"),
        require = 0, expect = 1, remap = false
    )
    @SuppressWarnings("UnusedMethod")
    private List<ServerPlayer> computercraft$includeRemoteViewers(ServerLevel instance) {
        var players = instance.players();
        var watchers = BroadcastChannels.crossDimensionWatchers(instance);
        if (watchers.isEmpty()) return players;

        var combined = new ArrayList<ServerPlayer>(players.size() + watchers.size());
        combined.addAll(players);
        combined.addAll(watchers);
        return combined;
    }

    @Redirect(
        method = { "tick", "sendMovementUpdates" },
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"),
        require = 0, expect = 1, remap = false
    )
    @SuppressWarnings("UnusedMethod")
    private @Nullable Player computercraft$resolveRemoteViewers(ServerLevel instance, UUID uuid) {
        var player = instance.getPlayerByUUID(uuid);
        if (player != null) return player;
        return BroadcastChannels.crossDimensionWatcher(instance, uuid);
    }
}
