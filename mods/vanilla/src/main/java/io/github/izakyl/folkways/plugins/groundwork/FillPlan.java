package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.ground.Ground;
import io.github.izakyl.folkways.core.api.ground.Grounds;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// A fill is a changing work face, with a way back out of it. Offers are only a prediction: the real body and
// material are checked against the live ground again immediately before each block is laid.
final class FillPlan implements AutoCloseable {

    enum Phase {
        FILLING, RETREATING, CLOSING, WAITING;
    }

    private static final int MARGIN = 8;
    private static final int BAND = 2;
    private static final int MOST_TRIALS = 64;
    private static final int MOST_EXIT_DISTANCE = 64;
    private static final int MOST_STANCES = 16;
    // Navigation accepts a stance within 0.9 horizontally and one block vertically. Leave that whole margin
    // inside hand reach, so arriving at a valid stance never creates an endlessly refused edge-of-reach job.
    private static final double WORK_REACH = Reach.PLAYER_BLOCK_REACH - Math.hypot(0.9D, 1.0D);

    // Every loaded colony in this level shares the same exclusion of future fill space from trusted exits.
    private static final Map<ServerLevel, Set<FillPlan>> LIVE = new WeakHashMap<>();

    private record Ranked(Job job, int part, int depth, boolean passage) {
    }

    private record View(Ground ground, Ground.Access access) {
    }

    private final ServerLevel level;
    private final UUID zone;
    private final UUID colony;
    private final Set<BlockPos> cells;
    private final Realm realm;
    private final BlockPos origin;
    private final BoundingBox workBox;
    private final Supplier<Collection<BlockPos>> external;
    private BoundingBox bounds;
    private AABB worldBounds;
    private Grounds.Watch watch;
    private final Mob surveyor;
    private final Runnable changed;
    private Set<BlockPos> pending = Set.of();
    private List<Job> remaining = List.of();
    private final Map<Integer, Integer> cursors = new HashMap<>();
    private volatile Map<BlockPos, Job> offered = Map.of();
    private volatile List<Grown> steppingAside = List.of();
    private volatile Phase phase = Phase.WAITING;
    private volatile boolean closed;

    FillPlan(ServerLevel level, UUID zone, UUID colony, Set<BlockPos> cells,
            Supplier<Collection<BlockPos>> external, Runnable changed) {
        this.level = level;
        this.zone = zone;
        this.colony = colony;
        this.cells = Set.copyOf(cells);
        this.changed = changed;
        this.external = external;
        BlockPos first = cells.iterator().next();
        realm = WorldPos.of(level, first).realm();
        origin = new WorldPos(realm, BlockPos.ZERO).block(level);
        workBox = BoundingBox.encapsulatingPositions(cells).orElseThrow();
        reframe(List.of());
        surveyor = PersonBody.RESIDENT.get().create(level);
        surveyor.setPos(Vec3.atBottomCenterOf(first));
        LIVE.computeIfAbsent(level, any -> new HashSet<>()).add(this);
    }

    Phase phase() {
        return phase;
    }

    boolean waiting() {
        return phase == Phase.WAITING && !pending.isEmpty();
    }

    List<Grown> steppingAside() {
        return steppingAside;
    }

    List<Job> refresh(List<Job> jobs, Set<String> wanted, BlockState material) {
        if (closed) {
            return List.of();
        }
        Set<BlockPos> left = new LinkedHashSet<>();
        cells.stream().filter(cell -> wanted.contains(Job.keyOf(cell)) && Survey.open(level.getBlockState(cell)))
            .sorted(Comparator.comparingLong(BlockPos::asLong)).forEach(left::add);
        pending = Set.copyOf(left);
        remaining = jobs.stream().filter(job -> pending.contains(job.cell())).toList();
        Context context = new Context();
        View view = context.view(surveyor);
        Map<BlockPos, Integer> parts = parts();
        Set<BlockPos> passage = new HashSet<>();
        for (LivingEntity body : context.people) {
            View bodyView = context.view(body);
            footing(body, bodyView.ground()).ifPresent(feet -> protect(passage, bodyView.access().path(feet)));
        }
        List<Ranked> ranks = new ArrayList<>();
        for (Job job : remaining) {
            List<BlockPos> stands = context.workStances(view.access(), job.cell());
            if (stands.isEmpty()) {
                continue;
            }
            BlockPos entry = stands.stream().min(Comparator.comparingInt(view.access()::distance)).orElseThrow();
            protect(passage, view.access().path(entry));
            ranks.add(new Ranked(job, parts.get(job.cell()), view.access().distance(entry) / BAND, false));
        }
        ranks = ranks.stream().map(rank -> new Ranked(rank.job(), rank.part(), rank.depth(),
                passage.contains(rank.job().cell())))
            .sorted(Comparator.comparingInt((Ranked rank) -> rank.job().cell().getY())
                .thenComparing(Comparator.comparingInt(Ranked::depth).reversed())
                .thenComparingLong(rank -> rank.job().cell().asLong())).toList();
        Map<Integer, List<Ranked>> candidates = new LinkedHashMap<>();
        for (Ranked rank : ranks) {
            candidates.computeIfAbsent(rank.part(), any -> new ArrayList<>()).add(rank);
        }
        Map<Ranked, Job> safe = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Ranked>> entry : candidates.entrySet()) {
            List<Ranked> part = entry.getValue();
            int start = Math.floorMod(cursors.getOrDefault(entry.getKey(), 0), part.size());
            boolean found = false;
            for (int tried = 0; tried < Math.min(MOST_TRIALS, part.size()); tried++) {
                Ranked rank = part.get((start + tried) % part.size());
                List<BlockPos> stands = safeStances(context, rank.job().cell(), material, true);
                if (!stands.isEmpty()) {
                    Job job = rank.job();
                    safe.put(rank, new Job(job.key(), job.site(), job.cell(), job.cells(), job.canopy(),
                        Stances.of(level, stands).orElseThrow()));
                    found = true;
                }
            }
            // A crowded or unreachable first band must not permanently hide later safe work, nor consume the
            // trial allowance of an independent part. A changed world is always checked again before commit.
            cursors.put(entry.getKey(), found ? 0 : (start + MOST_TRIALS) % part.size());
        }
        Map<Integer, List<Ranked>> byPart = new LinkedHashMap<>();
        safe.keySet().forEach(rank -> byPart.computeIfAbsent(rank.part(), any -> new ArrayList<>()).add(rank));
        Map<BlockPos, Job> next = new LinkedHashMap<>();
        boolean retreat = false;
        for (List<Ranked> part : byPart.values()) {
            List<Ranked> ordinary = part.stream().filter(rank -> !rank.passage()).toList();
            List<Ranked> front = ordinary.isEmpty() ? part : ordinary;
            retreat |= ordinary.isEmpty();
            int layer = front.stream().mapToInt(rank -> rank.job().cell().getY()).min().orElseThrow();
            int depth = front.stream().filter(rank -> rank.job().cell().getY() == layer)
                .mapToInt(Ranked::depth).max().orElseThrow();
            front.stream().filter(rank -> rank.job().cell().getY() == layer && rank.depth() == depth)
                .forEach(rank -> next.put(rank.job().cell(), safe.get(rank)));
        }
        offered = Map.copyOf(next);
        Set<Integer> activeParts = new HashSet<>();
        safe.keySet().stream().filter(rank -> next.containsKey(rank.job().cell())).forEach(rank -> activeParts.add(rank.part()));
        steppingAside = pending.isEmpty() ? List.of() : stepAside(context, parts, activeParts);
        phase = next.isEmpty() ? Phase.WAITING : retreat ? Phase.RETREATING : Phase.FILLING;
        if (!next.isEmpty() && next.values().stream().allMatch(job -> job.footings().cells().stream()
                .noneMatch(at -> cells.contains(at.block(level))))) {
            phase = Phase.CLOSING;
        }
        return List.copyOf(next.values());
    }

    boolean offered(BlockPos cell) {
        return !closed && offered.containsKey(cell);
    }

    Optional<GroundRefusal> permits(BlockPos cell, BlockState material, Mob worker) {
        if (!offered(cell)) {
            return Optional.of(GroundRefusal.FILL_ACCESS_CHANGED);
        }
        Context context = new Context();
        if (!context.people.contains(worker)) {
            context.people.add(worker);
        }
        List<BlockPos> stands = safeStances(context, cell, material, false);
        Vec3 actual = position(worker);
        View own = context.view(worker);
        BlockPos feet = footing(worker, own.ground()).orElse(null);
        // Work uses the same declared-stance contract as navigation. At the lip of a pit the centre of the
        // lower cell can be hidden from the body's rounded block position while its valid work stance sees it.
        if (stands.isEmpty() || feet == null || Reach.stanceHolding(actual, Set.copyOf(stands)).isEmpty()
                || actual.add(0.0D, worker.getEyeHeight(), 0.0D).distanceToSqr(Vec3.atCenterOf(cell))
                    > Reach.PLAYER_BLOCK_REACH * Reach.PLAYER_BLOCK_REACH) {
            changed.run();
            return Optional.of(GroundRefusal.FILL_ACCESS_BLOCKED);
        }
        // Use this body's own route, including its dimensions and locomotion; the ordinary surveyor only
        // chooses work to offer. A future promised block is never part of the trial ground.
        Ground trial = own.ground().with(Map.of(cell, material));
        if (trial.accessFrom(context.exits).distance(feet) < 0) {
            return Optional.of(GroundRefusal.FILL_ACCESS_BLOCKED);
        }
        return Optional.empty();
    }

    void committed(BlockPos cell) {
        cursors.clear();
        Set<BlockPos> left = new HashSet<>(pending);
        left.remove(cell);
        pending = Set.copyOf(left);
        Map<BlockPos, Job> next = new LinkedHashMap<>(offered);
        next.remove(cell);
        offered = Map.copyOf(next);
        changed.run();
    }

    boolean safeDestination(LivingEntity body, BlockPos target) {
        return !closed && clearAsideStance(body, target)
            && new Context().view(body).access().distance(target) >= 0;
    }

    boolean clearOfFill(LivingEntity body) {
        Vec3 here = position(body);
        return missesPending(body.getBoundingBox().move(here.subtract(body.position())));
    }

    void movedAside() {
        changed.run();
    }

    private List<BlockPos> safeStances(Context context, BlockPos cell, BlockState material, boolean planning) {
        if (!Survey.open(level.getBlockState(cell)) || !material.canSurvive(level, cell)
                || material.getBlock() instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(cell.below()))) {
            return List.of();
        }
        var shape = material.getCollisionShape(level, cell).move(cell.getX(), cell.getY(), cell.getZ());
        for (LivingEntity body : context.people) {
            Vec3 here = position(body);
            AABB box = body.getBoundingBox().move(here.subtract(body.position()));
            if (shape.toAabbs().stream().anyMatch(box::intersects)) {
                return List.of();
            }
        }
        View before = context.view(surveyor);
        Ground after = before.ground().with(Map.of(cell, material));
        Ground.Access access = after.accessFrom(context.exits);
        List<BlockPos> approaches = planning ? context.workStances(before.access(), cell)
            : before.access().stancesFor(cell);
        List<BlockPos> stands = approaches.stream()
            .filter(feet -> access.distance(feet) >= 0).toList();
        if (stands.isEmpty()) {
            return List.of();
        }
        for (LivingEntity body : context.people) {
            View own = context.view(body);
            Optional<BlockPos> feet = footing(body, own.ground());
            if (feet.isEmpty()) {
                continue;
            }
            if (own.access().distance(feet.get()) >= 0) {
                if (affectsRoute(cell, body, own.access().path(feet.get()))
                        && own.ground().with(Map.of(cell, material)).accessFrom(context.exits).distance(feet.get()) < 0) {
                    return List.of();
                }
            } else {
                // A pre-existing trapped neighbour must not freeze an unrelated room, but filling in its
                // own connected space must wait for a way out, even when the new block misses its body.
                Ground.Access room = own.ground().accessFrom(List.of(feet.get()));
                if (room.distance(cell) >= 0 || room.distance(cell.below()) >= 0
                        || room.distance(cell.above()) >= 0
                        || Direction.Plane.HORIZONTAL.stream().anyMatch(side -> room.distance(cell.relative(side)) >= 0)) {
                    return List.of();
                }
            }
        }
        // Keep entrances to the rest of the loaded world connected too. This protects people beyond the
        // watch boundary without pretending that reaching an arbitrary boundary is itself an escape.
        for (BlockPos port : context.ports(before)) {
            if (access.distance(port) < 0) {
                return List.of();
            }
        }
        for (Job job : remaining) {
            if (!job.cell().equals(cell) && pending.contains(job.cell()) && Survey.open(level.getBlockState(job.cell()))) {
                List<BlockPos> previous = context.workStances(before.access(), job.cell());
                if (!previous.isEmpty() && previous.stream().noneMatch(feet -> access.canWorkFrom(feet, job.cell()))
                        && context.workStances(access, job.cell()).isEmpty()) {
                    return List.of();
                }
            }
        }
        return stands;
    }

    private static boolean affectsRoute(BlockPos cell, LivingEntity body, List<BlockPos> route) {
        int across = (int) Math.ceil(body.getBbWidth()) + 2;
        int above = (int) Math.ceil(body.getBbHeight()) + 4;
        // Include the neighbouring blocks read by walking, jumping, support and door checks, not just the
        // body's occupied cells. A route beyond this envelope remains an unchanged proof of escape.
        return route.stream().anyMatch(feet -> Math.abs(feet.getX() - cell.getX()) <= across
            && Math.abs(feet.getZ() - cell.getZ()) <= across
            && cell.getY() >= feet.getY() - 5 && cell.getY() <= feet.getY() + above);
    }

    private List<Grown> stepAside(Context context, Map<BlockPos, Integer> parts, Set<Integer> activeParts) {
        List<Grown> moves = new ArrayList<>();
        for (LivingEntity body : context.people) {
            if (!(body instanceof Mob mob) || Bodies.of(body).flatMap(held -> held.colonyId())
                    .filter(colony::equals).isEmpty()) {
                continue;
            }
            View view = context.view(body);
            Optional<BlockPos> from = footing(body, view.ground());
            if (from.isEmpty()) {
                continue;
            }
            List<BlockPos> way = view.access().path(from.get());
            if (way.isEmpty() || clearOfFill(body) && way.stream().noneMatch(this::occupiesPending)) {
                continue;
            }
            Integer part = parts.get(from.get());
            if (part == null) {
                part = parts.get(from.get().above());
            }
            if (part == null) {
                part = parts.get(from.get().below());
            }
            if (part == null) {
                for (BlockPos feet : way) {
                    part = parts.get(feet);
                    if (part == null) {
                        part = parts.get(feet.above());
                    }
                    if (part == null) {
                        part = parts.get(feet.below());
                    }
                    if (part != null) {
                        break;
                    }
                }
            }
            if (part != null && activeParts.contains(part)) {
                continue;
            }
            int last = -1;
            for (int i = 0; i < way.size(); i++) {
                if (!clearAsideStance(body, way.get(i))) {
                    last = i;
                }
            }
            // Retire beyond the last part of the route that filling still needs. Merely standing on a finished
            // patch can leave the resident depending on the very passage that is waiting to close.
            if (last >= 0 && last + 1 < way.size()) {
                moves.add(Grown.of(new StepAsideNode(this, zone, mob, way.get(last + 1), level)));
            }
        }
        return List.copyOf(moves);
    }

    private boolean occupiesPending(BlockPos feet) {
        return pending.contains(feet) || pending.contains(feet.above()) || pending.contains(feet.below());
    }

    private boolean clearAsideStance(LivingEntity body, BlockPos feet) {
        double half = body.getBbWidth() / 2.0D + 0.9D;
        return missesPending(new AABB(feet.getX() + 0.5D - half, feet.getY() - 1.0D,
            feet.getZ() + 0.5D - half, feet.getX() + 0.5D + half,
            feet.getY() + body.getBbHeight() + 1.0D, feet.getZ() + 0.5D + half));
    }

    private boolean missesPending(AABB box) {
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (pending.contains(cell) && box.intersects(new AABB(cell))) {
                return false;
            }
        }
        return true;
    }

    private Map<BlockPos, Integer> parts() {
        Map<BlockPos, Integer> found = new HashMap<>();
        int index = 0;
        for (BlockPos cell : pending.stream().sorted(Comparator.comparingLong(BlockPos::asLong)).toList()) {
            if (found.containsKey(cell)) {
                continue;
            }
            int part = index++;
            ArrayDeque<BlockPos> open = new ArrayDeque<>();
            open.add(cell);
            found.put(cell, part);
            while (!open.isEmpty()) {
                BlockPos at = open.removeFirst();
                for (Direction side : Direction.values()) {
                    BlockPos next = at.relative(side);
                    if (pending.contains(next) && found.putIfAbsent(next, part) == null) {
                        open.addLast(next);
                    }
                }
            }
        }
        return found;
    }

    private Optional<BlockPos> footing(LivingEntity body, Ground ground) {
        Vec3 actual = position(body);
        BlockPos at = BlockPos.containing(actual);
        for (int dy : new int[] {0, -1, 1, -2}) {
            BlockPos feet = at.offset(0, dy, 0);
            if (ground.standable(feet)) {
                return Optional.of(feet);
            }
        }
        // A body can stand across the edge of a pit with its centre over air. Navigation's arrival contract
        // also includes the adjacent supporting stance; do not turn that legitimate position into no footing.
        List<BlockPos> nearby = new ArrayList<>();
        for (BlockPos feet : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
            if (Reach.standingAt(actual, feet) && ground.standable(feet)) {
                nearby.add(feet.immutable());
            }
        }
        nearby.sort(Comparator.comparingDouble(feet -> Vec3.atBottomCenterOf(feet).distanceToSqr(actual)));
        if (!nearby.isEmpty()) {
            return Optional.of(nearby.getFirst());
        }
        return Optional.empty();
    }

    private static void protect(Set<BlockPos> into, List<BlockPos> path) {
        for (BlockPos feet : path) {
            into.add(feet);
            into.add(feet.above());
            into.add(feet.above(2));
        }
    }

    private Vec3 position(LivingEntity body) {
        return WorldSpaces.relative(level, realm, body.position()).orElse(body.position())
            .add(origin.getX(), origin.getY(), origin.getZ());
    }

    private AABB worldBounds() {
        AABB result = null;
        for (int x : new int[] {bounds.minX(), bounds.maxX() + 1}) {
            for (int y : new int[] {bounds.minY(), bounds.maxY() + 1}) {
                for (int z : new int[] {bounds.minZ(), bounds.maxZ() + 1}) {
                    Vec3 shown = WorldSpaces.world(level, new WorldPos(realm, new BlockPos(x, y, z).subtract(origin)))
                        .orElse(new Vec3(x, y, z));
                    AABB point = new AABB(shown, shown);
                    result = result == null ? point : result.minmax(point);
                }
            }
        }
        return result.inflate(2.0D);
    }

    private void reframe(List<BlockPos> entrances) {
        List<BlockPos> points = new ArrayList<>(entrances);
        points.add(new BlockPos(workBox.minX(), workBox.minY(), workBox.minZ()));
        points.add(new BlockPos(workBox.maxX(), workBox.maxY(), workBox.maxZ()));
        BoundingBox box = BoundingBox.encapsulatingPositions(points).orElseThrow();
        BoundingBox next = new BoundingBox(box.minX() - MARGIN,
            Math.max(level.getMinBuildHeight(), box.minY() - MARGIN), box.minZ() - MARGIN,
            box.maxX() + MARGIN, Math.min(level.getMaxBuildHeight() - 1, box.maxY() + MARGIN), box.maxZ() + MARGIN);
        if (!next.equals(bounds)) {
            if (watch != null) {
                watch.close();
            }
            bounds = next;
            watch = Grounds.watch(level, bounds);
        }
        worldBounds = worldBounds();
    }

    private boolean outside(BlockPos feet) {
        return !cells.contains(feet) && !cells.contains(feet.above()) && !cells.contains(feet.below());
    }

    private boolean outsideAll(BlockPos feet) {
        return LIVE.getOrDefault(level, Set.of()).stream().allMatch(plan -> plan.outside(feet));
    }

    private double apart(BlockPos feet) {
        int x = Math.clamp(feet.getX(), workBox.minX(), workBox.maxX());
        int y = Math.clamp(feet.getY(), workBox.minY(), workBox.maxY());
        int z = Math.clamp(feet.getZ(), workBox.minZ(), workBox.maxZ());
        return feet.distSqr(new BlockPos(x, y, z));
    }

    private final class Context {

        private final List<LivingEntity> people;
        private final List<BlockPos> exits;
        private final Map<UUID, View> views = new HashMap<>();
        private final Map<Ground.Access, Map<BlockPos, List<BlockPos>>> approaches = new HashMap<>();
        private List<BlockPos> ports;

        Context() {
            List<BlockPos> trusted = external.get().stream().filter(FillPlan.this::outsideAll)
                .filter(feet -> apart(feet) <= MOST_EXIT_DISTANCE * MOST_EXIT_DISTANCE)
                .filter(feet -> level.isLoaded(feet) && Footing.withFeetAt(level, feet))
                .sorted(Comparator.comparingDouble(FillPlan.this::apart).thenComparingLong(BlockPos::asLong))
                .limit(32).toList();
            reframe(trusted);
            people = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class, worldBounds,
                body -> body.isAlive() && !body.isSpectator()));
            Set<BlockPos> found = new LinkedHashSet<>(trusted);
            found.addAll(exits());
            exits = List.copyOf(found);
        }

        View view(LivingEntity body) {
            return views.computeIfAbsent(body.getUUID(), any -> {
                Ground ground = watch.now(body);
                return new View(ground, ground.accessFrom(exits));
            });
        }

        List<BlockPos> workStances(Ground.Access access, BlockPos cell) {
            return approaches.computeIfAbsent(access, any -> new HashMap<>()).computeIfAbsent(cell, any ->
                access.stancesFor(cell).stream().filter(feet ->
                    Vec3.atBottomCenterOf(feet).add(0.0D, surveyor.getEyeHeight(), 0.0D)
                        .distanceToSqr(Vec3.atCenterOf(cell)) <= WORK_REACH * WORK_REACH)
                    .limit(MOST_STANCES).toList());
        }

        private List<BlockPos> exits() {
            List<BlockPos> found = new ArrayList<>();
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                exterior(found, x, bounds.minZ());
                exterior(found, x, bounds.maxZ());
            }
            for (int z = bounds.minZ() + 1; z < bounds.maxZ(); z++) {
                exterior(found, bounds.minX(), z);
                exterior(found, bounds.maxX(), z);
            }
            return List.copyOf(found);
        }

        private void exterior(List<BlockPos> into, int x, int z) {
            if (!level.hasChunk(x >> 4, z >> 4)) {
                return;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            if (bounds.isInside(feet) && outsideAll(feet)
                    && Footing.withFeetAt(level, feet)) {
                into.add(feet);
            }
        }

        List<BlockPos> ports(View view) {
            if (ports == null) {
                Set<BlockPos> found = new LinkedHashSet<>();
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                        port(found, view, new BlockPos(x, y, bounds.minZ()));
                        port(found, view, new BlockPos(x, y, bounds.maxZ()));
                    }
                }
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                        port(found, view, new BlockPos(bounds.minX(), y, z));
                        port(found, view, new BlockPos(bounds.maxX(), y, z));
                    }
                    for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                        port(found, view, new BlockPos(x, bounds.minY(), z));
                        port(found, view, new BlockPos(x, bounds.maxY(), z));
                    }
                }
                ports = List.copyOf(found);
            }
            return ports;
        }

        private void port(Set<BlockPos> into, View view, BlockPos feet) {
            if (view.access().distance(feet) >= 0) {
                into.add(feet);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        offered = Map.of();
        steppingAside = List.of();
        watch.close();
        Set<FillPlan> live = LIVE.get(level);
        if (live != null && live.remove(this) && live.isEmpty()) {
            LIVE.remove(level);
        }
    }
}
