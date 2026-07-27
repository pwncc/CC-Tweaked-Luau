// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.client.gui;

import dan200.computercraft.client.network.ClientNetworking;
import dan200.computercraft.shared.network.server.ControllerInputMessage;
import dan200.computercraft.shared.peripheral.controller.ControllerBlockEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.system.MemoryStack;

import java.util.HashSet;
import java.util.Set;

/**
 * Shown while the player holds a controller dock's controls: swallows all keyboard input (streaming it to the
 * dock), polls the first connected gamepad every tick, and releases the controls when closed.
 */
public class ControlScreen extends Screen {
    /** Stick movement below this is treated as resting, so worn controllers do not spam events. */
    private static final float DEADZONE = 0.08f;
    /** Axis changes smaller than this are not re-sent. */
    private static final float AXIS_EPSILON = 0.02f;
    private static final int HEARTBEAT_INTERVAL = 10;

    private final BlockPos pos;
    private final float[] lastAxes = new float[ControllerBlockEntity.AXIS_NAMES.length];
    private final boolean[] lastButtons = new boolean[ControllerBlockEntity.BUTTON_NAMES.length];
    private final Set<Integer> heldKeys = new HashSet<>();
    private int heartbeat = 0;
    private boolean gamepadSeen = false;

    public ControlScreen(BlockPos pos) {
        super(Component.translatable("gui.computercraft.control"));
        this.pos = pos;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (heldKeys.add(key)) send(ControllerBlockEntity.INPUT_KEY, key, 1);
        return true;
    }

    @Override
    public boolean keyReleased(int key, int scanCode, int modifiers) {
        if (heldKeys.remove(key)) send(ControllerBlockEntity.INPUT_KEY, key, 0);
        return true;
    }

    @Override
    public void tick() {
        if (++heartbeat >= HEARTBEAT_INTERVAL) {
            heartbeat = 0;
            send(ControllerBlockEntity.INPUT_HEARTBEAT, 0, 0);
        }
        pollGamepad();
    }

    private void pollGamepad() {
        for (var jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
            if (!GLFW.glfwJoystickIsGamepad(jid)) continue;
            gamepadSeen = true;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                var state = GLFWGamepadState.malloc(stack);
                if (!GLFW.glfwGetGamepadState(jid, state)) break;

                var axes = state.axes();
                for (var axis = 0; axis < lastAxes.length && axis < axes.limit(); axis++) {
                    var value = axes.get(axis);
                    // Sticks rest at 0; apply the deadzone there. Triggers rest at -1 and never wobble.
                    if (axis < 4 && Math.abs(value) < DEADZONE) value = 0;
                    if (Math.abs(value - lastAxes[axis]) < AXIS_EPSILON) continue;
                    lastAxes[axis] = value;
                    send(ControllerBlockEntity.INPUT_AXIS, axis, value);
                }

                var buttons = state.buttons();
                for (var button = 0; button < lastButtons.length && button < buttons.limit(); button++) {
                    var pressed = buttons.get(button) == GLFW.GLFW_PRESS;
                    if (pressed == lastButtons[button]) continue;
                    lastButtons[button] = pressed;
                    send(ControllerBlockEntity.INPUT_BUTTON, button, pressed ? 1 : 0);
                }
            }
            break;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.render(graphics, mouseX, mouseY, partialTicks);
        var lines = new String[]{
            title.getString(),
            gamepadSeen ? "● gamepad connected" : "○ no gamepad detected (keyboard only)",
            "Press ESC to release the controls",
        };
        var y = height / 2 - lines.length * 6;
        for (var line : lines) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xFFFFFF);
            y += 12;
        }
    }

    @Override
    public void onClose() {
        send(ControllerBlockEntity.INPUT_RELEASE, 0, 0);
        super.onClose();
    }

    private void send(int kind, int code, float value) {
        ClientNetworking.sendToServer(new ControllerInputMessage(pos, (byte) kind, code, value));
    }
}
