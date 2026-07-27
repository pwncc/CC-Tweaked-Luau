// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.diviningrod;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.core.util.Nullability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The divining rod senses the <em>density</em> of nearby ores, without revealing their exact positions. It is a
 * prospecting aid, not an x-ray machine.
 * <p>
 * Ores within 8 blocks of the rod contribute to the "strength" of their ore group, with closer ores contributing
 * more. {@link #survey()} reports the strength of every group at once, while {@link #pull(String)} focuses on a
 * single group, adding a coarse compass bearing towards it.
 * <p>
 * The rod needs to attune between readings: {@link #survey()} and {@link #pull(String)} share a five second cooldown.
 *
 * @cc.usage Take a survey, then follow the pull of the strongest group.
 *
 * <pre>{@code
 * local rod = peripheral.find("divining_rod")
 * for group, strength in pairs(rod.survey()) do
 *   print(("%s: %.2f"):format(group, strength))
 * end
 * sleep(5)
 * print(rod.pull("iron"))
 * }</pre>
 * @cc.module divining_rod
 */
public final class DiviningRodPeripheral implements IPeripheral {
    /**
     * How long (in ticks) the rod takes to attune between readings.
     */
    private static final int COOLDOWN_TICKS = 5 * 20;

    /**
     * The radius of the scanned cube.
     */
    private static final int RADIUS = 8;

    private static final List<OreGroup> GROUPS = List.of(
        new OreGroup("coal", BlockTags.COAL_ORES),
        new OreGroup("iron", BlockTags.IRON_ORES),
        new OreGroup("copper", BlockTags.COPPER_ORES),
        new OreGroup("gold", BlockTags.GOLD_ORES),
        new OreGroup("redstone", BlockTags.REDSTONE_ORES),
        new OreGroup("lapis", BlockTags.LAPIS_ORES),
        new OreGroup("diamond", BlockTags.DIAMOND_ORES),
        new OreGroup("emerald", BlockTags.EMERALD_ORES)
    );

    /**
     * The group used for blocks with the conventional {@code c:ores} tag which don't fit any group above.
     */
    private static final String OTHER_GROUP = "other";

    private static final TagKey<Block> CONVENTIONAL_ORES = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores"));

    private static final String[] BEARINGS = {
        "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west",
    };

    private final DiviningRodBlockEntity rod;

    DiviningRodPeripheral(DiviningRodBlockEntity rod) {
        this.rod = rod;
    }

    @Override
    public String getType() {
        return "divining_rod";
    }

    /**
     * Survey the surrounding area, measuring the strength of every ore group at once.
     * <p>
     * Each ore within 8 blocks of the rod adds {@code 1 / (1 + distance)} to the strength of its group, so a reading
     * of 2.0 might be a couple of adjacent ores, or a big cluster further out.
     *
     * @return A table mapping each detected ore group ({@code "coal"}, {@code "iron"}, {@code "copper"},
     * {@code "gold"}, {@code "redstone"}, {@code "lapis"}, {@code "diamond"}, {@code "emerald"} or {@code "other"})
     * to its strength. Groups with no ores nearby are omitted.
     * @throws LuaException If the rod is still attuning from a previous reading.
     */
    @LuaFunction(mainThread = true)
    public Map<String, Double> survey() throws LuaException {
        var level = level();
        checkCooldown(level);

        var results = new HashMap<String, Double>();
        for (var entry : scan(level).entrySet()) {
            var strength = round2(entry.getValue().strength);
            if (strength > 0) results.put(entry.getKey(), strength);
        }
        return results;
    }

    /**
     * Feel for the pull of a single ore group, returning its strength and a coarse compass bearing towards it.
     * <p>
     * The bearing points at the weighted centre of the group, and is one of the eight compass directions
     * ({@code "north"}, {@code "north-east"}, ...). If the centre is nearly on top of the rod, {@code "above"},
     * {@code "below"} or {@code "here"} is returned instead.
     *
     * @param group The ore group to feel for. One of the group names returned by {@link #survey()}.
     * @return The strength of the group and the bearing towards it, or {@code nil} if no such ores are nearby.
     * @throws LuaException If the group is unknown, or the rod is still attuning from a previous reading.
     * @cc.treturn [1] number The strength of this ore group.
     * @cc.treturn [1] string The bearing towards the ores.
     * @cc.treturn [2] nil If no ores of this group are nearby.
     */
    @LuaFunction(mainThread = true)
    public Object @Nullable [] pull(String group) throws LuaException {
        if (!isKnownGroup(group)) throw new LuaException("Unknown ore group '" + group + "'");

        var level = level();
        checkCooldown(level);

        var result = scan(level).get(group);
        if (result == null) return null;

        var dx = result.x / result.strength;
        var dy = result.y / result.strength;
        var dz = result.z / result.strength;

        String bearing;
        if (Math.sqrt(dx * dx + dz * dz) < 1.5) {
            if (dy < -0.5) {
                bearing = "below";
            } else if (dy > 0.5) {
                bearing = "above";
            } else {
                bearing = "here";
            }
        } else {
            bearing = compassBearing(dx, dz);
        }

        return new Object[]{ round2(result.strength), bearing };
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return this == other || (other instanceof DiviningRodPeripheral o && rod == o.rod);
    }

    private Level level() {
        return Nullability.assertNonNull(rod.getLevel());
    }

    private void checkCooldown(Level level) throws LuaException {
        var now = level.getGameTime();
        var elapsed = now - rod.getLastSurvey();
        if (rod.getLastSurvey() != Long.MIN_VALUE && elapsed >= 0 && elapsed < COOLDOWN_TICKS) {
            var secondsLeft = (COOLDOWN_TICKS - elapsed + 19) / 20;
            throw new LuaException(String.format("The rod is still attuning (%d seconds left)", secondsLeft));
        }
        rod.setLastSurvey(now);
    }

    /**
     * Scan the cube around the rod, accumulating the strength and weighted centre of each ore group.
     *
     * @param level The level to scan.
     * @return The accumulated readings, keyed by group name.
     */
    private Map<String, Reading> scan(Level level) {
        var pos = rod.getBlockPos();
        var results = new HashMap<String, Reading>();
        for (var target : BlockPos.betweenClosed(pos.offset(-RADIUS, -RADIUS, -RADIUS), pos.offset(RADIUS, RADIUS, RADIUS))) {
            var state = level.getBlockState(target);
            if (state.isAir()) continue;

            var group = groupFor(state);
            if (group == null) continue;

            var weight = 1 / (1 + Math.sqrt(target.distSqr(pos)));
            var reading = results.computeIfAbsent(group, g -> new Reading());
            reading.strength += weight;
            reading.x += (target.getX() - pos.getX()) * weight;
            reading.y += (target.getY() - pos.getY()) * weight;
            reading.z += (target.getZ() - pos.getZ()) * weight;
        }
        return results;
    }

    private static @Nullable String groupFor(BlockState state) {
        for (var group : GROUPS) {
            if (state.is(group.tag())) return group.name();
        }
        return state.is(CONVENTIONAL_ORES) ? OTHER_GROUP : null;
    }

    private static boolean isKnownGroup(String name) {
        if (name.equals(OTHER_GROUP)) return true;
        for (var group : GROUPS) {
            if (group.name().equals(name)) return true;
        }
        return false;
    }

    private static String compassBearing(double dx, double dz) {
        var angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) angle += 360;
        return BEARINGS[(int) Math.round(angle / 45) % BEARINGS.length];
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private record OreGroup(String name, TagKey<Block> tag) {
    }

    /**
     * The accumulated strength and weighted centre of a single ore group.
     */
    private static final class Reading {
        double strength;
        double x;
        double y;
        double z;
    }
}
