package io.github.izakyl.folkways.plugins.compost;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.plugins.farming.CropSeeds;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;

/** Per-colony compost preferences, saved with the colony rather than a player's book. */
public final class CompostSettings {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "compost_settings");
    public static final int DEFAULT_RESERVE = 64;
    public static final int MAX_RESERVE = 1_000_000;
    private static final String ENABLED = "enabled";
    private static final String DEFAULT = "default_reserve";
    private static final String OVERRIDES = "reserves";

    private CompostSettings() { }

    public static boolean enabled(Colony colony) {
        CompoundTag tag = colony.kept(ID);
        return !tag.contains(ENABLED, Tag.TAG_BYTE) || tag.getBoolean(ENABLED);
    }

    public static int defaultReserve(Colony colony) {
        CompoundTag tag = colony.kept(ID);
        return tag.contains(DEFAULT, Tag.TAG_INT) ? clamp(tag.getInt(DEFAULT)) : DEFAULT_RESERVE;
    }

    public static int reserve(Colony colony, Item item) {
        CompoundTag overrides = colony.kept(ID).getCompound(OVERRIDES);
        return SeedReserves.alternatives(item).stream()
            .map(alternative -> BuiltInRegistries.ITEM.getKey(alternative).toString())
            .filter(key -> overrides.contains(key, Tag.TAG_INT))
            .mapToInt(key -> clamp(overrides.getInt(key))).max().orElseGet(() -> defaultReserve(colony));
    }

    public static boolean overridden(Colony colony, Item item) {
        CompoundTag overrides = colony.kept(ID).getCompound(OVERRIDES);
        return SeedReserves.alternatives(item).stream()
            .anyMatch(alternative -> overrides.contains(BuiltInRegistries.ITEM.getKey(alternative).toString(), Tag.TAG_INT));
    }

    public static void enabled(Colony colony, boolean enabled) {
        CompoundTag tag = colony.kept(ID).copy();
        tag.putBoolean(ENABLED, enabled);
        colony.keep(ID, tag);
    }

    public static void defaultReserve(Colony colony, int count) {
        CompoundTag tag = colony.kept(ID).copy();
        tag.putInt(DEFAULT, clamp(count));
        colony.keep(ID, tag);
    }

    public static void reserve(Colony colony, Item item, int count) {
        CompoundTag tag = colony.kept(ID).copy();
        CompoundTag overrides = tag.getCompound(OVERRIDES).copy();
        clear(overrides, item);
        overrides.putInt(BuiltInRegistries.ITEM.getKey(SeedReserves.canonical(item)).toString(), clamp(count));
        tag.put(OVERRIDES, overrides);
        colony.keep(ID, tag);
    }

    public static void reset(Colony colony, Item item) {
        CompoundTag tag = colony.kept(ID).copy();
        CompoundTag overrides = tag.getCompound(OVERRIDES).copy();
        clear(overrides, item);
        tag.put(OVERRIDES, overrides);
        colony.keep(ID, tag);
    }

    /** Only seeds in real stores/packs or configured/current crops appear, never the entire catalogue. */
    public static List<Item> discovered(Colony colony, MinecraftServer server) {
        Set<Item> catalogue = CropSeeds.catalogue(server.overworld(), BlockPos.ZERO);
        Set<Item> found = new LinkedHashSet<>();
        for (var level : server.getAllLevels()) {
            for (var at : colony.storesIn(level.dimension())) {
                if (at.in(level) && level.isLoaded(at.block(level))) {
                    colony.store(level, at).ifPresent(store -> inspect(store, catalogue, found));
                }
            }
            for (var resident : colony.residents()) {
                var entity = level.getEntity(resident.id());
                if (entity != null) Bodies.of(entity).ifPresent(body -> inspect(body.pack(), catalogue, found));
            }
            for (var sowing : CropSeeds.sowing(colony, level)) {
                catalogue.stream().filter(item -> Goods.matches(item.getDefaultInstance(), sowing.seed())).forEach(found::add);
            }
            for (var zone : Fronts.of(colony).zonesIn(level)) {
                if (!zone.delegation().equals(FarmingContent.PLOT)) continue;
                found.addAll(CropSeeds.forCrop(level, zone.cells().stream().findFirst().orElse(BlockPos.ZERO),
                    zone.settings().choice(FarmingContent.CROP)));
            }
        }
        return found.stream().map(SeedReserves::canonical).distinct()
            .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString())).toList();
    }

    private static void inspect(Container inventory, Set<Item> catalogue, Set<Item> found) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && catalogue.contains(stack.getItem())) found.add(stack.getItem());
        }
    }

    private static void clear(CompoundTag overrides, Item item) {
        SeedReserves.alternatives(item).forEach(alternative -> overrides.remove(BuiltInRegistries.ITEM.getKey(alternative).toString()));
    }

    private static int clamp(int count) { return Math.clamp(count, 0, MAX_RESERVE); }
}
