// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.peripheral.controller;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The controller dock lets a player pipe their keyboard and gamepad straight into a computer: right-click the dock
 * to take the controls, and every key, button and stick movement arrives as events - ideal for remotely piloting
 * vehicles watched through a camera view.
 * <p>
 * Events: {@code controller_grab(side, player)}, {@code controller_release(side)},
 * {@code controller_key(side, keyCode, pressed)}, {@code controller_button(side, button, pressed)} and
 * {@code controller_axis(side, axis, value)}. Buttons use the standard gamepad names ({@code a}, {@code b},
 * {@code x}, {@code y}, {@code left_bumper}, {@code start}, {@code dpad_up}, ...) and axes are {@code left_x},
 * {@code left_y}, {@code right_x}, {@code right_y}, {@code left_trigger} and {@code right_trigger}.
 *
 * @cc.module controller
 */
public final class ControllerPeripheral implements IPeripheral {
    private final ControllerBlockEntity controller;
    private final Set<IComputerAccess> computers = new HashSet<>();

    ControllerPeripheral(ControllerBlockEntity controller) {
        this.controller = controller;
    }

    @Override
    public String getType() {
        return "controller";
    }

    /**
     * Whether a player currently holds the controls.
     *
     * @return Whether the dock is being controlled.
     */
    @LuaFunction(mainThread = true)
    public boolean isControlled() {
        return controller.controllerName() != null;
    }

    /**
     * Get the name of the player holding the controls.
     *
     * @return The controlling player's name, or {@code nil} when nobody has the controls.
     */
    @LuaFunction(mainThread = true)
    public Object @Nullable [] getController() {
        var name = controller.controllerName();
        return name == null ? null : new Object[]{ name };
    }

    /**
     * Get the current value of a gamepad axis.
     *
     * @param axis The axis name, e.g. {@code left_x} or {@code right_trigger}.
     * @return The axis value: -1 to 1 for sticks, -1 (released) to 1 (fully pulled) for triggers.
     */
    @LuaFunction(mainThread = true)
    public double getAxis(String axis) {
        return controller.axes.getOrDefault(axis, 0.0);
    }

    /**
     * Whether a gamepad button is currently held.
     *
     * @param button The button name, e.g. {@code a} or {@code dpad_up}.
     * @return Whether the button is held.
     */
    @LuaFunction(mainThread = true)
    public boolean isDown(String button) {
        return controller.buttons.contains(button);
    }

    /**
     * Whether a keyboard key is currently held.
     *
     * @param key The key code, as used by the {@code keys} API.
     * @return Whether the key is held.
     */
    @LuaFunction(mainThread = true)
    public boolean isKeyDown(int key) {
        return controller.keys.contains(key);
    }

    /**
     * Get the entire input state in one call: every axis value, held button and held key.
     *
     * @return A table with {@code axes}, {@code buttons} and {@code keys} sub-tables.
     */
    @LuaFunction(mainThread = true)
    public Map<String, Object> getState() {
        Map<String, Object> state = new HashMap<>(3);
        state.put("axes", new HashMap<>(controller.axes));

        Map<String, Boolean> buttons = new HashMap<>();
        for (var button : controller.buttons) buttons.put(button, true);
        state.put("buttons", buttons);

        Map<Integer, Boolean> keys = new HashMap<>();
        for (var key : controller.keys) keys.put(key, true);
        state.put("keys", keys);
        return state;
    }

    void queueEvent(String event, Object... args) {
        for (var computer : computers) {
            var eventArgs = new Object[args.length + 1];
            eventArgs[0] = computer.getAttachmentName();
            System.arraycopy(args, 0, eventArgs, 1, args.length);
            computer.queueEvent(event, eventArgs);
        }
    }

    @Override
    public void attach(IComputerAccess computer) {
        computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        computers.remove(computer);
    }

    @Override
    public Object getTarget() {
        return controller;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return this == other || (other instanceof ControllerPeripheral o && controller == o.controller);
    }
}
