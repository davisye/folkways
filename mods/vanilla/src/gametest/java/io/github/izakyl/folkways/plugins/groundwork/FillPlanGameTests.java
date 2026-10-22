package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.ground.Ground;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FillPlanGameTests {

    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final ItemSpec MATERIAL = ItemSpec.of(ResourceLocation.withDefaultNamespace("dirt"));

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FillPlanGameTests.class);
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void aTunnelIsFilledFromItsFarEndAndTheLastGapCloses(GameTestHelper helper) {
        floor(helper);
        tunnel(helper, 7, 15, false);
        Set<BlockPos> cells = box(at(helper, 7, 1, 10), at(helper, 15, 2, 10));
        UUID colony = UUID.randomUUID();
        Mob worker = resident(helper, at(helper, 5, 1, 10));
        ((io.github.izakyl.folkways.core.api.resident.body.Body) worker).joinColony(colony);
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 64));
        try (FillPlan plan = plan(helper, colony, cells)) {
            List<Job> first = refresh(plan, helper, cells);
            helper.assertTrue(!first.isEmpty() && first.stream().allMatch(job -> job.cell().getX() >= at(helper, 12, 1, 10).getX()),
                "only a work band at the deep end opens first, got " + first.stream().map(Job::cell).toList());
            for (int attempt = 0; attempt < cells.size() * 3 && cells.stream().anyMatch(cell -> Survey.open(helper.getLevel().getBlockState(cell))); attempt++) {
                List<Job> jobs = refresh(plan, helper, cells);
                if (jobs.isEmpty() && !plan.steppingAside().isEmpty()) {
                    Node move = plan.steppingAside().getFirst().nodes().getFirst();
                    BlockPos target = move.spec().stances().cells().iterator().next().block(helper.getLevel());
                    worker.setPos(Vec3.atBottomCenterOf(target));
                    helper.assertTrue(Commit.run(helper.getLevel(), move, worker(worker, pack)) instanceof Outcome.Done,
                        "the builder can walk back through the retained passage");
                    continue;
                }
                helper.assertTrue(!jobs.isEmpty(), "the retained passage can be filled by retreating: attempt " + attempt);
                Job job = jobs.getFirst();
                BlockPos stance = job.footings().cells().stream().map(at -> at.block(helper.getLevel()))
                    .min(Comparator.comparingDouble(feet -> feet.distSqr(worker.blockPosition()))).orElseThrow();
                worker.setPos(Vec3.atBottomCenterOf(stance));
                Outcome result = Commit.run(helper.getLevel(), node(plan, job), worker(worker, pack));
                helper.assertTrue(result instanceof Outcome.Done, "a safe step commits, got " + result);
                helper.assertTrue(!worker.isInWall(), "the builder is never buried by its own work");
            }
            helper.assertTrue(cells.stream().allMatch(cell -> helper.getLevel().getBlockState(cell).is(Blocks.DIRT)),
                "every layer, including the access passage, is filled");
            helper.assertTrue(pack.countItem(Items.DIRT) == 64 - cells.size(), "exactly one block is spent per cell");
        } finally {
            worker.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void theSecondOfTwoExitClosuresChecksTheFirstPlacement(GameTestHelper helper) {
        floor(helper);
        tunnel(helper, 7, 15, true);
        BlockPos west = at(helper, 6, 1, 10);
        BlockPos east = at(helper, 16, 1, 10);
        Set<BlockPos> cells = Set.of(west, east);
        Mob inside = resident(helper, at(helper, 11, 1, 10));
        Mob builder = resident(helper, at(helper, 5, 1, 10));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (FillPlan plan = plan(helper, UUID.randomUUID(), cells)) {
            List<Job> jobs = refresh(plan, helper, cells);
            helper.assertTrue(jobs.size() == 2, "each of the two exits is safe when the other remains open");
            Node closeWest = node(plan, jobs.stream().filter(job -> job.cell().equals(west)).findFirst().orElseThrow());
            Node closeEast = node(plan, jobs.stream().filter(job -> job.cell().equals(east)).findFirst().orElseThrow());
            helper.assertTrue(Commit.run(helper.getLevel(), closeWest, worker(builder, pack)) instanceof Outcome.Done,
                "the first door is filled while the other is open");
            builder.setPos(Vec3.atBottomCenterOf(at(helper, 17, 1, 10)));
            helper.assertTrue(Commit.run(helper.getLevel(), closeEast, worker(builder, pack)) instanceof Outcome.Failed,
                "the other already-offered job cannot close the last exit around the bystander");
            helper.assertTrue(helper.getLevel().getBlockState(east).isAir() && pack.countItem(Items.DIRT) == 7,
                "the refused placement changes no block and returns its material");
        } finally {
            inside.discard();
            builder.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void aBystanderEnteringAfterPlanningKeepsTheLastExitOpen(GameTestHelper helper) {
        floor(helper);
        tunnel(helper, 7, 11, false);
        BlockPos mouth = at(helper, 6, 2, 10);
        Set<BlockPos> cells = Set.of(mouth);
        Mob builder = resident(helper, at(helper, 5, 1, 10));
        Mob bystander = null;
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (FillPlan plan = plan(helper, UUID.randomUUID(), cells)) {
            Job job = refresh(plan, helper, cells).getFirst();
            Node seal = node(plan, job);
            bystander = resident(helper, at(helper, 10, 1, 10));
            helper.assertTrue(Commit.run(helper.getLevel(), seal, worker(builder, pack)) instanceof Outcome.Failed,
                "a new bystander is protected even though the seal was planned before it entered");
            helper.assertTrue(pack.countItem(Items.DIRT) == 8 && helper.getLevel().getBlockState(mouth).isAir(),
                "the seal waits without using material");
            bystander.setPos(Vec3.atBottomCenterOf(at(helper, 4, 1, 10)));
            helper.assertTrue(Commit.run(helper.getLevel(), seal, worker(builder, pack)) instanceof Outcome.Done,
                "the same pending seal succeeds once everyone is outside");
        } finally {
            if (bystander != null) {
                bystander.discard();
            }
            builder.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void anIdleResidentGetsARealWalkOutOfTheLastFillCell(GameTestHelper helper) {
        floor(helper);
        UUID colony = UUID.randomUUID();
        BlockPos cell = at(helper, 11, 1, 11);
        Mob body = resident(helper, cell);
        ((io.github.izakyl.folkways.core.api.resident.body.Body) body).joinColony(colony);
        FillPlan plan = plan(helper, colony, Set.of(cell));
        try {
            helper.assertTrue(refresh(plan, helper, Set.of(cell)).isEmpty(), "a cell occupied by the resident waits");
            List<Grown> moves = plan.steppingAside();
            helper.assertTrue(moves.size() == 1, "the idle resident is asked to step aside instead of waiting forever");
            Node move = moves.getFirst().nodes().getFirst();
            helper.assertTrue(move.worker().filter(body.getUUID()::equals).isPresent(), "only this resident takes the move");
            BlockPos target = move.spec().stances().cells().iterator().next().block(helper.getLevel());
            helper.assertTrue(!target.equals(cell) && plan.safeDestination(body, target), "the destination is outside the pending fill");
            body.setNoAi(false);
            helper.assertTrue(body.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D),
                "normal navigation can take the resident to the aside stance");
            helper.runAtTickTime(40, () -> {
                try {
                    helper.assertTrue(plan.clearOfFill(body), "walking actually clears the last pending cell");
                    helper.assertTrue(Commit.run(helper.getLevel(), move,
                        worker(body, new SimpleContainer())) instanceof Outcome.Done, "the walking task finishes");
                    helper.assertTrue(!refresh(plan, helper, Set.of(cell)).isEmpty(), "the final fill is now available");
                    helper.succeed();
                } finally {
                    plan.close();
                    body.discard();
                }
            });
        } catch (RuntimeException failure) {
            plan.close();
            body.discard();
            throw failure;
        }
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void unfinishedWorkKeepsItsEntranceEvenWithoutAnybodyInside(GameTestHelper helper) {
        floor(helper);
        tunnel(helper, 7, 15, false);
        BlockPos mouth = at(helper, 6, 1, 10);
        BlockPos deep = at(helper, 14, 1, 10);
        Set<BlockPos> cells = Set.of(mouth, deep);
        try (FillPlan plan = plan(helper, UUID.randomUUID(), cells)) {
            List<Job> jobs = refresh(plan, helper, cells);
            helper.assertTrue(jobs.stream().noneMatch(job -> job.cell().equals(mouth))
                    && jobs.stream().anyMatch(job -> job.cell().equals(deep)),
                "the empty inner room stays accessible until its distant work is done");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void aRaisedWorkFaceProvidesTheNextLayersStances(GameTestHelper helper) {
        floor(helper);
        Set<BlockPos> cells = box(at(helper, 9, 1, 9), at(helper, 12, 3, 12));
        Mob builder = resident(helper, at(helper, 8, 1, 8));
        UUID colony = UUID.randomUUID();
        ((io.github.izakyl.folkways.core.api.resident.body.Body) builder).joinColony(colony);
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 64));
        try (FillPlan plan = plan(helper, colony, cells)) {
            for (int attempt = 0; attempt < cells.size() * 2 && cells.stream().anyMatch(cell -> Survey.open(helper.getLevel().getBlockState(cell))); attempt++) {
                List<Job> jobs = refresh(plan, helper, cells);
                if (jobs.isEmpty() && !plan.steppingAside().isEmpty()) {
                    Node move = plan.steppingAside().getFirst().nodes().getFirst();
                    BlockPos target = move.spec().stances().cells().iterator().next().block(helper.getLevel());
                    builder.setPos(Vec3.atBottomCenterOf(target));
                    helper.assertTrue(Commit.run(helper.getLevel(), move, worker(builder, pack)) instanceof Outcome.Done,
                        "the builder can retreat from the raised face");
                    continue;
                }
                helper.assertTrue(!jobs.isEmpty(), "each raised layer has a safe next step at " + attempt
                    + ", body=" + builder.position() + ", left=" + cells.stream()
                        .filter(cell -> Survey.open(helper.getLevel().getBlockState(cell))).toList());
                Job job = jobs.getFirst();
                BlockPos stance = job.footings().cells().iterator().next().block(helper.getLevel());
                builder.setPos(Vec3.atBottomCenterOf(stance));
                helper.assertTrue(Commit.run(helper.getLevel(), node(plan, job), worker(builder, pack)) instanceof Outcome.Done,
                    "filling can move its escape route onto completed ground");
            }
            helper.assertTrue(cells.stream().allMatch(cell -> helper.getLevel().getBlockState(cell).is(Blocks.DIRT)),
                "no permanently reserved access column remains");
        } finally {
            builder.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void aBlockedLargePartDoesNotHideAnIndependentFill(GameTestHelper helper) {
        floor(helper);
        for (BlockPos cell : box(at(helper, 2, 1, 4), at(helper, 18, 3, 14))) {
            helper.getLevel().setBlock(cell, Blocks.STONE.defaultBlockState(), 3);
        }
        List<BlockPos> path = new ArrayList<>();
        for (int row = 0; row < 5; row++) {
            int z = 5 + row * 2;
            for (int step = 0; step < 15; step++) {
                path.add(at(helper, row % 2 == 0 ? 3 + step : 17 - step, 1, z));
            }
            if (row < 4) {
                path.add(at(helper, row % 2 == 0 ? 17 : 3, 1, z + 1));
            }
        }
        Set<BlockPos> cells = new LinkedHashSet<>(path);
        for (BlockPos cell : path) {
            helper.getLevel().setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
            helper.getLevel().setBlock(cell.above(), Blocks.AIR.defaultBlockState(), 3);
        }
        helper.getLevel().setBlock(at(helper, 2, 1, 5), Blocks.AIR.defaultBlockState(), 3);
        helper.getLevel().setBlock(at(helper, 2, 2, 5), Blocks.AIR.defaultBlockState(), 3);
        BlockPos free = at(helper, 20, 1, 19);
        cells.add(free);
        Mob bystander = resident(helper, path.getLast());
        try (FillPlan plan = plan(helper, UUID.randomUUID(), cells)) {
            plan.refresh(new Survey(helper.getLevel(), cells).fill(),
                cells.stream().map(Job::keyOf).collect(Collectors.toSet()), DIRT);
            for (int slice = 0; slice < 200_000 && !plan.offered(free); slice++) {
                Ground.Budget small = new Ground.Budget(31, 0);
                plan.advance(small);
                helper.assertTrue(small.used() <= 31, "an independent component cannot borrow a second trial budget");
            }
            List<Job> jobs = plan.jobs();
            helper.assertTrue(path.size() > 64 && jobs.stream().anyMatch(job -> job.cell().equals(free)),
                "more than a trial budget of blocked work cannot starve an independent part");
            helper.assertTrue(jobs.stream().noneMatch(job -> path.contains(job.cell())),
                "the bystander's only passage stays open");
        } finally {
            bystander.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void aRemovedPlanRefusesItsAlreadyClaimedWorkAndReturnsTheBlock(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 1, 11);
        Mob builder = resident(helper, at(helper, 10, 1, 10));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        FillPlan plan = plan(helper, UUID.randomUUID(), Set.of(cell));
        try {
            Node node = node(plan, refresh(plan, helper, Set.of(cell)).getFirst());
            node.planned(helper.getLevel());
            plan.close();
            helper.assertTrue(Commit.run(helper.getLevel(), node, worker(builder, pack)) instanceof Outcome.Failed,
                "a node kept by an old worker cannot place after its plan was removed");
            helper.assertTrue(pack.countItem(Items.DIRT) == 8 && helper.getLevel().getBlockState(cell).isAir(),
                "the stale task changes nothing and returns its block");
        } finally {
            plan.close();
            builder.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_budget", timeoutTicks = 2000)
    public static void tinySlicesKeepTheirFrontierAndEventuallyOfferWork(GameTestHelper helper) {
        floor(helper);
        Set<BlockPos> cells = Set.of(at(helper, 11, 1, 11));
        FillPlan plan = plan(helper, UUID.randomUUID(), cells);
        plan.refresh(new Survey(helper.getLevel(), cells).fill(),
            cells.stream().map(Job::keyOf).collect(Collectors.toSet()), DIRT);
        // In particular, a wrapper around the graph must not spend the only available unit forever.
        for (int i = 0; i < 64; i++) {
            Ground.Budget one = new Ground.Budget(1, 0);
            plan.advance(one);
            helper.assertTrue(one.used() <= 1, "one-unit slices cannot overspend");
        }
        helper.assertTrue(plan.jobs().isEmpty(), "a partial proof is never published as a safe route");
        int[] ticks = {0};
        helper.onEachTick(() -> {
            if (plan.stage().equals("complete")) {
                return;
            }
            Ground.Budget slice = new Ground.Budget(256, 0);
            if (ticks[0] % 10 == 0) {
                // Repeated remote creation/cancellation used to invalidate every watch in the level.
                try (FillPlan distant = plan(helper, UUID.randomUUID(), Set.of(at(helper, 1000, 1, 1000)))) {
                    distant.phase();
                }
            }
            plan.advance(slice);
            ticks[0]++;
            helper.assertTrue(slice.used() <= 256, "every later tick shares a real bounded unit count");
        });
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(plan.stage().equals("complete"), "the small slices finish the same proof");
            helper.assertTrue(!plan.jobs().isEmpty() && ticks[0] > 1, "work advances across actual server ticks");
        }).thenExecute(plan::close).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_budget", timeoutTicks = 2000)
    public static void interruptedProofsDiscardChangedTerrainAndConfiguration(GameTestHelper helper) {
        floor(helper);
        BlockPos old = at(helper, 9, 1, 9);
        BlockPos next = at(helper, 15, 1, 15);
        Set<BlockPos> cells = Set.of(old, next);
        FillPlan plan = plan(helper, UUID.randomUUID(), cells);
        plan.refresh(new Survey(helper.getLevel(), cells).fill(), Set.of(Job.keyOf(old), Job.keyOf(next)), DIRT);
        plan.advance(new Ground.Budget(64, 0));
        helper.getLevel().setBlock(old, Blocks.STONE.defaultBlockState(), 3);
        // A settings refresh must not resurrect the old candidate when its pending graph later completes.
        plan.refresh(new Survey(helper.getLevel(), cells).fill(), Set.of(Job.keyOf(next)), DIRT);
        helper.onEachTick(() -> {
            Ground.Budget slice = new Ground.Budget(256, 0);
            plan.advance(slice);
            helper.assertTrue(slice.used() <= 256 && !plan.offered(old), "stale intermediate results stay invalid");
        });
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(plan.stage().equals("complete") && plan.offered(next), "the updated target still makes progress");
            helper.assertTrue(plan.jobs().size() == 1, "only the current configuration is offered");
        }).thenExecute(plan::close).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_budget", timeoutTicks = 8000)
    public static void manyIdenticalResidentsAndLargeFillRemainBudgeted(GameTestHelper helper) {
        floor(helper);
        Set<BlockPos> cells = box(at(helper, 12, 1, 12), at(helper, 19, 1, 19));
        List<Mob> people = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            people.add(resident(helper, at(helper, 2 + i % 8, 1, 2 + i / 8)));
        }
        FillPlan plan = plan(helper, UUID.randomUUID(), cells);
        plan.refresh(new Survey(helper.getLevel(), cells).fill(), cells.stream().map(Job::keyOf).collect(Collectors.toSet()), DIRT);
        long[] observations = {0, 0, 0, 0};
        helper.onEachTick(() -> {
            if (plan.stage().equals("complete")) {
                return;
            }
            Ground.Budget slice = new Ground.Budget(4096, 0);
            long start = System.nanoTime();
            plan.advance(slice);
            long elapsed = System.nanoTime() - start;
            observations[0]++;
            observations[1] += slice.used();
            observations[2] += elapsed;
            observations[3] = Math.max(observations[3], elapsed);
            helper.assertTrue(slice.used() <= 4096, "48 bodies and 64 trial cells still use a single work allowance");
        });
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(plan.stage().equals("complete") && !plan.jobs().isEmpty(), "the full large round finishes");
        }).thenExecute(() -> {
            org.slf4j.LoggerFactory.getLogger(FillPlanGameTests.class).info(
                "FILL_BUDGET slices={} work={} totalNanos={} maxSliceNanos={} residents=48 cells=64",
                observations[0], observations[1], observations[2], observations[3]);
            plan.close();
            people.forEach(Mob::discard);
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_budget", timeoutTicks = 100)
    public static void cancellingAndRedrawingReleasesProofsAndSharedAllowances(GameTestHelper helper)
            throws ReflectiveOperationException {
        UUID colony = UUID.randomUUID();
        Set<BlockPos> cells = Set.of(at(helper, 11, 1, 11));
        var allowances = FillPlan.class.getDeclaredField("ALLOWANCES");
        allowances.setAccessible(true);
        for (int i = 0; i < 32; i++) {
            FillPlan plan = plan(helper, colony, cells);
            plan.refresh(List.of(), Set.of(Job.keyOf(cells.iterator().next())), DIRT);
            plan.advance(new Ground.Budget(8, 0));
            plan.close();
            plan.close();
            helper.assertTrue(!plan.advance() && plan.jobs().isEmpty() && plan.stage().equals("idle"),
                "cancelled work releases its continuation and cannot recreate an allowance");
            Map<?, ?> worlds = (Map<?, ?>) allowances.get(null);
            Map<?, ?> colonies = (Map<?, ?>) worlds.get(helper.getLevel());
            helper.assertTrue(colonies == null || !colonies.containsKey(colony),
                "the colony's last cancelled plan releases the shared tick account");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_retention", timeoutTicks = 500)
    public static void ordinarySweepsKeepAnOfferWhileItsWorkerIsStillOnTheWay(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 18, 1, 18);
        Set<BlockPos> cells = Set.of(cell);
        Mob builder = resident(helper, at(helper, 1, 1, 1));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        FillPlan plan = plan(helper, UUID.randomUUID(), cells);
        Job job = refresh(plan, helper, cells).getFirst();
        Node held = node(plan, job);
        held.planned(helper.getLevel());
        int[] ticks = {0};
        helper.onEachTick(() -> {
            if (++ticks[0] > 250) {
                return;
            }
            if (ticks[0] % 100 == 0) {
                plan.refresh(new Survey(helper.getLevel(), cells).fill(), Set.of(Job.keyOf(cell)), DIRT);
                helper.assertTrue(held.refusal(helper.getLevel()).isEmpty(),
                    "a normal sweep cannot revoke the valid node before its worker arrives");
            }
            // Replanning can need many more ticks than the next sweep. Every intermediate publication
            // must preserve the old proof too, not just the first call that starts this new round.
            plan.advance(new Ground.Budget(1, 0));
            helper.assertTrue(held.refusal(helper.getLevel()).isEmpty(), "a suspended round keeps the worker's offer");
        });
        helper.runAtTickTime(251, () -> {
            try {
                BlockPos feet = job.footings().cells().iterator().next().block(helper.getLevel());
                builder.setPos(Vec3.atBottomCenterOf(feet));
                helper.assertTrue(Commit.run(helper.getLevel(), held, worker(builder, pack)) instanceof Outcome.Done,
                    "the original claimed node still commits after multiple ordinary sweeps");
                helper.assertTrue(pack.countItem(Items.DIRT) == 7, "the delayed work spends exactly one block");
                helper.succeed();
            } finally {
                plan.close();
                builder.discard();
            }
        });
    }

    @GameTest(template = "empty_large", batch = "safe_fill_invalidation", timeoutTicks = 100)
    public static void changedInputsAndTerrainStillRevokeRetainedOffers(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 1, 11);
        Set<BlockPos> cells = Set.of(cell);
        try (FillPlan plan = plan(helper, UUID.randomUUID(), cells)) {
            Node old = node(plan, refresh(plan, helper, cells).getFirst());
            plan.refresh(new Survey(helper.getLevel(), cells).fill(), Set.of(), DIRT);
            helper.assertTrue(old.refusal(helper.getLevel()).isPresent(), "removing the target revokes the old offer");
            refresh(plan, helper, cells);
            plan.refresh(new Survey(helper.getLevel(), cells).fill(), Set.of(Job.keyOf(cell)), Blocks.STONE.defaultBlockState());
            helper.assertTrue(old.refusal(helper.getLevel()).isPresent(), "changing material revokes its old proof");
            refresh(plan, helper, cells);
            helper.getLevel().setBlock(cell.above(3), Blocks.STONE.defaultBlockState(), 3);
            plan.advance(new Ground.Budget(1, 0));
            helper.assertTrue(old.refusal(helper.getLevel()).isPresent(), "world changes revoke retained geometry before reuse");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_fallback", timeoutTicks = 100)
    public static void aDifferentMaterialRebuildsItsFallbackAfterTheExitWatchChanges(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 1, 11);
        Set<BlockPos> cells = Set.of(cell);
        AtomicReference<List<BlockPos>> anchors = new AtomicReference<>(List.of(at(helper, 1, 1, 1)));
        Mob builder = resident(helper, at(helper, 1, 1, 1));
        Mob bystander = null;
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        BlockState predicted = Blocks.STONE.defaultBlockState();
        try (FillPlan plan = new FillPlan(helper.getLevel(), UUID.randomUUID(), UUID.randomUUID(), cells,
                anchors::get, () -> {}, false, new FillPlan.Limits(Integer.MAX_VALUE, 0))) {
            plan.refresh(new Survey(helper.getLevel(), cells).fill(), Set.of(Job.keyOf(cell)), predicted);
            plan.advance(Ground.Budget.unlimited());
            Job first = plan.jobs().getFirst();
            // The broad material prediction is stone, while the worker actually carries dirt. Prove
            // that fallback, then fail only because this worker has not reached its work stance yet.
            helper.assertTrue(Commit.run(helper.getLevel(), node(plan, first), worker(builder, pack)) instanceof Outcome.Failed,
                "the first actual-material proof is cached without placing from beyond hand reach");
            bystander = resident(helper, cell);
            plan.refresh(new Survey(helper.getLevel(), cells).fill(), Set.of(Job.keyOf(cell)), predicted);
            plan.advance(Ground.Budget.unlimited());
            helper.assertTrue(plan.jobs().isEmpty(), "the new occupant withdraws this offered face");
            anchors.set(List.of(at(helper, 22, 1, 22)));
            bystander.discard();
            plan.refresh(new Survey(helper.getLevel(), cells).fill(), Set.of(Job.keyOf(cell)), predicted);
            plan.advance(Ground.Budget.unlimited());
            Job next = plan.jobs().getFirst();
            BlockPos feet = next.footings().cells().iterator().next().block(helper.getLevel());
            builder.setPos(Vec3.atBottomCenterOf(feet));
            helper.assertTrue(Commit.run(helper.getLevel(), node(plan, next), worker(builder, pack)) instanceof Outcome.Done,
                "the same actual material rebuilds an expired fallback after reframing to a different exterior");
            helper.assertTrue(pack.countItem(Items.DIRT) == 7 && helper.getLevel().getBlockState(cell).is(Blocks.DIRT),
                "the rejected attempt refunds its block and the rebuilt proof spends exactly one");
        } finally {
            builder.discard();
            if (bystander != null) {
                bystander.discard();
            }
        }
        helper.succeed();
    }

    private static FillPlan plan(GameTestHelper helper, UUID colony, Set<BlockPos> cells) {
        return new FillPlan(helper.getLevel(), UUID.randomUUID(), colony, cells,
            () -> List.of(at(helper, 1, 1, 1)), () -> {}, false,
            new FillPlan.Limits(Integer.MAX_VALUE, 0));
    }

    @GameTest(template = "empty_large", batch = "safe_fill_near_exit", timeoutTicks = 100)
    public static void aNearTrustedExitDoesNotEndTheAsideInsideItsSafetyBuffer(GameTestHelper helper) {
        floor(helper);
        UUID colony = UUID.randomUUID();
        BlockPos cell = at(helper, 11, 1, 11);
        Mob body = resident(helper, cell);
        ((io.github.izakyl.folkways.core.api.resident.body.Body) body).joinColony(colony);
        FillPlan plan = new FillPlan(helper.getLevel(), UUID.randomUUID(), colony, Set.of(cell),
            () -> List.of(at(helper, 12, 1, 11), at(helper, 13, 1, 11)), () -> {}, false,
            new FillPlan.Limits(Integer.MAX_VALUE, 0));
        try {
            helper.assertTrue(refresh(plan, helper, Set.of(cell)).isEmpty(), "the body still blocks the last fill");
            helper.assertTrue(plan.steppingAside().size() == 1,
                "a trusted exit one block away cannot swallow the resident's real move farther clear: " + retreatState(plan));
            Node move = plan.steppingAside().getFirst().nodes().getFirst();
            BlockPos target = move.spec().stances().cells().iterator().next().block(helper.getLevel());
            helper.assertTrue(plan.safeDestination(body, target), "the move really clears the full arrival buffer");
            body.setNoAi(false);
            helper.assertTrue(body.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D),
                "normal navigation can really walk beyond the near anchor");
            helper.runAtTickTime(40, () -> {
                try {
                    helper.assertTrue(plan.clearOfFill(body), "the real walk leaves the pending cube clear");
                    helper.assertTrue(Commit.run(helper.getLevel(), move,
                        worker(body, new SimpleContainer())) instanceof Outcome.Done, "the actual aside node completes");
                    Job job = refresh(plan, helper, Set.of(cell)).getFirst();
                    body.setPos(Vec3.atBottomCenterOf(job.footings().cells().iterator().next().block(helper.getLevel())));
                    SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 2));
                    helper.assertTrue(Commit.run(helper.getLevel(), node(plan, job), worker(body, pack)) instanceof Outcome.Done,
                        "the last fill really commits after the real retreat");
                    helper.assertTrue(pack.countItem(Items.DIRT) == 1 && helper.getLevel().getBlockState(cell).is(Blocks.DIRT),
                        "the retreat neither spends nor duplicates the final block");
                    helper.succeed();
                } finally {
                    plan.close();
                    body.discard();
                }
            });
        } catch (RuntimeException failure) {
            plan.close();
            body.discard();
            throw failure;
        }
    }

    @GameTest(template = "empty_large", batch = "safe_fill_near_exit_budget", timeoutTicks = 2000)
    public static void crowdedRetreatsResumeTheirFarExitSearchWithOneWorkUnit(GameTestHelper helper) {
        floor(helper);
        UUID colony = UUID.randomUUID();
        Set<BlockPos> cells = box(at(helper, 10, 1, 10), at(helper, 12, 1, 12));
        List<Mob> people = cells.stream().map(cell -> resident(helper, cell)).toList();
        people.forEach(body -> ((io.github.izakyl.folkways.core.api.resident.body.Body) body).joinColony(colony));
        FillPlan plan = new FillPlan(helper.getLevel(), UUID.randomUUID(), colony, cells,
            () -> List.of(at(helper, 13, 1, 11), at(helper, 14, 1, 11)), () -> {}, false,
            new FillPlan.Limits(Integer.MAX_VALUE, 0));
        plan.refresh(new Survey(helper.getLevel(), cells).fill(), cells.stream().map(Job::keyOf).collect(Collectors.toSet()), DIRT);
        long[] slices = {0};
        helper.onEachTick(() -> {
            for (int i = 0; i < 256 && !plan.stage().equals("complete"); i++) {
                Ground.Budget one = new Ground.Budget(1, 0);
                plan.advance(one);
                slices[0]++;
                helper.assertTrue(one.used() <= 1 && plan.jobs().isEmpty(), "one-unit retreats cannot place over any crowded body");
            }
        });
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(plan.stage().equals("complete"), "tiny slices eventually complete the farther retreat graph");
            helper.assertTrue(plan.steppingAside().size() == people.size() && slices[0] > 256,
                "every crowded resident gets a real move after multiple server ticks: " + retreatState(plan));
        }).thenExecute(() -> {
            List<Grown> moves = List.copyOf(plan.steppingAside());
            for (Grown grown : moves) {
                Node move = grown.nodes().getFirst();
                Mob body = people.stream().filter(person -> move.worker().filter(person.getUUID()::equals).isPresent()).findFirst().orElseThrow();
                BlockPos target = move.spec().stances().cells().iterator().next().block(helper.getLevel());
                helper.assertTrue(plan.safeDestination(body, target), "each size-aware retreat target clears the whole fill buffer");
                body.setPos(Vec3.atBottomCenterOf(target));
                helper.assertTrue(Commit.run(helper.getLevel(), move, worker(body, new SimpleContainer())) instanceof Outcome.Done,
                    "all residents can complete their retained aside nodes");
            }
            helper.assertTrue(!refresh(plan, helper, cells).isEmpty(), "the emptied work face becomes available again");
            plan.close();
            people.forEach(Mob::discard);
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_near_exit_closed", timeoutTicks = 100)
    public static void anAsideWaitsIfEveryFartherTrustedExitIsBehindAWall(GameTestHelper helper) {
        floor(helper);
        for (int x = 10; x <= 13; x++) {
            for (int z = 10; z <= 12; z++) {
                for (int y = 1; y <= 3; y++) {
                    if (y == 3 || x == 10 || x == 13 || z != 11) {
                        helper.getLevel().setBlock(at(helper, x, y, z), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
        }
        UUID colony = UUID.randomUUID();
        BlockPos cell = at(helper, 11, 1, 11);
        Mob body = resident(helper, cell);
        ((io.github.izakyl.folkways.core.api.resident.body.Body) body).joinColony(colony);
        try (FillPlan plan = new FillPlan(helper.getLevel(), UUID.randomUUID(), colony, Set.of(cell),
                () -> List.of(at(helper, 12, 1, 11), at(helper, 14, 1, 11)), () -> {}, false,
                new FillPlan.Limits(Integer.MAX_VALUE, 0))) {
            helper.assertTrue(refresh(plan, helper, Set.of(cell)).isEmpty() && plan.steppingAside().isEmpty(),
                "an anchor behind the buffer does not invent a route through the surrounding wall");
            helper.assertTrue(helper.getLevel().getBlockState(cell).isAir(), "the trapped resident is never filled over");
            helper.getLevel().setBlock(at(helper, 13, 1, 11), Blocks.AIR.defaultBlockState(), 3);
            helper.getLevel().setBlock(at(helper, 13, 2, 11), Blocks.AIR.defaultBlockState(), 3);
            refresh(plan, helper, Set.of(cell));
            helper.assertTrue(plan.steppingAside().size() == 1,
                "opening a real onward passage invalidates the old wait and supplies a safe retreat: " + retreatState(plan));
        } finally {
            body.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_near_exit_resizing", timeoutTicks = 100)
    public static void aBodyGrowingDuringARetreatProofRechecksTheWholeBuffer(GameTestHelper helper)
            throws ReflectiveOperationException {
        floor(helper);
        UUID colony = UUID.randomUUID();
        BlockPos cell = at(helper, 11, 1, 11);
        Mob body = resident(helper, cell);
        ((io.github.izakyl.folkways.core.api.resident.body.Body) body).joinColony(colony);
        FillPlan plan = new FillPlan(helper.getLevel(), UUID.randomUUID(), colony, Set.of(cell),
            () -> List.of(at(helper, 12, 1, 11), at(helper, 13, 1, 11), at(helper, 15, 1, 11)), () -> {}, false,
            new FillPlan.Limits(Integer.MAX_VALUE, 0));
        try {
            plan.refresh(new Survey(helper.getLevel(), Set.of(cell)).fill(), Set.of(Job.keyOf(cell)), DIRT);
            boolean suspended = false;
            for (int i = 0; i < 2000 && !suspended; i++) {
                plan.advance(new Ground.Budget(64, 0));
                Object planning = reflected(plan, "planning");
                Object aside = reflected(planning, "aside");
                suspended = aside != null && Boolean.TRUE.equals(optionalReflected(aside, "rerouting"));
            }
            helper.assertTrue(suspended, "the exit filtering/search can remain suspended between slices: " + retreatState(plan));
            helper.runAtTickTime(1, () -> {
                try {
                    body.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE).setBaseValue(4.0D);
                    body.refreshDimensions();
                    helper.assertTrue(body.getBbWidth() > 2.0F, "the same resident now needs a materially wider safe buffer");
                    plan.advance(Ground.Budget.unlimited());
                    helper.assertTrue(plan.stage().equals("complete") && plan.steppingAside().size() == 1,
                        "the resized body gets a newly proved retreat rather than losing progress forever");
                    Node move = plan.steppingAside().getFirst().nodes().getFirst();
                    BlockPos target = move.spec().stances().cells().iterator().next().block(helper.getLevel());
                    helper.assertTrue(plan.safeDestination(body, target), "the old small-body target is never published for the larger body");
                    helper.succeed();
                } finally {
                    plan.close();
                    body.discard();
                }
            });
        } catch (ReflectiveOperationException | RuntimeException failure) {
            plan.close();
            body.discard();
            throw failure;
        }
    }

    private static Object reflected(Object object, String name) throws ReflectiveOperationException {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static Object optionalReflected(Object object, String name) throws ReflectiveOperationException {
        try {
            return reflected(object, name);
        } catch (NoSuchFieldException absent) {
            return "absent";
        }
    }

    private static String retreatState(FillPlan plan) {
        try {
            Object context = reflected(plan, "context");
            Object aside = reflected(reflected(plan, "planning"), "aside");
            var state = new java.util.LinkedHashMap<String, Object>();
            state.put("exits", reflected(context, "exits"));
            state.put("people", reflected(context, "people"));
            if (aside != null) {
                for (String key : List.of("person", "way", "last", "cursor", "rerouting", "rerouted", "exit", "safeExits")) {
                    state.put(key, optionalReflected(aside, key));
                }
            }
            return state.toString();
        } catch (ReflectiveOperationException failure) {
            return failure.toString();
        }
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void aBodyOverhangingTheLastCellAlsoStepsAside(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 1, 11);
        UUID colony = UUID.randomUUID();
        Mob builder = resident(helper, at(helper, 10, 1, 11));
        builder.setPos(cell.getX() - 0.15D, cell.getY(), cell.getZ() + 0.5D);
        ((io.github.izakyl.folkways.core.api.resident.body.Body) builder).joinColony(colony);
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (FillPlan plan = plan(helper, colony, Set.of(cell))) {
            List<Job> waiting = refresh(plan, helper, Set.of(cell));
            helper.assertTrue(!builder.blockPosition().equals(cell) && !plan.clearOfFill(builder),
                "the feet are outside the target but the body crosses its edge");
            helper.assertTrue(waiting.isEmpty(), "the overlapping body blocks the last cell");
            helper.assertTrue(plan.steppingAside().size() == 1,
                "edge overlap also asks the resident to walk clear");
            Node move = plan.steppingAside().getFirst().nodes().getFirst();
            BlockPos target = move.spec().stances().cells().iterator().next().block(helper.getLevel());
            builder.setPos(Vec3.atBottomCenterOf(target));
            helper.assertTrue(Commit.run(helper.getLevel(), move, worker(builder, pack)) instanceof Outcome.Done,
                "walking clear completes the aside task");
            Job job = refresh(plan, helper, Set.of(cell)).getFirst();
            BlockPos stance = job.footings().cells().iterator().next().block(helper.getLevel());
            builder.setPos(Vec3.atBottomCenterOf(stance));
            helper.assertTrue(Commit.run(helper.getLevel(), node(plan, job), worker(builder, pack)) instanceof Outcome.Done,
                "the last cell is filled after the body has cleared it");
        } finally {
            builder.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void arrivingAtTheEdgeOfAStanceStillLeavesTheBlockInReach(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 1, 11);
        Mob builder = resident(helper, at(helper, 9, 1, 9));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (FillPlan plan = plan(helper, UUID.randomUUID(), Set.of(cell))) {
            Job job = refresh(plan, helper, Set.of(cell)).getFirst();
            BlockPos feet = job.footings().cells().stream().map(at -> at.block(helper.getLevel()))
                .max(Comparator.comparingDouble(at -> at.distSqr(cell))).orElseThrow();
            Vec3 centered = Vec3.atBottomCenterOf(feet);
            Vec3 away = centered.subtract(Vec3.atBottomCenterOf(cell)).multiply(1, 0, 1).normalize().scale(0.85D);
            builder.setPos(centered.add(away));
            helper.assertTrue(Commit.run(helper.getLevel(), node(plan, job), worker(builder, pack)) instanceof Outcome.Done,
                "the navigation arrival tolerance cannot strand a task just beyond actual hand reach");
            helper.assertTrue(helper.getLevel().getBlockState(cell).is(Blocks.DIRT), "the work actually places its block");
        } finally {
            builder.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void arrivingAtAPitsLipStillUsesTheValidWorkStance(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 0, 11);
        helper.getLevel().setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        Mob builder = resident(helper, at(helper, 9, 1, 9));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (FillPlan plan = plan(helper, UUID.randomUUID(), Set.of(cell))) {
            Job job = refresh(plan, helper, Set.of(cell)).getFirst();
            BlockPos feet = job.footings().cells().stream().map(at -> at.block(helper.getLevel()))
                .max(Comparator.comparingDouble(at -> at.distSqr(cell))).orElseThrow();
            Vec3 centered = Vec3.atBottomCenterOf(feet);
            Vec3 away = centered.subtract(Vec3.atBottomCenterOf(cell)).multiply(1, 0, 1).normalize().scale(0.85D);
            builder.setPos(centered.add(away));
            helper.assertTrue(Commit.run(helper.getLevel(), node(plan, job), worker(builder, pack)) instanceof Outcome.Done,
                "stopping within the valid work stance still fills the pit without refusing the same task forever");
            helper.assertTrue(helper.getLevel().getBlockState(cell).is(Blocks.DIRT), "the lower cell really is filled");
        } finally {
            builder.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void anotherActiveFillCannotSupplyATrustedExit(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 1, 11);
        FillPlan neighbouring = plan(helper, UUID.randomUUID(), Set.of(at(helper, 1, 1, 1)));
        try (FillPlan own = plan(helper, UUID.randomUUID(), Set.of(cell))) {
            helper.assertTrue(refresh(own, helper, Set.of(cell)).isEmpty(),
                "a neighbouring colony's pending fill cannot be treated as a permanent exit");
            neighbouring.close();
            helper.assertTrue(!refresh(own, helper, Set.of(cell)).isEmpty(),
                "removing that fill releases the outside stance for a fresh proof");
        } finally {
            neighbouring.close();
        }
        helper.succeed();
    }

    private static List<Job> refresh(FillPlan plan, GameTestHelper helper, Set<BlockPos> cells) {
        plan.refresh(new Survey(helper.getLevel(), cells).fill(),
            cells.stream().map(Job::keyOf).collect(Collectors.toSet()), DIRT);
        plan.advance(Ground.Budget.unlimited());
        helper.assertTrue(plan.stage().equals("complete"), "the explicit unbounded fixture drains one planning round");
        return plan.jobs();
    }

    private static Node node(FillPlan plan, Job job) {
        Chores chores = new Chores(UUID.randomUUID(), "fill", (level, chore) -> new FillNode(chore, MATERIAL, plan),
            () -> {}, new PastDay());
        return new FillNode(new Chores.Chore(UUID.randomUUID(), job, chores), MATERIAL, plan);
    }

    private static Worker worker(Mob body, SimpleContainer pack) {
        return (Worker) Proxy.newProxyInstance(FillPlanGameTests.class.getClassLoader(), new Class<?>[] {Worker.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "body" -> body;
                case "pack" -> pack;
                case "resident" -> new Resident(body.getUUID(), PersonBody.ID);
                case "effects" -> Effects.NONE;
                case "placement" -> Placement.NOWHERE;
                case "held" -> ItemStack.EMPTY;
                case "within", "route", "supplied" -> List.of();
                default -> null;
            });
    }

    private static Mob resident(GameTestHelper helper, BlockPos feet) {
        Mob body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.setPos(Vec3.atBottomCenterOf(feet));
        body.setOnGround(true);
        io.github.izakyl.folkways.plugins.person.walk.Walking.INSTANCE.fit(body);
        body.setNoAi(true);
        helper.getLevel().addFreshEntity(body);
        return body;
    }

    private static Set<BlockPos> box(BlockPos min, BlockPos max) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        BlockPos.betweenClosed(min, max).forEach(cell -> cells.add(cell.immutable()));
        return cells;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 23; x++) {
            for (int z = 0; z <= 23; z++) {
                for (int y = 0; y <= 5; y++) {
                    helper.getLevel().setBlock(at(helper, x, y, z),
                        y == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static void tunnel(GameTestHelper helper, int from, int to, boolean bothEnds) {
        ServerLevel level = helper.getLevel();
        for (int x = from - 1; x <= to + 1; x++) {
            for (int z = 9; z <= 11; z++) {
                for (int y = 1; y <= 3; y++) {
                    if (y == 3 || z != 10 || x == to + 1 && !bothEnds) {
                        level.setBlock(at(helper, x, y, z), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }
}
