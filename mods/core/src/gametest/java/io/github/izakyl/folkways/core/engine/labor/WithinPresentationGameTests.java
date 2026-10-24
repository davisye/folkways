package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Shown;
import io.github.izakyl.folkways.core.engine.plan.Vertex;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WithinPresentationGameTests {

    private static final int LABOR_TICKS = 20;
    private static final int PIECES = 3;
    private static final ResourceLocation TEST = ResourceLocation.fromNamespaceAndPath("folkways_test", "presentation");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(WithinPresentationGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void presentationMovesThroughoutInnerLaborWithoutChangingItsCadence(GameTestHelper helper)
            throws ReflectiveOperationException {
        Shift baseline = run(helper, false, false);
        Shift dense = run(helper, true, false);
        Shift dispersed = run(helper, true, true);
        helper.assertValueEqual(dense.commits, baseline.commits,
            "moving during inner labor preserves the same commit times as no presentation");
        helper.assertValueEqual(dispersed.commits, baseline.commits,
            "distant work targets preserve the same commit times as dense targets");
        for (int at = 1; at < PIECES; at++) {
            helper.assertValueEqual(dispersed.commits.get(at) - dispersed.commits.get(at - 1), LABOR_TICKS + 2,
                "each piece has the same wind-up plus the fixed inner dispatch/commit boundaries");
        }
        helper.assertTrue(dense.callbacksDuringLabor >= PIECES * LABOR_TICKS
                && dispersed.callbacksDuringLabor >= PIECES * LABOR_TICKS,
            "the presentation callback keeps running throughout every inner wind-up");
        helper.assertTrue(dense.movedDuringLabor && dispersed.movedDuringLabor,
            "presentation can move the resident while its inner work advances");
        helper.assertValueEqual(dense.endings, PIECES, "each inner piece still reports exactly one ending");
        helper.assertValueEqual(dispersed.endings, PIECES, "distance does not change inner lifecycle reporting");
        helper.succeed();
    }

    private static Shift run(GameTestHelper helper, boolean present, boolean dispersed)
            throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        Shift shift = new Shift(level, origin, present, dispersed);
        ColonyLabor labor = new ColonyLabor(new ColonyData(), level.dimension());
        PlanningFixture.apply(labor, level, List.of(), Shown.delta(
            Map.of(shift.id(), new Vertex(shift.id(), shift, Placement.NOWHERE)), List.of(), List.of()));
        var body = PersonBody.RESIDENT.get().create(level);
        body.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
        BodyRunner runner = new BodyRunner(body.getUUID());
        runner.follow(List.of(shift.id()));
        try {
            for (int tick = 0; tick < 100 && shift.commits.size() < PIECES; tick++) {
                shift.clock = tick;
                runner.tick(labor, level, body);
            }
            helper.assertValueEqual(shift.commits.size(), PIECES, "all inner work completed within the expected budget");
            return shift;
        } finally {
            labor.close();
            body.discard();
        }
    }

    private static final class Shift implements Node, Workload.Within {
        private final NodeSpec spec;
        private final List<Node> pieces = new ArrayList<>();
        private final List<Integer> commits = new ArrayList<>();
        private final boolean present;
        private int issued;
        private int clock;
        private int callbacksDuringLabor;
        private int endings;
        private boolean laboring;
        private boolean movedDuringLabor;

        private Shift(ServerLevel level, BlockPos origin, boolean present, boolean dispersed) {
            this.present = present;
            spec = NodeSpec.of(UUID.randomUUID(), TEST, WorkSite.at(level, origin), Stances.WHEREVER, this).done();
            for (int index = 0; index < PIECES; index++) {
                BlockPos focus = origin.offset(dispersed ? 64 * (index + 1) : index, dispersed ? index * 8 : 0, 0);
                NodeSpec inner = NodeSpec.of(UUID.randomUUID(), TEST, WorkSite.at(level, focus), Stances.WHEREVER,
                    Workload.Once.of(LABOR_TICKS)).done();
                pieces.add(new Node() {
                    public NodeSpec spec() { return inner; }
                    public Outcome commit(ServerLevel at, Worker who) {
                        commits.add(clock);
                        laboring = false;
                        return Outcome.done();
                    }
                });
            }
        }

        public NodeSpec spec() { return spec; }

        public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }

        public Turn next(ServerLevel level, Worker who) {
            if (issued == pieces.size()) {
                return LEAVE;
            }
            laboring = true;
            return new Turn.Piece(pieces.get(issued++));
        }

        public void tick(ServerLevel level, Worker who) {
            if (present && laboring) {
                callbacksDuringLabor++;
                double before = who.body().getX();
                who.body().setPos(before + 0.05D, who.body().getY(), who.body().getZ());
                movedDuringLabor |= who.body().getX() != before;
            }
        }

        public void ended(ServerLevel level, Node piece, Ending how) {
            endings++;
        }
    }
}
