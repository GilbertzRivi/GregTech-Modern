package com.gregtechceu.gtceu.integration.ae2.machine.feature.multiblock;

import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.integration.ae2.gridservice.IStockingService;
import com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlotList;

import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;

import appeng.api.networking.IGrid;
import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

public interface IMEStockingPart extends IAutoPullPart {

    @Override
    default void addedToController(IMultiController controller) {
        // ensure that no other stocking bus on this multiblock is configured to hold the same item.
        // that we have in our own bus.
        setAutoPullTest(stack -> !this.testConfiguredInOtherPart(stack));
        // also ensure that our current config is valid given other inputs
        if (self().getLevel() instanceof ServerLevel serverLevel) {
            // wait for 1 tick
            // we should not access the part list at this time
            serverLevel.getServer().tell(new TickTask(0, this::validateConfig));
        }
    }

    @Override
    default void removedFromController(IMultiController controller) {
        setAutoPullTest($ -> false);
        if (isAutoPull()) {
            getSlotList().clearInventory(0);
        }
    }

    IConfigurableSlotList getSlotList();

    IManagedGridNode getMainNode();

    boolean isOnline();

    /**
     * @return True if the passed stack is found as a configuration in any other stocking buses on the multiblock.
     */
    boolean testConfiguredInOtherPart(@Nullable GenericStack config);

    /**
     * Test for if any of our configured items are in another stocking bus on the multi
     * we are attached to. Prevents dupes in certain situations.
     */
    default void validateConfig() {
        var slots = getSlotList();
        for (int i = 0; i < slots.getConfigurableSlots(); i++) {
            var slot = slots.getConfigurableSlot(i);
            if (slot.getConfig() != null) {
                GenericStack configuredStack = slot.getConfig();
                if (testConfiguredInOtherPart(configuredStack)) {
                    slot.setConfig(null);
                    slot.setStock(null);
                }
            }
        }
    }

    default Set<AEKey> getStockingKeys() {
        Set<AEKey> keys = new HashSet<>();
        IConfigurableSlotList slots = getSlotList();
        for (int i = 0; i < slots.getConfigurableSlots(); i++) {
            GenericStack config = slots.getConfigurableSlot(i).getConfig();
            if (config != null) {
                keys.add(config.what());
            }
        }
        return keys;
    }

    default boolean acceptStockUpdate(Object2LongMap<AEKey> changed) {
        IConfigurableSlotList slots = getSlotList();
        int min = getMinStackSize();
        boolean surfaced = false;
        for (int i = 0; i < slots.getConfigurableSlots(); i++) {
            IConfigurableSlot slot = slots.getConfigurableSlot(i);
            GenericStack config = slot.getConfig();
            if (config == null) {
                continue;
            }
            AEKey key = config.what();
            if (!changed.containsKey(key)) {
                continue;
            }
            long amount = changed.getLong(key);
            GenericStack newStock = amount >= min ? new GenericStack(key, amount) : null;
            boolean wasEmpty = slot.getStock() == null;
            if (slot.setStockSilent(newStock) && wasEmpty && newStock != null) {
                surfaced = true;
            }
        }
        if (surfaced) {
            slots.onContentsChanged();
        }
        return surfaced;
    }

    default boolean isStockSyncDue() {
        int interval = getTicksPerCycle();
        if (interval <= 0) {
            interval = 40;
        }
        return self().getOffsetTimer() % interval == 0;
    }

    default void refreshStock(Object2LongMap<AEKey> amounts) {
        IConfigurableSlotList slots = getSlotList();
        int min = getMinStackSize();
        boolean anyChanged = false;
        for (int i = 0; i < slots.getConfigurableSlots(); i++) {
            IConfigurableSlot slot = slots.getConfigurableSlot(i);
            GenericStack config = slot.getConfig();
            GenericStack newStock = null;
            if (config != null) {
                long amount = amounts.getOrDefault(config.what(), 0L);
                if (amount >= min) {
                    newStock = new GenericStack(config.what(), amount);
                }
            }
            if (slot.setStockSilent(newStock)) {
                anyChanged = true;
            }
        }
        if (anyChanged) {
            slots.onContentsChanged();
        }
    }

    default void markForRefresh() {
        if (self().isRemote()) {
            return;
        }
        IGrid grid = getMainNode().getGrid();
        if (grid != null) {
            grid.getService(IStockingService.class).markForRefresh(this);
        }
    }

    int getMinStackSize();

    void setMinStackSize(int newSize);

    int getTicksPerCycle();

    void setTicksPerCycle(int newSize);
}
