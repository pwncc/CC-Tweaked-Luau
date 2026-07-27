// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.computer.blocks;

import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.component.ComputerComponent;
import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.pocket.IPocketAccess;
import dan200.computercraft.api.pocket.IPocketUpgrade;
import dan200.computercraft.api.upgrades.UpgradeData;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.impl.PocketUpgrades;
import dan200.computercraft.shared.computer.core.ComputerFamily;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.computer.core.TerminalSize;
import dan200.computercraft.shared.media.MountMedia;
import dan200.computercraft.shared.media.items.RomChipItem;
import dan200.computercraft.shared.util.DataComponentUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A tiny, headless embedded computer.
 * <p>
 * The nano computer has no screen and no GUI. It boots whatever {@linkplain RomChipItem ROM chip} is slotted into
 * it (written elsewhere, such as in a disk drive attached to a full-size computer), and up to three pocket upgrades
 * (wireless modems, speakers, and so on) can be fitted invisibly inside it as internal modules.
 * <p>
 * Everything is done by clicking the block: right-click an item to fit it, sneak-click to pop parts back out, and
 * click with an empty hand to power it up.
 */
public class NanoComputerBlockEntity extends ComputerBlockEntity {
    /** The component exposing this block entity to {@code chip} API. */
    public static final ComputerComponent<NanoComputerBlockEntity> COMPONENT = ComputerComponent.create(ComputerCraftAPI.MOD_ID, "nano_computer");

    private static final TerminalSize TERMINAL_SIZE = new TerminalSize(13, 4);

    private static final int SLOT_CHIP = 0;
    private static final int MODULE_SLOTS = 3;
    /** The computer sides the internal module bays occupy. External peripherals are blocked on these. */
    private static final ComputerSide[] MODULE_SIDES = { ComputerSide.BACK, ComputerSide.TOP, ComputerSide.BOTTOM };

    private final NonNullList<ItemStack> items = NonNullList.withSize(1 + MODULE_SLOTS, ItemStack.EMPTY);
    private final @Nullable NanoModuleAccess[] modules = new NanoModuleAccess[MODULE_SLOTS];
    private boolean modulesDirty = true;
    private @Nullable ServerComputer moduleComputer;

    /** The mount for the installed chip, prepared on the server thread and read by the computer thread. */
    private volatile @Nullable Mount chipMount;

    public NanoComputerBlockEntity(BlockEntityType<? extends ComputerBlockEntity> type, BlockPos pos, BlockState state, ComputerFamily family) {
        super(type, pos, state, family);
    }

    @Override
    protected TerminalSize defaultTerminalSize() {
        return TERMINAL_SIZE;
    }

    @Override
    protected void configureComputer(ServerComputer.Properties properties) {
        properties.addComponent(COMPONENT, this);
    }

    @Override
    protected boolean isPeripheralBlockedOnSide(ComputerSide localSide) {
        for (var side : MODULE_SIDES) {
            if (side == localSide) return true;
        }
        return false;
    }

    @Override
    protected void serverTick() {
        if (chipMount == null && !items.get(SLOT_CHIP).isEmpty()) prepareChipMount();

        super.serverTick();

        var computer = getServerComputer();
        if (computer == null) return;

        if (computer != moduleComputer || modulesDirty) {
            moduleComputer = computer;
            modulesDirty = false;
            for (var slot = 0; slot < MODULE_SLOTS; slot++) refreshModule(computer, slot);
        }

        for (var module : modules) {
            if (module != null) module.update();
        }
    }

    private void refreshModule(ServerComputer computer, int slot) {
        var level = getLevel();
        var stack = items.get(1 + slot);
        var upgrade = stack.isEmpty() || level == null ? null
            : PocketUpgrades.instance().get(level.registryAccess(), stack);

        if (upgrade == null) {
            modules[slot] = null;
            computer.setPeripheral(MODULE_SIDES[slot], null);
        } else {
            var module = modules[slot] = new NanoModuleAccess(slot, upgrade);
            module.peripheral = upgrade.upgrade().createPeripheral(module);
            computer.setPeripheral(MODULE_SIDES[slot], module.peripheral);
        }
    }

    /**
     * Prepare (or re-prepare) the chip mount. Must be called on the server thread; the mount itself is later read
     * from the computer thread when the computer boots.
     */
    private void prepareChipMount() {
        var stack = items.get(SLOT_CHIP);
        chipMount = !stack.isEmpty() && getLevel() instanceof ServerLevel level
            ? MountMedia.CHIP.createDataMount(stack, level)
            : null;
        setChanged();
    }

    /**
     * Get the mount of the installed chip, if any. Safe to call from the computer thread.
     *
     * @return The chip's mount.
     */
    public @Nullable Mount getChipMount() {
        return chipMount;
    }

    /**
     * Get the installed ROM chip.
     *
     * @return The installed chip, possibly empty.
     */
    public ItemStack getChip() {
        return items.get(SLOT_CHIP);
    }

    /**
     * Install a chip or internal module from the player's hand.
     *
     * @param player The interacting player.
     * @param stack  The held item.
     */
    public void installItem(Player player, ItemStack stack) {
        if (stack.getItem() instanceof RomChipItem) {
            var previous = items.get(SLOT_CHIP);
            items.set(SLOT_CHIP, takeOne(player, stack));
            if (!previous.isEmpty()) giveOrDrop(player, previous);
            prepareChipMount();
            rebootComputer();
            feedback(player, "gui.computercraft.nano.chip_installed");
            return;
        }

        var level = getLevel();
        var upgrade = level == null ? null : PocketUpgrades.instance().get(level.registryAccess(), stack);
        if (upgrade == null) return;

        for (var slot = 0; slot < MODULE_SLOTS; slot++) {
            if (items.get(1 + slot).isEmpty()) {
                items.set(1 + slot, takeOne(player, stack));
                modulesDirty = true;
                setChanged();
                feedback(player, "gui.computercraft.nano.module_installed");
                return;
            }
        }
        feedback(player, "gui.computercraft.nano.bays_full");
    }

    private static ItemStack takeOne(Player player, ItemStack stack) {
        var taken = stack.copyWithCount(1);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        return taken;
    }

    /**
     * Pop out the next removable part: first the chip, then modules, and failing that shut the computer down.
     *
     * @param player The interacting player.
     */
    public void ejectNext(Player player) {
        if (!items.get(SLOT_CHIP).isEmpty()) {
            ejectChip(player);
            feedback(player, "gui.computercraft.nano.chip_ejected");
            return;
        }

        for (var slot = MODULE_SLOTS - 1; slot >= 0; slot--) {
            var stack = items.get(1 + slot);
            if (!stack.isEmpty()) {
                items.set(1 + slot, ItemStack.EMPTY);
                modulesDirty = true;
                setChanged();
                giveOrDrop(player, stack);
                feedback(player, "gui.computercraft.nano.module_ejected");
                return;
            }
        }

        var computer = getServerComputer();
        if (computer != null && computer.isOn()) {
            computer.shutdown();
            feedback(player, "gui.computercraft.nano.shutdown");
        } else {
            feedback(player, "gui.computercraft.nano.empty");
        }
    }

    /**
     * Remove the installed chip, giving it to a player or dropping it into the world.
     *
     * @param player The player to give the chip to, or {@code null} to drop it.
     */
    public void ejectChip(@Nullable Player player) {
        var chip = items.get(SLOT_CHIP);
        if (chip.isEmpty()) return;

        items.set(SLOT_CHIP, ItemStack.EMPTY);
        chipMount = null;
        setChanged();
        giveOrDrop(player, chip);
        rebootComputer();
    }

    /**
     * Turn the computer on (if needed) and report its state to the player.
     *
     * @param player The interacting player.
     */
    public void activate(Player player) {
        var computer = createServerComputer();
        var wasOn = computer.isOn();
        if (!wasOn) computer.turnOn();

        var name = getLabel() != null ? getLabel() : "#" + computer.getID();
        var chip = items.get(SLOT_CHIP);
        var chipName = chip.isEmpty()
            ? Component.translatable("gui.computercraft.nano.no_chip")
            : chipLabel(chip);
        player.displayClientMessage(
            Component.translatable(wasOn ? "gui.computercraft.nano.running" : "gui.computercraft.nano.booting", name, chipName),
            true
        );
    }

    private static Component chipLabel(ItemStack chip) {
        var label = DataComponentUtil.getCustomName(chip);
        return label != null ? Component.literal(label) : Component.translatable("gui.computercraft.nano.unlabelled_chip");
    }

    /** Drop every installed item into the world, e.g. when the block is destroyed. */
    public void dropContents() {
        var level = getLevel();
        if (level == null) return;
        for (var slot = 0; slot < items.size(); slot++) {
            var stack = items.get(slot);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, getBlockPos().getX(), getBlockPos().getY(), getBlockPos().getZ(), stack);
                items.set(slot, ItemStack.EMPTY);
            }
        }
        chipMount = null;
    }

    private void giveOrDrop(@Nullable Player player, ItemStack stack) {
        var level = getLevel();
        if (player != null && player.getInventory().add(stack)) return;
        if (level != null) {
            Containers.dropItemStack(level, getBlockPos().getX(), getBlockPos().getY() + 0.5, getBlockPos().getZ(), stack);
        }
    }

    private void rebootComputer() {
        var computer = getServerComputer();
        if (computer == null) {
            startOn = true;
        } else if (computer.isOn()) {
            computer.reboot();
        } else {
            computer.turnOn();
        }
    }

    private static void feedback(Player player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    @Override
    protected void loadServer(CompoundTag nbt, HolderLookup.Provider registries) {
        super.loadServer(nbt, registries);
        for (var slot = 0; slot < items.size(); slot++) items.set(slot, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(nbt, items, registries);
        modulesDirty = true;
        chipMount = null;
    }

    @Override
    public void saveAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.saveAdditional(nbt, registries);
        ContainerHelper.saveAllItems(nbt, items, registries);
    }

    /**
     * Exposes an internal module bay as a pocket upgrade holder, so any pocket upgrade can run inside the nano.
     */
    private final class NanoModuleAccess implements IPocketAccess {
        private final int slot;
        private UpgradeData<IPocketUpgrade> upgrade;
        private @Nullable IPeripheral peripheral;

        NanoModuleAccess(int slot, UpgradeData<IPocketUpgrade> upgrade) {
            this.slot = slot;
            this.upgrade = upgrade;
        }

        void update() {
            upgrade.upgrade().update(this, peripheral);
        }

        @Override
        public ServerLevel getLevel() {
            return (ServerLevel) Objects.requireNonNull(NanoComputerBlockEntity.this.getLevel());
        }

        @Override
        public Vec3 getPosition() {
            return Vec3.atCenterOf(getBlockPos());
        }

        @Override
        public @Nullable Entity getEntity() {
            return null;
        }

        @Override
        public int getColour() {
            return -1;
        }

        @Override
        public void setColour(int colour) {
        }

        @Override
        public int getLight() {
            return -1;
        }

        @Override
        public void setLight(int colour) {
        }

        @Override
        public @Nullable UpgradeData<IPocketUpgrade> getUpgrade() {
            return upgrade;
        }

        @Override
        public void setUpgrade(@Nullable UpgradeData<IPocketUpgrade> upgrade) {
            if (upgrade == null) {
                items.set(1 + slot, ItemStack.EMPTY);
                modulesDirty = true;
            } else {
                this.upgrade = upgrade;
                items.set(1 + slot, upgrade.getUpgradeItem());
            }
            setChanged();
        }

        @Override
        public DataComponentPatch getUpgradeData() {
            return upgrade.data();
        }

        @Override
        public void setUpgradeData(DataComponentPatch data) {
            upgrade = UpgradeData.of(upgrade.holder(), data);
            items.set(1 + slot, upgrade.getUpgradeItem());
            setChanged();
        }

        @Override
        public void invalidatePeripheral() {
            modulesDirty = true;
        }
    }
}
