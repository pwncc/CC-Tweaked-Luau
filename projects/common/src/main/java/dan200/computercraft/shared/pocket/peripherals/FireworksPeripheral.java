// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.pocket.peripherals;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.LuaValues;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.pocket.IPocketAccess;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The firework launcher lets a pocket computer launch fireworks from the position of the player (or other entity)
 * holding it.
 * <p>
 * Fireworks are launched with {@link #launch}, which accepts an optional table describing the firework. To avoid
 * covering the world in gunpowder smoke, only one firework may be launched every half second.
 *
 * @cc.module fireworks
 */
public final class FireworksPeripheral implements IPeripheral {
    /**
     * The minimum number of ticks between launching two fireworks.
     */
    private static final int COOLDOWN = 10;

    /**
     * The maximum number of colours in the {@code colours} and {@code fade} lists.
     */
    private static final int MAX_COLOURS = 16;

    /**
     * The colours used when no {@code colours} option is given.
     */
    private static final IntList DEFAULT_COLOURS = IntList.of(0xFFAA00);

    private final IPocketAccess access;
    private long lastLaunch = -COOLDOWN;

    public FireworksPeripheral(IPocketAccess access) {
        this.access = access;
    }

    @Override
    public String getType() {
        return "fireworks";
    }

    /**
     * Launch a firework from the position of whoever is holding this pocket computer.
     * <p>
     * The firework may be customised with a table of options:
     * <ul>
     * <li>{@code power}: The flight duration of the rocket, between 1 and 3. Defaults to 1.</li>
     * <li>{@code colours} (or {@code colors}): A list of RGB colours (such as {@code 0xFF0000}) used by the
     * explosion. Defaults to orange ({@code 0xFFAA00}).</li>
     * <li>{@code fade}: A list of RGB colours the explosion fades to. Defaults to none.</li>
     * <li>{@code shape}: The shape of the explosion. One of {@code "small_ball"}, {@code "large_ball"},
     * {@code "star"}, {@code "creeper"} or {@code "burst"}. Defaults to {@code "small_ball"}.</li>
     * <li>{@code trail}: Whether the explosion leaves a trail. Defaults to {@code false}.</li>
     * <li>{@code twinkle}: Whether the explosion twinkles. Defaults to {@code false}.</li>
     * </ul>
     *
     * @param options Options describing the launched firework.
     * @throws LuaException If the pocket computer is not held by an entity, the launcher is still recharging, or the
     *                      options are malformed.
     * @cc.usage Launch a red creeper-face firework.
     *
     * <pre>{@code
     * local fireworks = peripheral.find("fireworks")
     * fireworks.launch({ power = 2, colours = { 0xFF0000 }, shape = "creeper" })
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public void launch(Optional<Map<?, ?>> options) throws LuaException {
        var entity = access.getEntity();
        if (entity == null) throw new LuaException("The pocket computer is not being held");

        var level = access.getLevel();
        var now = level.getGameTime();
        if (now - lastLaunch < COOLDOWN) throw new LuaException("Fireworks are still recharging");

        var table = options.orElse(Map.of());

        var power = optInt(table, "power", 1);
        if (power < 1 || power > 3) throw new LuaException("Power out of range (expected 1-3)");

        var colours = optColourList(table, "colours");
        if (colours == null) colours = optColourList(table, "colors");
        if (colours == null) colours = DEFAULT_COLOURS;

        var fade = optColourList(table, "fade");
        if (fade == null) fade = IntList.of();

        var shape = parseShape(optString(table, "shape", "small_ball"));
        var trail = optBoolean(table, "trail", false);
        var twinkle = optBoolean(table, "twinkle", false);

        var stack = new ItemStack(Items.FIREWORK_ROCKET);
        stack.set(DataComponents.FIREWORKS, new Fireworks(power, List.of(
            new FireworkExplosion(shape, colours, fade, trail, twinkle)
        )));

        level.addFreshEntity(new FireworkRocketEntity(level, entity.getX(), entity.getY() + 0.5, entity.getZ(), stack));
        lastLaunch = now;
    }

    /**
     * Check whether a firework can be launched right now.
     *
     * @return Whether the pocket computer is held by an entity and the launcher has finished recharging.
     */
    @LuaFunction(mainThread = true)
    public boolean canLaunch() {
        return access.getEntity() != null && access.getLevel().getGameTime() - lastLaunch >= COOLDOWN;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return this == other || (other instanceof FireworksPeripheral fireworks && access == fireworks.access);
    }

    private static FireworkExplosion.Shape parseShape(String shape) throws LuaException {
        return switch (shape) {
            case "small_ball" -> FireworkExplosion.Shape.SMALL_BALL;
            case "large_ball" -> FireworkExplosion.Shape.LARGE_BALL;
            case "star" -> FireworkExplosion.Shape.STAR;
            case "creeper" -> FireworkExplosion.Shape.CREEPER;
            case "burst" -> FireworkExplosion.Shape.BURST;
            default -> throw new LuaException("Unknown shape '" + shape + "'");
        };
    }

    private static int optInt(Map<?, ?> table, String key, int fallback) throws LuaException {
        var value = table.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw LuaValues.badField(key, "number", LuaValues.getType(value));

        var asDouble = number.doubleValue();
        if (!Double.isFinite(asDouble)) throw LuaValues.badField(key, "number", LuaValues.getNumericType(asDouble));
        return (int) asDouble;
    }

    private static boolean optBoolean(Map<?, ?> table, String key, boolean fallback) throws LuaException {
        var value = table.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean bool)) throw LuaValues.badField(key, "boolean", LuaValues.getType(value));
        return bool;
    }

    private static String optString(Map<?, ?> table, String key, String fallback) throws LuaException {
        var value = table.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String string)) throw LuaValues.badField(key, "string", LuaValues.getType(value));
        return string;
    }

    private static @Nullable IntList optColourList(Map<?, ?> table, String key) throws LuaException {
        var value = table.get(key);
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> list)) throw LuaValues.badField(key, "table", LuaValues.getType(value));

        var colours = new IntArrayList();
        for (var i = 1; ; i++) {
            var item = getIndex(list, i);
            if (item == null) break;
            if (colours.size() >= MAX_COLOURS) {
                throw new LuaException("Too many colours in '" + key + "' (at most " + MAX_COLOURS + ")");
            }
            if (!(item instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                throw new LuaException("Bad colour #" + i + " in '" + key + "' (number expected)");
            }

            var colour = (int) number.doubleValue();
            if (colour < 0 || colour > 0xFFFFFF) {
                throw new LuaException("Bad colour #" + i + " in '" + key + "' (RGB colour expected)");
            }
            colours.add(colour);
        }
        return colours;
    }

    private static @Nullable Object getIndex(Map<?, ?> table, int index) {
        var value = table.get((double) index);
        return value != null ? value : table.get(index);
    }
}
