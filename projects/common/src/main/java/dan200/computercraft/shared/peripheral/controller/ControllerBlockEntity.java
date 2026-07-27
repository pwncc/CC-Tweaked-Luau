// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.peripheral.controller;

import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The controller dock: while a player has {@linkplain #grab grabbed} it, their client streams keyboard and gamepad
 * input here, which is queued as events on every attached computer (and kept for polling).
 */
public final class ControllerBlockEntity extends BlockEntity {
    /** How far the controlling player may stand from the dock. */
    public static final double GRAB_RANGE = 8;

    /** Release the controls when the client has gone quiet for this long (its screen sends a steady heartbeat). */
    private static final int TIMEOUT_TICKS = 60;

    /** The names of the standard gamepad buttons, in GLFW order. */
    public static final String[] BUTTON_NAMES = {
        "a", "b", "x", "y", "left_bumper", "right_bumper", "back", "start", "guide",
        "left_thumb", "right_thumb", "dpad_up", "dpad_right", "dpad_down", "dpad_left",
    };

    /** The names of the standard gamepad axes, in GLFW order. */
    public static final String[] AXIS_NAMES = { "left_x", "left_y", "right_x", "right_y", "left_trigger", "right_trigger" };

    public static final int INPUT_KEY = 0;
    public static final int INPUT_BUTTON = 1;
    public static final int INPUT_AXIS = 2;
    public static final int INPUT_RELEASE = 3;
    public static final int INPUT_HEARTBEAT = 4;

    private final ControllerPeripheral peripheral = new ControllerPeripheral(this);

    private @Nullable UUID controller;
    private @Nullable String controllerName;
    private long lastInput;

    final Map<String, Double> axes = new HashMap<>();
    final Set<String> buttons = new HashSet<>();
    final Set<Integer> keys = new HashSet<>();

    public ControllerBlockEntity(BlockEntityType<ControllerBlockEntity> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public IPeripheral peripheral() {
        return peripheral;
    }

    @Nullable
    String controllerName() {
        return controllerName;
    }

    void serverTick() {
        if (controller == null || getLevel() == null) return;

        var player = getLevel().getServer() == null ? null : getLevel().getServer().getPlayerList().getPlayer(controller);
        if (player == null
            || player.level() != getLevel()
            || player.blockPosition().distSqr(getBlockPos()) > GRAB_RANGE * GRAB_RANGE
            || getLevel().getGameTime() - lastInput > TIMEOUT_TICKS
        ) {
            release();
        }
    }

    /**
     * A player takes the controls. Fails when somebody else already has them.
     *
     * @param player The grabbing player.
     * @return Whether they now hold the controls.
     */
    public boolean grab(Player player) {
        if (controller != null && !controller.equals(player.getUUID())) return false;

        var fresh = controller == null;
        controller = player.getUUID();
        controllerName = player.getGameProfile().getName();
        lastInput = player.level().getGameTime();
        if (fresh) peripheral.queueEvent("controller_grab", controllerName);
        return true;
    }

    private void release() {
        if (controller == null) return;
        controller = null;
        controllerName = null;
        axes.clear();
        buttons.clear();
        keys.clear();
        peripheral.queueEvent("controller_release");
    }

    /**
     * Handle one input from the controlling player's client.
     *
     * @param sender The player who sent the input.
     * @param kind   The input kind; one of the {@code INPUT_*} constants.
     * @param code   The key code, button index or axis index.
     * @param value  The axis value, or 1/0 for pressed/released.
     */
    public void handleInput(Player sender, int kind, int code, float value) {
        if (getLevel() == null || !sender.getUUID().equals(controller)) return;
        lastInput = getLevel().getGameTime();

        switch (kind) {
            case INPUT_HEARTBEAT -> {
            }
            case INPUT_RELEASE -> release();
            case INPUT_KEY -> {
                var pressed = value != 0;
                if (pressed ? keys.add(code) : keys.remove(code)) {
                    peripheral.queueEvent("controller_key", code, pressed);
                }
            }
            case INPUT_BUTTON -> {
                if (code < 0 || code >= BUTTON_NAMES.length) return;
                var name = BUTTON_NAMES[code];
                var pressed = value != 0;
                if (pressed ? buttons.add(name) : buttons.remove(name)) {
                    peripheral.queueEvent("controller_button", name, pressed);
                }
            }
            case INPUT_AXIS -> {
                if (code < 0 || code >= AXIS_NAMES.length) return;
                var name = AXIS_NAMES[code];
                axes.put(name, (double) value);
                peripheral.queueEvent("controller_axis", name, (double) value);
            }
            default -> {
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        release();
    }
}
