package com.gregtechceu.gtceu.integration.ae2.gridservice;

import com.gregtechceu.gtceu.integration.ae2.machine.feature.multiblock.IMEStockingPart;

import net.minecraft.nbt.CompoundTag;

import appeng.api.networking.GridServices;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridServiceProvider;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public class StockingGridService implements IStockingService, IGridServiceProvider {

    public static void register() {
        GridServices.register(IStockingService.class, StockingGridService.class);
    }

    private final IGrid grid;

    private final Set<IMEStockingPart> parts = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<AEKey, Set<IMEStockingPart>> keyToParts = new HashMap<>();
    private final Map<IMEStockingPart, Set<AEKey>> partKeys = new IdentityHashMap<>();
    private final Object2LongMap<AEKey> lastAmounts = new Object2LongOpenHashMap<>();
    private final Set<IMEStockingPart> pendingRefresh = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<IMEStockingPart, Object2LongMap<AEKey>> pendingChanges = new IdentityHashMap<>();

    public StockingGridService(IGrid grid) {
        this.grid = grid;
    }

    @Override
    public void addNode(IGridNode node, @Nullable CompoundTag savedData) {
        if (node.getOwner() instanceof IMEStockingPart part) {
            parts.add(part);
            pendingRefresh.add(part);
        }
    }

    @Override
    public void removeNode(IGridNode node) {
        if (node.getOwner() instanceof IMEStockingPart part) {
            unregister(part);
        }
    }

    @Override
    public void markForRefresh(IMEStockingPart part) {
        if (parts.contains(part)) {
            pendingRefresh.add(part);
        }
    }

    private void unregister(IMEStockingPart part) {
        parts.remove(part);
        pendingRefresh.remove(part);
        pendingChanges.remove(part);
        Set<AEKey> keys = partKeys.remove(part);
        if (keys != null) {
            for (AEKey key : keys) {
                removeInterest(key, part);
            }
        }
    }

    private void removeInterest(AEKey key, IMEStockingPart part) {
        Set<IMEStockingPart> interested = keyToParts.get(key);
        if (interested != null) {
            interested.remove(part);
            if (interested.isEmpty()) {
                keyToParts.remove(key);
                lastAmounts.removeLong(key);
            }
        }
    }

    @Override
    public void onServerStartTick() {
        if (parts.isEmpty()) {
            return;
        }

        KeyCounter cached = null;

        if (!pendingRefresh.isEmpty()) {
            cached = getCachedInventory();
            for (IMEStockingPart part : pendingRefresh) {
                refreshRegistration(part, cached);
                pendingChanges.remove(part);
                if (part.isOnline()) {
                    fullSync(part, cached);
                }
            }
            pendingRefresh.clear();
        }

        if (!keyToParts.isEmpty()) {
            if (cached == null) {
                cached = getCachedInventory();
            }
            for (var entry : keyToParts.entrySet()) {
                AEKey key = entry.getKey();
                long amount = cached.get(key);
                if (lastAmounts.containsKey(key) && lastAmounts.getLong(key) == amount) {
                    continue;
                }
                lastAmounts.put(key, amount);
                for (IMEStockingPart part : entry.getValue()) {
                    if (part.isOnline()) {
                        pendingChanges.computeIfAbsent(part, p -> new Object2LongOpenHashMap<>()).put(key, amount);
                    }
                }
            }
        }

        if (!pendingChanges.isEmpty()) {
            var it = pendingChanges.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                IMEStockingPart part = entry.getKey();
                if (part.isStockSyncDue()) {
                    part.acceptStockUpdate(entry.getValue());
                    it.remove();
                }
            }
        }
    }

    private void refreshRegistration(IMEStockingPart part, KeyCounter cached) {
        Set<AEKey> newKeys = part.getStockingKeys();
        Set<AEKey> oldKeys = partKeys.getOrDefault(part, Collections.emptySet());

        for (AEKey key : oldKeys) {
            if (!newKeys.contains(key)) {
                removeInterest(key, part);
            }
        }
        for (AEKey key : newKeys) {
            keyToParts.computeIfAbsent(key, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(part);
            lastAmounts.put(key, cached.get(key));
        }

        if (newKeys.isEmpty()) {
            partKeys.remove(part);
        } else {
            partKeys.put(part, newKeys);
        }
    }

    private void fullSync(IMEStockingPart part, KeyCounter cached) {
        Object2LongMap<AEKey> amounts = new Object2LongOpenHashMap<>();
        for (AEKey key : partKeys.getOrDefault(part, Collections.emptySet())) {
            amounts.put(key, cached.get(key));
        }
        part.refreshStock(amounts);
    }

    private KeyCounter getCachedInventory() {
        IStorageService storage = grid.getStorageService();
        return storage.getCachedInventory();
    }
}
