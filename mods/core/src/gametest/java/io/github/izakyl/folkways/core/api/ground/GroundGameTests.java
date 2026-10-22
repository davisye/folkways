package io.github.izakyl.folkways.core.api.ground;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import io.github.izakyl.folkways.core.api.terms.Structures;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GroundGameTests {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(GroundGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void reachAcrossSectionsFollowsTheWorldAndTrialsLeaveItAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        room(helper, 6, 8);
        room(helper, 13, 15);
        Mob walker = walker(helper, at(helper, 3, 1, 7));
        BlockPos far = at(helper, 20, 1, 7);
        BlockPos sealed = at(helper, 10, 1, 14);
        try (Grounds.Watch watch = Grounds.watch(level, BoundingBox.fromCorners(at(helper, 2, 0, 5), at(helper, 21, 3, 16)))) {
            Ground ground = watch.now(List.of(walker));
            helper.assertTrue(ground.standable(far), "the far end of the hall is somewhere to stand");
            helper.assertTrue(ground.reachable(far), "reach carries across several 8x8x8 sections");
            helper.assertTrue(ground.standable(sealed) && !ground.reachable(sealed), "a sealed room is not reached");
            helper.assertTrue(!ground.stancesFor(at(helper, 20, 2, 7)).isEmpty(), "a cell in the hall can be worked");
            helper.assertTrue(ground.stancesFor(at(helper, 10, 2, 14)).isEmpty(), "a cell in the sealed room cannot");

            Map<BlockPos, BlockState> wall = new HashMap<>();
            for (int z = 6; z <= 8; z++) {
                wall.put(at(helper, 12, 1, z), STONE);
                wall.put(at(helper, 12, 2, z), STONE);
            }
            Ground walled = ground.with(wall);
            helper.assertTrue(!walled.reachable(far), "a trial wall cuts the hall");
            helper.assertTrue(walled.lostSince(ground).contains(far), "the cut-off end is reported lost");
            helper.assertTrue(watch.now(List.of(walker)).reachable(far), "a trial leaves the watched ground alone");

            wall.forEach((cell, state) -> level.setBlock(cell, state, 3));
            helper.assertTrue(!watch.now(List.of(walker)).reachable(far), "a real wall is seen at once");
            wall.keySet().forEach(cell -> level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3));
            helper.assertTrue(watch.now(List.of(walker)).reachable(far), "taking it down opens the hall again");
            helper.assertTrue(!ground.known(at(helper, 2, 0, 5).offset(-40, 0, 0)), "outside the box is not known");
        }
        helper.succeed();
    }

    private static void room(GameTestHelper helper, int nearZ, int farZ) {
        for (int x = 2; x <= 21; x++) {
            for (int z = nearZ - 1; z <= farZ + 1; z++) {
                for (int y = 0; y <= 3; y++) {
                    boolean inside = x > 2 && x < 21 && z >= nearZ && z <= farZ && y >= 1 && y <= 2;
                    helper.getLevel().setBlock(at(helper, x, y, z), inside ? Blocks.AIR.defaultBlockState() : STONE, 3);
                }
            }
        }
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void anOccupiedSealedRoomIsNotAnExit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        room(helper, 6, 8);
        BlockPos exit = at(helper, 3, 1, 7);
        BlockPos inside = at(helper, 20, 1, 7);
        Mob body = walker(helper, inside);
        try (Grounds.Watch watch = Grounds.watch(level, BoundingBox.fromCorners(at(helper, 2, 0, 5), at(helper, 21, 3, 9)))) {
            Ground ground = watch.now(List.of(body));
            Ground.Access before = ground.accessFrom(List.of(exit));
            helper.assertTrue(before.distance(inside) > 0 && before.path(inside).getLast().equals(exit),
                "a route is proved to the actual exit");
            Map<BlockPos, BlockState> wall = new HashMap<>();
            for (int z = 6; z <= 8; z++) {
                wall.put(at(helper, 12, 1, z), STONE);
                wall.put(at(helper, 12, 2, z), STONE);
            }
            Ground trial = ground.with(wall);
            helper.assertTrue(trial.reachable(inside), "the ordinary survey still starts from the body inside");
            helper.assertTrue(trial.accessFrom(List.of(exit)).distance(inside) < 0,
                "the body's own position cannot make a sealed room a way out");
            helper.assertTrue(ground.accessFrom(List.of()).distance(inside) < 0,
                "a survey without a confirmed exit proves no escape");
            helper.assertTrue(before.distance(inside) > 0, "a virtual closure leaves the original proof alone");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void playerEscapeUsesThePlayersHeight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        room(helper, 6, 8);
        BlockPos exit = at(helper, 3, 1, 7);
        BlockPos inside = at(helper, 20, 1, 7);
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        player.setPos(Vec3.atBottomCenterOf(inside));
        try (Grounds.Watch watch = Grounds.watch(level, BoundingBox.fromCorners(at(helper, 2, 0, 5), at(helper, 21, 3, 9)))) {
            Ground ground = watch.now(player);
            helper.assertTrue(ground.accessFrom(List.of(exit)).distance(inside) >= 0,
                "a player fits in the original two-high hall");
            Map<BlockPos, BlockState> beam = new HashMap<>();
            for (int z = 6; z <= 8; z++) {
                beam.put(at(helper, 12, 2, z), STONE);
            }
            helper.assertTrue(ground.with(beam).accessFrom(List.of(exit)).distance(inside) < 0,
                "a one-high gap does not count as an escape for a standing player");
            Ground pig = watch.now(List.of(walker(helper, inside)));
            helper.assertTrue(pig.with(beam).accessFrom(List.of(exit)).distance(inside) >= 0,
                "the same gap really is walkable for a smaller body");
        }
        helper.succeed();
    }

    private static Mob walker(GameTestHelper helper, BlockPos feet) {
        Mob body = EntityType.PIG.create(helper.getLevel());
        body.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
        return body;
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aSleepingBodyNeedsAnExitItCanUseAfterWaking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        room(helper, 6, 8);
        BlockPos exit = at(helper, 3, 1, 7);
        BlockPos inside = at(helper, 20, 1, 7);
        Mob body = EntityType.VILLAGER.create(level);
        body.setPos(Vec3.atBottomCenterOf(inside));
        body.setPose(net.minecraft.world.entity.Pose.SLEEPING);
        body.refreshDimensions();
        helper.assertTrue(body.getBbHeight() < 1.0F, "the resting body is physically shorter than the gap");
        try (Grounds.Watch watch = Grounds.watch(level, BoundingBox.fromCorners(at(helper, 2, 0, 5), at(helper, 21, 3, 9)))) {
            Ground ground = watch.now(body);
            helper.assertTrue(ground.accessFrom(List.of(exit)).distance(inside) >= 0, "the original hall has a usable exit");
            Map<BlockPos, BlockState> beam = new HashMap<>();
            for (int z = 6; z <= 8; z++) {
                beam.put(at(helper, 12, 2, z), STONE);
            }
            helper.assertTrue(ground.with(beam).accessFrom(List.of(exit)).distance(inside) < 0,
                "a gap only the sleeping pose fits is not a way out after waking");
            helper.assertTrue(body.getPose() == net.minecraft.world.entity.Pose.SLEEPING && body.getBbHeight() < 1.0F,
                "the proof neither wakes nor resizes the real body");
        }
        helper.succeed();
    }

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void anExitProofSharesWorkBetweenThirtyEightMatchingBodies(GameTestHelper helper) {
        room(helper, 6, 8);
        BlockPos exit = at(helper, 3, 1, 7);
        BlockPos inside = at(helper, 20, 1, 7);
        try (Grounds.Watch watch = hallWatch(helper)) {
            List<Mob> bodies = new ArrayList<>();
            for (int i = 0; i < 38; i++) {
                bodies.add(walker(helper, inside));
            }
            Ground first = watch.now(bodies.getFirst());
            Ground.Access access = first.beginAccess(List.of(exit));
            Ground.Budget zero = new Ground.Budget(0, 0);
            helper.assertTrue(!access.advance(zero) && !access.complete() && zero.used() == 0,
                "no work allowance leaves the proof unfinished");
            helper.assertTrue(watch.stats().sectionBuilds() == 0,
                "even section surveying waits for a work allowance");
            int slices = drain(helper, access, 17);
            helper.assertTrue(slices > 1 && access.distance(inside) > 0, "small slices eventually prove the full route");
            long work = watch.stats().work();
            long sections = watch.stats().sectionBuilds();
            for (Mob body : bodies) {
                Ground.Access reused = watch.now(body).beginAccess(List.of(exit, exit));
                helper.assertTrue(reused == access && reused.complete(), "matching bodies share one exit graph");
                helper.assertTrue(reused.advance(new Ground.Budget(0, 0)), "a cached proof needs no further work");
            }
            helper.assertTrue(watch.stats().work() == work && watch.stats().sectionBuilds() == sections,
                "the other thirty-seven bodies cause no repeated graph or section work");
            Map<BlockPos, BlockState> trial = Map.of(at(helper, 10, 2, 7), STONE);
            Ground.Access a = first.with(trial).beginAccess(List.of(exit));
            Ground.Access b = watch.now(bodies.getLast()).with(trial).beginAccess(List.of(exit));
            helper.assertTrue(a == b && a != access, "the same virtual placement shares its own distinct proof");
            System.out.println("GROUND_SHARED_38 slices=" + slices + " stats=" + watch.stats());
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aSingleWorkUnitCanResumeSectionPreparation(GameTestHelper helper) {
        room(helper, 6, 8);
        BlockPos inside = at(helper, 20, 1, 7);
        try (Grounds.Watch watch = hallWatch(helper)) {
            Ground ground = watch.now(walker(helper, inside));
            helper.assertTrue(!ground.prepare(inside, new Ground.Budget(0, 0)), "an unprepared section waits");
            int slices = 0;
            while (slices++ < 1024) {
                Ground.Budget budget = new Ground.Budget(1, 0);
                boolean ready = ground.prepare(inside, budget);
                helper.assertTrue(budget.used() <= 1, "a section never overruns its deterministic work allowance");
                if (ready) {
                    break;
                }
            }
            helper.assertTrue(slices > 1 && slices < 1024 && ground.standable(inside),
                "even a one-unit allowance resumes instead of restarting the section");
            Ground.Access access = ground.beginAccess(List.of(at(helper, 3, 1, 7)));
            drain(helper, access, 1);
            helper.assertTrue(access.distance(inside) >= 0, "one-unit search slices also retain their pending edge");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void bodyAbilitiesAndIndependentOriginsDoNotLeakBetweenProofs(GameTestHelper helper) {
        room(helper, 6, 8);
        room(helper, 13, 15);
        BlockPos exit = at(helper, 3, 1, 7);
        BlockPos inside = at(helper, 20, 1, 7);
        BlockPos sealed = at(helper, 10, 1, 14);
        Mob body = walker(helper, inside);
        try (Grounds.Watch watch = Grounds.watch(helper.getLevel(),
                BoundingBox.fromCorners(at(helper, 2, 0, 5), at(helper, 21, 3, 16)))) {
            Ground original = watch.now(body);
            Ground.Access access = original.accessFrom(List.of(exit));
            Ground occupied = watch.now(walker(helper, sealed));
            helper.assertTrue(occupied.reachable(sealed) && !original.reachable(sealed),
                "ordinary reach preserves each body's own origin even when geometry is shared");
            body.setPathfindingMalus(PathType.WALKABLE, -1.0F);
            helper.assertTrue(!original.valid(), "changing a body's movement ability invalidates its captured view");
            Ground.Access changed = watch.now(body).accessFrom(List.of(exit));
            helper.assertTrue(changed != access && changed.distance(inside) < 0,
                "a current body snapshot cannot reuse another movement profile's positive proof");
            helper.assertTrue(access.valid() && access.distance(inside) > 0,
                "a completed graph still describes its original immutable movement profile");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void cacheEvictionPreservesHeldProgressAndCloseDropsAllLookups(GameTestHelper helper) {
        room(helper, 6, 8);
        BlockPos inside = at(helper, 20, 1, 7);
        Grounds.Watch watch = hallWatch(helper);
        try {
            Ground ground = watch.now(walker(helper, inside));
            Ground.Access held = ground.beginAccess(List.of(at(helper, 3, 1, 7)));
            held.advance(new Ground.Budget(31, 0));
            long before = watch.stats().work();
            for (int i = 0; i < 64; i++) {
                ground.with(Map.of(at(helper, 12, 2, 7).offset(0, i, 0), STONE))
                    .beginAccess(List.of(at(helper, 3, 1, 7)));
            }
            helper.assertTrue(watch.stats().accessEntries() <= 32 && watch.stats().geometryEntries() <= 48,
                "lookup caches have fixed entry limits");
            helper.assertTrue(held.valid(), "eviction cannot invalidate a retained search");
            for (int i = 1; i <= 600; i++) {
                ground.prepare(inside.offset(i * 8, 0, 0), new Ground.Budget(1, 0));
            }
            long builds = watch.stats().sectionBuilds();
            held.advance(new Ground.Budget(1, 0));
            helper.assertTrue(watch.stats().sectionBuilds() == builds,
                "even section eviction cannot restart a search's pinned partial section read");
            drain(helper, held, 23);
            helper.assertTrue(held.distance(inside) > 0 && watch.stats().work() > before,
                "a held search finishes after its lookup entry has been evicted");
            watch.close();
            helper.assertTrue(!held.valid() && !held.complete() && held.distance(inside) < 0,
                "closed terrain cannot provide a positive escape proof");
            helper.assertTrue(watch.stats().accessEntries() == 0 && watch.stats().geometryEntries() == 0
                    && watch.stats().cachedCells() == 0, "close clears every lookup cache");
        } finally {
            watch.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void terrainWritesDiscardFinishedAndUnfinishedEscapeProofs(GameTestHelper helper) {
        room(helper, 6, 8);
        BlockPos exit = at(helper, 3, 1, 7);
        BlockPos inside = at(helper, 20, 1, 7);
        Mob body = walker(helper, inside);
        try (Grounds.Watch watch = hallWatch(helper)) {
            Ground.Access finished = watch.now(body).accessFrom(List.of(exit));
            Ground.Access pending = watch.now(body).beginAccess(List.of(inside));
            pending.advance(new Ground.Budget(7, 0));
            long revision = watch.revision();
            for (int z = 6; z <= 8; z++) {
                helper.getLevel().setBlock(at(helper, 12, 1, z), STONE, 3);
                helper.getLevel().setBlock(at(helper, 12, 2, z), STONE, 3);
            }
            helper.assertTrue(watch.revision() > revision && !finished.valid() && !pending.valid(),
                "a real terrain change invalidates both kinds of proof at once");
            helper.assertTrue(finished.distance(inside) < 0 && finished.path(inside).isEmpty(),
                "stale proofs expose no positive route");
            helper.assertTrue(watch.now(body).accessFrom(List.of(exit)).distance(inside) < 0,
                "the fresh graph sees the closed passage");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void movingBarriersAndKeepoutSourcesInvalidateGeometry(GameTestHelper helper) {
        room(helper, 6, 8);
        BlockPos exit = at(helper, 3, 1, 7);
        BlockPos inside = at(helper, 20, 1, 7);
        Mob body = walker(helper, inside);
        AtomicReference<List<AABB>> barriers = new AtomicReference<>(List.of());
        Runnable remove = Structures.install((level, area) -> level == helper.getLevel() ? barriers.get() : List.of());
        try (Grounds.Watch watch = hallWatch(helper)) {
            Ground.Access open = watch.now(body).accessFrom(List.of(exit));
            barriers.set(List.of(AABB.encapsulatingFullBlocks(at(helper, 12, 1, 6), at(helper, 12, 2, 8))));
            Ground.Access shut = watch.now(body).accessFrom(List.of(exit));
            helper.assertTrue(!open.valid() && shut.distance(inside) < 0,
                "a moving structure barrier invalidates and changes the graph without a block write");
            barriers.set(List.of());
            Ground.Access reopened = watch.now(body).accessFrom(List.of(exit));
            helper.assertTrue(reopened.distance(inside) > 0, "removing the moving barrier restores the route");
            Runnable keepout = Keepouts.install((level, x, y, z) -> level == helper.getLevel()
                && x == at(helper, 12, 1, 7).getX());
            try {
                helper.assertTrue(!reopened.valid(), "installing a keepout invalidates existing proofs immediately");
                helper.assertTrue(watch.now(body).accessFrom(List.of(exit)).distance(inside) < 0,
                    "the new rule is included in subsequent graphs");
            } finally {
                keepout.run();
            }
            helper.assertTrue(watch.now(body).accessFrom(List.of(exit)).distance(inside) > 0,
                "removing the rule invalidates its cached geometry too");
        } finally {
            remove.run();
        }
        helper.succeed();
    }

    private static Grounds.Watch hallWatch(GameTestHelper helper) {
        return Grounds.watch(helper.getLevel(),
            BoundingBox.fromCorners(at(helper, 2, 0, 5), at(helper, 21, 3, 9)));
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aPlayersChangedDimensionsInvalidateItsSizingView(GameTestHelper helper) {
        room(helper, 6, 8);
        BlockPos inside = at(helper, 20, 1, 7);
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel());
        player.setPose(net.minecraft.world.entity.Pose.STANDING);
        player.refreshDimensions();
        player.setPos(Vec3.atBottomCenterOf(inside));
        try (Grounds.Watch watch = hallWatch(helper)) {
            Ground standing = watch.now(player);
            Ground.Access tall = standing.accessFrom(List.of(at(helper, 3, 1, 7)));
            player.setPose(net.minecraft.world.entity.Pose.SWIMMING);
            player.refreshDimensions();
            helper.assertTrue(!standing.valid(), "changing the real player's size invalidates the captured proxy view");
            Ground swimming = watch.now(player);
            helper.assertTrue(swimming.valid() && swimming.beginAccess(List.of(at(helper, 3, 1, 7))) != tall,
                "the current player receives the matching dimensions' graph");
        } finally {
            player.setPose(net.minecraft.world.entity.Pose.STANDING);
            player.refreshDimensions();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void railOriginRulesKeepPositionDependentGraphsSeparate(GameTestHelper helper) {
        room(helper, 6, 8);
        BlockPos exit = at(helper, 3, 1, 7);
        BlockPos inside = at(helper, 20, 1, 7);
        helper.getLevel().setBlock(exit, Blocks.RAIL.defaultBlockState(), 3);
        helper.getLevel().setBlock(inside, Blocks.RAIL.defaultBlockState(), 3);
        Mob first = walker(helper, inside);
        Mob second = walker(helper, exit);
        try (Grounds.Watch watch = hallWatch(helper)) {
            Ground a = watch.now(first);
            Ground.Access fromInsideRail = a.beginAccess(List.of(exit));
            Ground.Access fromExitRail = watch.now(second).beginAccess(List.of(exit));
            helper.assertTrue(fromInsideRail != fromExitRail,
                "rail permissions read the local region around the exact origin, not just an on-rail boolean");
            first.setPos(Vec3.atBottomCenterOf(exit));
            helper.assertTrue(!a.valid() && watch.now(first).beginAccess(List.of(exit)) == fromExitRail,
                "moving between rail origins renews the capability view and selects the matching graph");
            Mob left = walker(helper, at(helper, 6, 1, 7));
            Mob right = walker(helper, at(helper, 18, 1, 7));
            Map<BlockPos, BlockState> rail = Map.of(at(helper, 10, 1, 7), Blocks.RAIL.defaultBlockState());
            Ground virtual = watch.now(left).with(rail);
            helper.assertTrue(virtual.beginAccess(List.of(exit)) != watch.now(right).with(rail).beginAccess(List.of(exit)),
                "a rail overlay conservatively keeps different origins separate too");
            left.setPos(Vec3.atBottomCenterOf(at(helper, 7, 1, 7)));
            helper.assertTrue(!virtual.valid(), "moving with a rail overlay invalidates that position-dependent view");
        }
        helper.succeed();
    }

    private static int drain(GameTestHelper helper, Ground.Access access, int work) {
        int slices = 0;
        while (!access.complete() && slices++ < 200000) {
            Ground.Budget budget = new Ground.Budget(work, 0);
            access.advance(budget);
            helper.assertTrue(access.valid() && budget.used() <= work, "every slice stays valid and within its allowance");
        }
        helper.assertTrue(access.complete(), "the bounded search eventually completes");
        return slices;
    }
}
