package io.github.izakyl.folkways.rts.combat.group;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutRegistry;
import io.github.izakyl.folkways.plugins.combat.weapon.PatrolSupplies;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.rts.FolkwaysRtsMod;
import io.github.izakyl.folkways.rts.combat.command.ResidentCommands;
import io.github.izakyl.folkways.rts.combat.control.CombatSelectionOwnership;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysRtsMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SquadExecutionGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SquadExecutionGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 500)
    public static void explicitAttackKeepsTheActualSquadAndReformsAfterTargetDeath(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(2);
        f.groups.commandSelectionPoints(people, List.of(f.at(18, 10)), -1);
        UnitGroup original = f.groups.ofResident(people.getFirst().id()).orElseThrow();
        UUID previous = original.commandId().orElseThrow();
        Zombie enemy = f.enemy(10, 10);
        UnitGroup attacking = f.groups.attackSelection(people, enemy, f.player);
        f.ownership.trackGroup(f.player, attacking);
        helper.assertTrue(
            attacking == original && !attacking.commandId().orElseThrow().equals(previous),
            "a complete squad preserves its actual identity while replacing its command generation");
        helper.assertTrue(attacking.pointRoute().isEmpty(), "a new attack discards the old route permanently");
        enemy.kill();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    attacking.phase() == UnitGroup.Phase.REFORMING,
                    "target death begins a separate survivor assembly"))
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    attacking.phase() == UnitGroup.Phase.HOLDING,
                    "survivors physically regroup and remain stable for twenty ticks"))
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    attacking.endReason(),
                    UnitGroup.EndReason.TARGET_DEFEATED,
                    "the completed attack exposes its target-death result");
                helper.assertTrue(
                    attacking.pointRoute().isEmpty()
                    && attacking.members().size() == 2
                    && people.stream().allMatch(body -> f.ownership.owns(f.player, body)),
                    "old travel never resumes and both survivors remain controlled");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 500)
    public static void attackTargetLossReformsAndCannotCancelAnIndividualReplacement(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(2);
        Zombie enemy = f.enemy(14, 10);
        UnitGroup group = f.groups.attackSelection(people, enemy, f.player);
        ResidentCommands.Order replacement = f.commands.hold(people.getFirst(), f.at(5, 7));
        f.groups.tick(f.level.getServer());
        enemy.discard();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    group.phase() == UnitGroup.Phase.HOLDING,
                    "the remaining member reforms after the target becomes unavailable"))
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    group.endReason(), UnitGroup.EndReason.TARGET_LOST, "loss remains observable");
                helper.assertTrue(
                    group.members().equals(Set.of(people.getLast().id())),
                    "a resident with a newer individual order leaves the old squad");
                group.release();
                helper.assertTrue(
                    f.commands.current(people.getFirst().id()).orElseThrow() == replacement,
                    "attack completion and old-group release preserve the new individual handle");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large")
    public static void targetRemovalKeepsHealthyDiscardsUnloadAndWorldChangesDistinctFromKilled(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(1);
        for (Entity.RemovalReason reason :
            List.of(
            Entity.RemovalReason.DISCARDED,
            Entity.RemovalReason.UNLOADED_TO_CHUNK,
            Entity.RemovalReason.CHANGED_DIMENSION,
            Entity.RemovalReason.KILLED)) {
            Zombie target = f.enemy(14, 10);
            helper.assertTrue(target.getHealth() > 0, "each removal begins with a healthy loaded target");
            UnitGroup group = f.groups.attackSelection(people, target, f.player);
            f.ownership.trackGroup(f.player, group);
            UUID generation = group.commandId().orElseThrow();
            ResidentCommands.Order attack = f.commands.current(people.getFirst().id()).orElseThrow();
            target.remove(reason);
            ResidentCommands.AttackEnd expected =
                reason == Entity.RemovalReason.KILLED
                ? ResidentCommands.AttackEnd.TARGET_DEFEATED
                : ResidentCommands.AttackEnd.TARGET_LOST;
            helper.assertValueEqual(
                attack.attackEnd(),
                expected,
                "the target's actual removal reason determines its terminal attack classification");
            f.groups.tick(f.level.getServer());
            helper.assertTrue(
                f.groups.ofResident(people.getFirst().id()).orElseThrow() == group
                && group.commandId().filter(generation::equals).isPresent()
                && group.phase() == UnitGroup.Phase.REFORMING
                && f.ownership.owns(f.player, people.getFirst()),
                "both loss and explicit KILLED retain actual squad identity, command generation and control"
                + " while reforming");
            helper.assertValueEqual(
                group.endReason(),
                reason == Entity.RemovalReason.KILLED
                ? UnitGroup.EndReason.TARGET_DEFEATED
                : UnitGroup.EndReason.TARGET_LOST,
                "squad aggregation preserves the same removal classification");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void runnerRefusalAfterTargetEscapesPreservesTheSpecificAttackEnd(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(2);
        Zombie enemy = f.enemy(14, 10);
        UnitGroup group = f.groups.attackSelection(people, enemy, f.player);
        var attacks = people.stream().map(body -> f.commands.current(body.id()).orElseThrow()).toList();
        // Keep the target loaded and inside the player bound, but beyond its attack-origin leash.
        // Deliver the real runner completion callback before the periodic command scan can release
        // the handles. Both orders now have Status.FAILED together with AttackEnd.OUT_OF_RANGE.
        BlockPos escaped = enemy.blockPosition().offset(65, 0, 0);
        f.level.getChunkAt(escaped);
        enemy.moveTo(escaped.getX() + .5, escaped.getY(), escaped.getZ() + .5);
        try {
            var ended = ResidentCommands.class.getDeclaredMethod("ended", ResidentCommands.Order.class, Ending.class);
            ended.setAccessible(true);
            for (var order : attacks) {
                ended.invoke(
                    f.commands, order, new Ending.Failed(UUID.randomUUID(), () -> "folkways.test.target_escaped"));
            }
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException(unavailable);
        }
        helper.assertTrue(
            attacks.stream()
            .allMatch(
            order ->
                order.status() == ResidentCommands.Status.FAILED
                    && order.attackEnd() == ResidentCommands.AttackEnd.OUT_OF_RANGE),
            "the fixture exercises failed runner status with a specific normal termination reason: "
                + attacks.stream().map(order -> order.status() + "/" + order.attackEnd()).toList());
        f.groups.tick(f.level.getServer());
        helper.assertTrue(
            group.endReason() == UnitGroup.EndReason.OUT_OF_RANGE && group.failedMembers().isEmpty(),
            "squad aggregation preserves the leash result instead of reporting a false complete or partial"
            + " execution failure");
        helper.assertTrue(
            group.members().size() == 2 && group.phase() == UnitGroup.Phase.REFORMING,
            "both terminal members retain their squad identity and begin safe local assembly");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void partialCrossGroupAttackTransfersOnlyTheExplicitSelection(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(4);
        UnitGroup first = f.groups.create(people.subList(0, 2));
        UnitGroup second = f.groups.create(people.subList(2, 4));
        first.hold(f.line(8));
        second.hold(f.line(10));
        var untouched =
            List.of(
            f.commands.current(people.get(1).id()).orElseThrow(),
            f.commands.current(people.get(3).id()).orElseThrow());
        UnitGroup attack = f.groups.attackSelection(List.of(people.get(0), people.get(2)), f.enemy(16, 10), f.player);
        helper.assertTrue(
            attack != first
            && attack != second
            && attack.members().size() == 2
            && first.members().equals(Set.of(people.get(1).id()))
            && second.members().equals(Set.of(people.get(3).id())),
            "a cross-group subset becomes one new squad and leaves unselected members in place");
        helper.assertTrue(
            untouched.stream().allMatch(order -> f.commands.current(order.resident()).orElseThrow() == order),
            "the transfer leaves unselected handles unchanged");
        var attacks = List.copyOf(attack.destinations().keySet());
        first.release();
        second.release();
        helper.assertTrue(
            attacks.stream()
            .allMatch(id -> f.commands.current(id).orElseThrow().mode() == ResidentCommands.Mode.ATTACK),
            "old groups cannot release the transferred attack handles");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void appendedPointsInheritTheirOriginalMovementPolicy(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(2);
        f.groups.appendSelectionPoint(people, f.at(12, 10), true);
        UnitGroup group = f.groups.ofResident(people.getFirst().id()).orElseThrow();
        UUID generation = group.commandId().orElseThrow();
        var handles = people.stream().map(body -> f.commands.current(body.id()).orElseThrow()).toList();
        try {
            f.groups.appendSelectionPoint(people, f.at(18, 10), false);
            helper.fail("changing the movement policy of an existing queue must be rejected");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(
                group.attackMove()
                && group.pointRoute().equals(List.of(f.at(12, 10)))
                && handles.stream()
                .allMatch(order -> f.commands.current(order.resident()).orElseThrow() == order),
                "a rejected mode change preserves route intent and every command handle");
        }
        f.groups.appendSelectionPoint(people, f.at(18, 10), true);
        helper.assertTrue(
            group.commandId().orElseThrow().equals(generation) && group.attackMove(),
            "a compatible append retains command identity and the attack-move policy");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 1900)
    public static void oneUnreachableAttackMemberCannotKeepTheEntireSquadAttackingForever(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(2);
        // Only the first body is sealed in a dry standing cell. The other body can reach the
        // target.
        for (int y = 1; y <= 3; y++) {
            for (int x = 1; x <= 3; x++) {
                for (int z = 6; z <= 8; z++) {
                    if (x != 2 || z != 7) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
        UnitGroup group = f.groups.attackSelection(people, f.enemy(12, 10), f.player);
        f.ownership.trackGroup(f.player, group);
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    group.phase() == UnitGroup.Phase.HOLDING
                    || group.phase() == UnitGroup.Phase.FAILED_WAITING,
                    "one unreachable member cannot keep any survivor attacking indefinitely"))
            .thenExecute(
            () -> {
                helper.assertTrue(
                    !group.failedMembers().isEmpty()
                    || group.endReason() == UnitGroup.EndReason.TIMED_OUT
                    || group.endReason() == UnitGroup.EndReason.REGROUP_TIMED_OUT,
                    "the failed member or bounded failure remains observable");
                helper.assertTrue(
                    people.stream().allMatch(body -> f.ownership.owns(f.player, body)),
                    "bounded failure preserves squad control until the next command or explicit"
                    + " release");
                helper.assertTrue(
                    people.stream()
                    .noneMatch(
                    body ->
                        f.commands
                            .current(body.id())
                            .filter(
                            order ->
                                order.mode()
                                    == ResidentCommands.Mode
                                    .ATTACK)
                            .isPresent()),
                    "no old attack keeps pursuing");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large")
    public static void stopWithNoSafeLocalAnchorStillCancelsTheOldPursuit(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(2);
        UnitGroup group = f.groups.attackSelection(people, f.enemy(16, 10), f.player);
        f.ownership.trackGroup(f.player, group);
        var attacks = people.stream().map(body -> f.commands.current(body.id()).orElseThrow()).toList();
        for (int x = 0; x <= 5; x++) {
            for (int z = 4; z <= 12; z++) {
                for (int y = -3; y <= 0; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        UnitGroup stopped = f.groups.stopSelection(people);
        f.ownership.trackGroup(f.player, stopped);
        helper.assertTrue(
            stopped == group
            && stopped.phase() == UnitGroup.Phase.FAILED_WAITING
            && stopped.endReason() == UnitGroup.EndReason.NO_SAFE_ANCHOR,
            "unsafe footing reports a waiting failure while accepting stop");
        helper.assertTrue(
            attacks.stream().allMatch(order -> order.status() == ResidentCommands.Status.RELEASED)
            && people.stream()
            .allMatch(
            body ->
                f.commands.current(body.id()).isEmpty()
                    && f.ownership.owns(f.player, body)),
            "stop always cancels pursuing handles and retains selection ownership even without assembly cells");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 1600)
    public static void attackMovePausesItsOriginalStopAndResumesAfterPhysicalReassembly(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(2);
        f.groups.commandSelectionPoints(people, List.of(f.at(18, 10), f.at(18, 17)), 0, true);
        UnitGroup group = f.groups.ofResident(people.getFirst().id()).orElseThrow();
        Zombie enemy = f.enemy(7, 9);
        AtomicReference<WorldPos> captain = new AtomicReference<>();
        AtomicReference<Map<UUID, WorldPos>> slots = new AtomicReference<>();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    enemy.getHealth() < enemy.getMaxHealth(),
                    "attack move automatically engages an unprovoked hostile through the actual"
                    + " weapon runner"))
            .thenExecute(
            () -> {
                captain.set(group.captain().orElseThrow());
                slots.set(group.destinations());
                helper.assertValueEqual(
                    group.patrolStop(), 0, "combat cannot skip the first mandatory stop");
                enemy.setHealth(200);
            })
            .thenIdle(10)
            .thenExecute(
            () -> {
                helper.assertTrue(
                    group.captain().orElseThrow().equals(captain.get())
                    && group.destinations().equals(slots.get()),
                    "combat pauses the captain and preserves the original return slots");
                enemy.kill();
            })
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    group.captain().orElseThrow().equals(f.at(18, 10))
                    && group.phase() == UnitGroup.Phase.HOLDING,
                    "the squad resumes and physically reaches the original mandatory stop"))
            .thenIdle(19)
            .thenExecute(
            () ->
                helper.assertValueEqual(
                    group.patrolStop(), 0, "the original stop still receives its assembly dwell"))
            .thenWaitUntil(
            () ->
                helper.assertValueEqual(
                    group.patrolStop(), 1, "the next mandatory stop follows only after regrouping"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 500)
    public static void stopRetainsControlButReleaseRemovesSelectedMembersFromEveryShortcut(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(2);
        f.ownership.bindGroup(f.player, 0);
        f.ownership.bindGroup(f.player, 8);
        UnitGroup attacking = f.groups.attackSelection(people, f.enemy(16, 10), f.player);
        f.ownership.trackGroup(f.player, attacking);
        UnitGroup stopped = f.groups.stopSelection(people);
        f.ownership.trackGroup(f.player, stopped);
        helper.assertTrue(
            stopped == attacking
            && stopped.pointRoute().isEmpty()
            && people.stream().allMatch(body -> f.ownership.owns(f.player, body)),
            "stop keeps the same complete squad and its control lease");
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertValueEqual(
                    stopped.phase(),
                    UnitGroup.Phase.HOLDING,
                    "stop safely assembles instead of releasing"))
            .thenExecute(
            () -> {
                helper.assertTrue(
                    f.ownership.releaseSelected(f.player, List.of(people.getFirst().id())),
                    "release accepts the selected subset");
                var state = f.ownership.snapshot(f.player);
                helper.assertTrue(
                    !state.selection().contains(people.getFirst().id())
                    && state.groups().stream()
                    .noneMatch(group -> group.contains(people.getFirst().id())),
                    "release clears every selected reference atomically");
                helper.assertTrue(
                    !f.ownership.owns(f.player, people.getFirst())
                    && f.ownership.owns(f.player, people.getLast())
                    && stopped.members().equals(Set.of(people.getLast().id())),
                    "unselected survivors retain their ownership and actual group");
                helper.assertTrue(
                    f.commands.current(people.getFirst().id()).isEmpty(),
                    "release relinquishes the released member's exact handle");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 900)
    public static void twoArmedResidentsPursueSpiderOverOneBlockStepAndDefeatIt(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(2);
        for (int x = 10; x <= 15; x++) {
            for (int z = 8; z <= 12; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.GRASS_BLOCK);
            }
        }
        Spider spider = EntityType.SPIDER.create(f.level);
        spider.setNoAi(true);
        spider.setPersistenceRequired();
        spider.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);
        spider.setHealth(40);
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 10));
        spider.moveTo(target.getX() + .5, target.getY(), target.getZ() + .5);
        f.level.addFreshEntity(spider);
        f.enemies.add(spider);
        UnitGroup group = f.groups.attackSelection(people, spider, f.player);
        f.ownership.trackGroup(f.player, group);
        helper.onEachTick(() -> {
            if (spider.isAlive() && (group.phase() == UnitGroup.Phase.FAILED_WAITING || group.phase() == UnitGroup.Phase.FAILED)) {
                helper.fail("armed squad stopped before defeating the reachable spider: phase=" + group.phase()
                    + " reason=" + group.endReason() + " health=" + spider.getHealth()
                    + " members=" + people.stream().map(body -> f.commands.current(body.id())
                        .map(order -> order.status() + "/" + order.attackEnd() + "/" + order.failure())
                        .orElse("no order") + " pos=" + body.mob().position() + " pack=" + body.pack().contents()).toList());
            }
        });
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(spider.getHealth() < 40, "the real approach reaches the small target and deals actual damage"))
            .thenWaitUntil(() -> helper.assertFalse(spider.isAlive(), "the explicit squad attack physically defeats the spider"))
            .thenExecute(() -> helper.assertTrue(people.stream().allMatch(body -> f.ownership.owns(f.player, body)),
                "finishing combat retains player control until explicit release"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 850)
    public static void temporarilyBlockedAttackerRejoinsAndDealsRealDamageAfterOpening(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(2);
        // Unequal real weapon damage avoids vanilla's synchronized equal-damage invulnerability
        // window masking every hit by the recovered attacker. The healthy peer keeps this same
        // physical weapon and order throughout the scenario.
        people.getLast().pack().setItem(0, new ItemStack(Items.GOLDEN_SWORD));
        for (int y = 1; y <= 3; y++) for (int x = 1; x <= 3; x++) for (int z = 6; z <= 8; z++) {
            if (x != 2 || z != 7) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        }
        Spider spider = EntityType.SPIDER.create(f.level);
        spider.setNoAi(true);
        spider.setPersistenceRequired();
        spider.getAttribute(Attributes.MAX_HEALTH).setBaseValue(160);
        // NoAI alone still allows hit knockback. Keep this recovery fixture's target at its
        // original approach site so it does not turn into a different moving-target chase test.
        spider.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        spider.setHealth(160);
        BlockPos target = helper.absolutePos(new BlockPos(12, 1, 10));
        spider.moveTo(target.getX() + .5, target.getY(), target.getZ() + .5);
        f.level.addFreshEntity(spider);
        f.enemies.add(spider);
        AtomicBoolean opened = new AtomicBoolean(), recoveredHit = new AtomicBoolean(), peerHit = new AtomicBoolean();
        Consumer<LivingDamageEvent.Post> damage = event -> {
            if (event.getEntity() != spider) return;
            if (event.getSource().getEntity() == people.getLast().mob()) peerHit.set(true);
            if (opened.get() && event.getSource().getEntity() == people.getFirst().mob()) recoveredHit.set(true);
        };
        NeoForge.EVENT_BUS.addListener(damage);
        f.listeners.add(damage);
        UnitGroup group = f.groups.attackSelection(people, spider, f.player);
        f.ownership.trackGroup(f.player, group);
        UUID generation = group.commandId().orElseThrow();
        ResidentCommands.Order healthy = f.commands.current(people.getLast().id()).orElseThrow();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(peerHit.get(), "the unobstructed peer keeps dealing real damage"))
            .thenExecute(() -> {
                for (int y = 1; y <= 3; y++) for (int x = 1; x <= 3; x++) for (int z = 6; z <= 8; z++) {
                    if (x != 2 || z != 7) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
                opened.set(true);
                helper.assertTrue(f.commands.current(healthy.resident()).orElse(null) == healthy,
                    "a peer failure cannot replace the healthy attack handle");
            })
            .thenWaitUntil(() -> helper.assertTrue(recoveredHit.get(),
                "after opening the way the original squad must recover its blocked attacker and land a real hit; phase="
                    + group.phase() + " reason=" + group.endReason() + " first=" + people.getFirst().mob().position()
                    + " order=" + f.commands.current(people.getFirst().id()).map(order -> order.status()
                        + "/" + order.attackEnd() + "/" + order.failure()).orElse("none")))
            .thenExecute(() -> helper.assertTrue(f.commands.current(people.getFirst().id()).orElseThrow().failure().isEmpty(),
                "a real resumed attack clears the previous attempt's refusal"))
            .thenWaitUntil(() -> helper.assertFalse(spider.isAlive(), "the preserved attack intent defeats the real spider"))
            .thenExecute(() -> helper.assertTrue(group.commandId().orElseThrow().equals(generation),
                "member recovery must not author a new squad command or reset its deadline"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 700)
    public static void regroupRetriesOnlyBlockedMemberAfterOpeningWithoutReplacingHealthyHold(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(2);
        regroupWall(helper, true);
        UnitGroup group = f.groups.stopSelection(people);
        f.ownership.trackGroup(f.player, group);
        UUID generation = group.commandId().orElseThrow();
        ResidentCommands.Order healthy = f.commands.current(people.getFirst().id()).orElseThrow();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(group.phase() == UnitGroup.Phase.WAITING_FOR_MEMBER
                || group.phase() == UnitGroup.Phase.FAILED_WAITING,
                "an actually blocked regroup becomes observable"))
            .thenExecute(() -> {
                helper.assertTrue(f.commands.current(healthy.resident()).orElse(null) == healthy,
                    "one failed member must not clear the already safe member's hold");
                regroupWall(helper, false);
            })
            .thenWaitUntil(() -> helper.assertValueEqual(group.phase(), UnitGroup.Phase.HOLDING,
                "opening the obstruction automatically restores the original regroup"))
            .thenExecute(() -> {
                helper.assertTrue(group.commandId().orElseThrow().equals(generation), "recovery retains the original command generation");
                helper.assertTrue(f.commands.current(healthy.resident()).orElse(null) == healthy,
                    "successful recovery leaves the healthy order instance unchanged");
                helper.assertTrue(group.failedMembers().isEmpty(), "recovered members no longer report a failed slot");
                helper.assertTrue(people.stream().allMatch(body -> f.ownership.owns(f.player, body)), "recovery retains player control");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 700)
    public static void releaseDuringRegroupBackoffCannotReviveAnyOldHold(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(2);
        regroupWall(helper, true);
        UnitGroup group = f.groups.stopSelection(people);
        f.ownership.trackGroup(f.player, group);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(group.phase() == UnitGroup.Phase.WAITING_FOR_MEMBER
                || group.phase() == UnitGroup.Phase.FAILED_WAITING, "the regroup is actually waiting on an obstruction"))
            .thenExecute(() -> {
                helper.assertTrue(f.ownership.releaseSelected(f.player, people.stream().map(Body::id).toList()), "player releases the waiting squad");
                regroupWall(helper, false);
            }).thenIdle(220)
            .thenExecute(() -> helper.assertTrue(people.stream().allMatch(body -> f.commands.current(body.id()).isEmpty()
                && !f.ownership.owns(f.player, body)), "late regroup probes cannot reclaim released residents"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 700)
    public static void replacementMoveDuringAttackBackoffCannotReviveTheOldTarget(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(2);
        for (int y = 1; y <= 3; y++) for (int x = 1; x <= 3; x++) for (int z = 6; z <= 8; z++) {
            if (x != 2 || z != 7) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        }
        Zombie enemy = f.enemy(12, 10);
        enemy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(160);
        enemy.setHealth(160);
        UnitGroup original = f.groups.attackSelection(people, enemy, f.player);
        f.ownership.trackGroup(f.player, original);
        ResidentCommands.Order oldAttack = f.commands.current(people.getFirst().id()).orElseThrow();
        AtomicReference<UnitGroup> moved = new AtomicReference<>();
        float[] afterMove = new float[1];
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(oldAttack.failure().isPresent()
                && f.commands.current(oldAttack.resident()).orElse(null) == oldAttack,
                "the original failed approach is really in recoverable backoff"))
            .thenExecute(() -> {
                // Remove the fixture wall in this same tick, before the new route's all-member
                // start-footing validation. The old attack is still in its timed backoff.
                for (int y = 1; y <= 3; y++) for (int x = 1; x <= 3; x++) for (int z = 6; z <= 8; z++) {
                    if (x != 2 || z != 7) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
                helper.assertTrue(f.groups.commandSelectionPoints(people, List.of(f.at(18, 18)), -1), "the new ground move is accepted");
                moved.set(f.groups.ofResident(people.getFirst().id()).orElseThrow());
                f.ownership.trackGroup(f.player, moved.get());
                afterMove[0] = enemy.getHealth();
            }).thenIdle(160)
            .thenExecute(() -> {
                helper.assertValueEqual(enemy.getHealth(), afterMove[0], "no delayed attack retry damages the old target after MOVE");
                helper.assertTrue(people.stream().noneMatch(body -> f.commands.current(body.id())
                    .filter(order -> order.mode() == ResidentCommands.Mode.ATTACK).isPresent()), "the old attack cannot retake either member");
                helper.assertValueEqual(oldAttack.attackEnd(), ResidentCommands.AttackEnd.REPLACED, "the old permission stays replaced");
            })
            .thenWaitUntil(() -> helper.assertTrue(moved.get().routeComplete(), "the new move reaches its own mandatory stop"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 650)
    public static void individualMoveDuringMissingAnchorWaitCannotBeOverwrittenByRegroup(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Body> people = f.people(2);
        for (int x = 0; x <= 5; x++) for (int z = 4; z <= 12; z++) for (int y = -3; y <= 0; y++) {
            helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        UnitGroup group = f.groups.stopSelection(people);
        f.ownership.trackGroup(f.player, group);
        helper.assertValueEqual(group.endReason(), UnitGroup.EndReason.NO_SAFE_ANCHOR, "the regroup really has no local anchor");
        ResidentCommands.Order move = f.commands.move(people.getFirst(), f.at(18, 18));
        // Restore traversable terrain before advancing the world, while the old regroup is waiting.
        for (int x = 0; x <= 5; x++) for (int z = 4; z <= 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        }
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertValueEqual(move.status(), ResidentCommands.Status.ARRIVED,
                "the individual replacement physically arrives instead of being reclaimed by regroup"))
            .thenIdle(220)
            .thenExecute(() -> {
                helper.assertTrue(f.commands.current(move.resident()).isEmpty(), "no old regroup HOLD appears after the completed move");
                helper.assertTrue(!group.members().contains(move.resident()), "the individual replacement safely leaves the recovering group");
            }).thenSucceed();
    }

    private static void regroupWall(GameTestHelper helper, boolean present) {
        for (int y = 1; y <= 3; y++) for (int x = 1; x <= 3; x++) for (int z = 8; z <= 10; z++) {
            if (x != 2 || z != 9) helper.setBlock(new BlockPos(x, y, z), present ? Blocks.STONE : Blocks.AIR);
        }
    }

    private static final class Fixture implements GameTestListener {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final ResidentCommands commands;
        final UnitGroups groups;
        final CombatSelectionOwnership ownership;
        final ServerPlayer player;
        final LaborFixture labour;
        final List<Body> bodies = new ArrayList<>();
        final List<Mob> enemies = new ArrayList<>();
        final List<Object> listeners = new ArrayList<>();
        final Map<UUID, ServerPlayer> playerIndex;

        @SuppressWarnings("unchecked")
        Fixture(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            commands = ResidentCommands.of(colony);
            groups = UnitGroups.of(colony);
            ownership = CombatSelectionOwnership.of(colony);
            player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "squad_test"));
            player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(FolkwaysItems.COLONY_BOOK.get()));
            BlockPos feet = at(10, 10).block(level);
            player.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
            Charters.keep(colony, Charter.founded("squad test", player.getUUID()));
            labour = new LaborFixture(colony, level);
            // Explicit attack authority deliberately uses the real server player index. Install
            // only
            // this fixture's random UUID there, then remove it on both passing and failing cleanup.
            try {
                var field = PlayerList.class.getDeclaredField("playersByUUID");
                field.setAccessible(true);
                playerIndex = (Map<UUID, ServerPlayer>) field.get(level.getServer().getPlayerList());
            } catch (ReflectiveOperationException unavailable) {
                throw new IllegalStateException(unavailable);
            }
            helper.testInfo.addListener(this);
            playerIndex.put(player.getUUID(), player);
            for (int x = 0; x < 22; x++) {
                for (int z = 0; z < 22; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            helper.onEachTick(
                () -> {
                    ownership.heartbeat(player);
                    labour.tick();
                });
        }

        WorldPos at(int x, int z) {
            return WorldPos.of(level, helper.absolutePos(new BlockPos(x, 1, z)));
        }

        Deployment line(int x) {
            return new Deployment(at(x, 7), at(x, 13), new Vec3(1, 0, 0), new Deployment.Formation(2));
        }

        List<Body> people(int count) {
            List<Body> people = new ArrayList<>();
            for (int n = 0; n < count; n++) {
                Body body = PersonBody.RESIDENT.get().create(level);
                BlockPos cell = at(2, 7 + n * 2).block(level);
                body.mob().moveTo(cell.getX() + .5, cell.getY(), cell.getZ() + .5);
                level.addFreshEntity(body.mob());
                colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow();
                body.joinColony(colony.id());
                PatrolSupplies.of(colony).loadout(body, LoadoutRegistry.MELEE);
                body.pack().insert(new ItemStack(Items.IRON_SWORD));
                bodies.add(body);
                people.add(body);
            }
            ownership.select(player, people.stream().map(Body::id).toList());
            return List.copyOf(people);
        }

        Zombie enemy(int x, int z) {
            Zombie enemy = EntityType.ZOMBIE.create(level);
            enemy.setNoAi(true);
            enemy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            BlockPos at = at(x, z).block(level);
            enemy.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            level.addFreshEntity(enemy);
            enemies.add(enemy);
            return enemy;
        }

        void close() {
            try {
                listeners.forEach(NeoForge.EVENT_BUS::unregister);
                ownership.clearContext(player);
                labour.close();
                bodies.forEach(body -> body.mob().discard());
                enemies.forEach(Mob::discard);
                colony.raze();
            } finally {
                playerIndex.remove(player.getUUID(), player);
                player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            }
        }

        public void testStructureLoaded(GameTestInfo info) {
        }

        public void testPassed(GameTestInfo info, GameTestRunner runner) {
            close();
        }

        public void testFailed(GameTestInfo info, GameTestRunner runner) {
            close();
        }

        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) {
        }
    }
}
