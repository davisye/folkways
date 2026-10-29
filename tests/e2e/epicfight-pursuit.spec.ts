import { expect, test, type TestInfo } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { defineTask, javaTask, input, media, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";
import { SETUP, READ, RENDER, CLEANUP, GUI_SCALE, RELEASE_BINDING, type Scene, type Reading, type Evidence } from "../shared/combat/epicfight-native";
import { PROJECT, READ_CLIENT, type ClientState, type Pixel } from "../shared/combat/rts-fixtures";

type Actor = {
  resident: string; x: number; y: number; z: number; distance: number; health: number;
  owned: boolean; reserved: boolean; navigationDone: boolean; doing: string; balk: string;
  orderId: string; mode: string; status: string; attackEnd: string; failure: string; liveOrderId: string; liveMode: string; liveStatus: string;
  group: string; groupPhase: string; groupKind: string; groupEnd: string; failedMembers: string[];
  session: boolean; sessionType: string; sessionState: string; weaponCount: number; weaponDamage: number;
  components: boolean; weaponPlace: string; inventory: Array<{ place: string; count: number; serialized: string }>;
};
type Observation = {
  tick: number; bookInHand: boolean; currentColony: string; selection: string[];
  targetPresent: boolean; targetAlive: boolean; targetHealth: number; targetMax: number;
  targetType: string; targetNoAi: boolean; attacker: string; targetX: number; targetY: number; targetZ: number; actors: Actor[];
};

// A positive pursuit regression: arrangements use real entities/profiles/terrain; only actual V-screen
// mouse selection and a right-click on the spider may issue combat. No task fires or damages anything.
for (const raised of [true, false]) {
  test(`two iron-sword residents right-click a spider from nine blocks away across ${raised ? "grass steps and a turn" : "ordinary grass with uneven surroundings"} and land native hits`, async ({}, info) => {
    test.setTimeout(900_000);
    let pair: E2EPair | undefined;
    let scenes: Scene[] = [];
    const evidence: Evidence = [];
    let firstError: unknown;
    try {
      const run = newRunId();
      pair = await launchPair({ config: "blockwright.toml#epicfight-acceptance",
        server: { instance: `epicfight-spider-pursuit-server-${raised ? "step" : "ground"}-${run}` },
        client: { instance: `epicfight-spider-pursuit-client-${raised ? "step" : "ground"}-${run}` },
        snapshot: { entityTypes: ["folkways:resident", "minecraft:spider", "minecraft:zombie", "minecraft:item"] } });
      await pair.client.run(GUI_SCALE);
      const player = await waitForGroundedPlayer(pair.server);
      const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
      await terrain(pair, origin, raised);
      await mustCommand(pair.server, `gamemode survival ${player.name}`);
      const founded = await foundColonyFast(pair.server, pair.client, player.name, { origin, residentCount: 1 });
      await openColonyPanelViaBook(pair.server, pair.client, player.name, founded.standBlock);
      await screen.dismiss(pair.client);
      await terrain(pair, origin, raised, false);
      const first = await pair.server.run(SETUP, { ...origin, colony: String(founded.evidence.colonyId), player: player.name,
        weapon: "minecraft:iron_sword", autonomous: false });
      scenes = [first]; // Failure cleanup also owns the original fixture before the second spawn succeeds.
      scenes = await pair.server.run(PREPARE_PURSUIT, { ...first, raised });
      await mustCommand(pair.server, `tp ${player.name} ${origin.x + 5.5} ${origin.y} ${origin.z + .5} 0 0`);
      await tick.sprint(pair.server, 2); await frames(pair.client, 8);
      await input.key(pair.client, { keyCode: 86 });
      await screen.waitFor(pair.client, "OverheadScreen", { timeoutMs: 8_000 });
      const combat = (await screen.widgets(pair.client)).find(widget => /^RTS$/.test(widget.label?.trim() ?? ""));
      expect(combat, "the real V screen exposes the production RTS tool").toBeTruthy();
      await screen.clickWidget(pair.client, { label: combat!.label });
      await waitClient(pair, scenes, evidence, "production roster loaded", state => state.combat && state.roster >= 2);
      const boxes = await Promise.all(scenes.map(scene => pair!.client.run(PROJECT, { ...origin, entity: scene.resident })));
      boxes.forEach(visible);
      const from = { x: Math.min(...boxes.map(box => box.left)) - 7, y: Math.min(...boxes.map(box => box.top)) - 7 };
      const to = { x: Math.max(...boxes.map(box => box.right)) + 7, y: Math.max(...boxes.map(box => box.bottom)) + 7 };
      visible({ ...boxes[0], ...from }); visible({ ...boxes[0], ...to });
      evidence.push({ step: "actual two-person mouse box", value: { from, to, boxes } });
      await input.drag(pair.client, { from, to, steps: 8, button: "left" });
      const selected = await waitClient(pair, scenes, evidence, "mouse box acquires both actual residents", state =>
        sameIds(state.selected, scenes.map(scene => scene.resident)));
      const before = await pair.server.run(OBSERVE, { scenes });
      evidence.push({ step: "authoritative selected residents and actual colony book before right click", value: { server: before, client: selected } });
      expect(selected.failure, "selection itself must not show a production failure").toBe("");
      expect(before.bookInHand).toBe(true);
      expect(before.currentColony).toBe(scenes[0].colony);
      expect(sameIds(before.selection, scenes.map(scene => scene.resident))).toBe(true);
      expect(before.targetType).toBe("minecraft:spider");
      expect(before.targetNoAi).toBe(true);
      expect(before.targetHealth).toBe(40);
      for (const actor of before.actors) {
        physical(actor);
        expect(actor.owned && actor.reserved).toBe(true);
        expect(actor.distance, "right-click pursuit starts outside native melee collision").toBeGreaterThanOrEqual(8);
        expect(actor.distance).toBeLessThanOrEqual(10);
        expect(actor.weaponDamage).toBe(9);
      }
      const enemy = await pair.client.run(PROJECT, { ...origin, entity: scenes[0].monster }); visible(enemy);
      const routeRequest = selected.route.request;
      await capture(pair, info, "two-residents-before-spider-right-click.png");
      await input.click(pair.client, enemy, { button: "right" });
      await waitClient(pair, scenes, evidence, "actual hostile right-click receives ATTACK acknowledgement", state =>
        state.route.request > routeRequest && state.route.accepted && state.route.intent === "ATTACK" && !state.routePending);

      let sawNativeAttack = false, sawClientAttack = false, sawMovement = false, sawElevation = !raised;
      let damaged: Observation | undefined, defeated: Observation | undefined;
      const nativeParticipants = new Set<string>();
      const commandedResidents = new Set<string>(), movedResidents = new Set<string>();
      // Observe each world tick, including route setup, path pursuit, order failure and native collision.
      // No failed/regrouped order qualifies as success: an actual damage source and worn real item are mandatory.
      for (let elapsed = 0; elapsed < 600; elapsed++) {
        const server = await pair.server.run(OBSERVE, { scenes });
        const client = await pair.client.run(READ_CLIENT);
        const native: Reading[] = [];
        if (server.targetPresent) for (const scene of scenes) native.push(await pair.server.run(READ, scene));
        const draws = await Promise.all(scenes.map(scene => pair!.client.run(RENDER, scene)));
        evidence.push({ step: `pursuit tick ${elapsed}`, value: { server, client, native, draws } });
        expect(server.targetPresent, "the actual spider must remain loaded until an observed death").toBe(true);
        expect(server.bookInHand).toBe(true);
        for (const actor of server.actors) {
          physical(actor);
          const start = before.actors.find(row => row.resident === actor.resident)!;
          if (Math.hypot(actor.x - start.x, actor.z - start.z) > 2) movedResidents.add(actor.resident);
          sawMovement ||= movedResidents.size === 2;
          if (actor.mode === "ATTACK" || actor.groupKind === "ATTACK") commandedResidents.add(actor.resident);
          sawElevation ||= actor.y >= origin.y + .85;
        }
        for (let index = 0; index < native.length; index++) {
          const row = native[index];
          if (row.sessionType.endsWith(".EpicFightWeaponSession") && row.attackAnimation && row.nativeTarget)
            nativeParticipants.add(scenes[index].resident);
        }
        sawNativeAttack ||= nativeParticipants.size > 0;
        sawClientAttack ||= draws.some(draw => draw.present && draw.patch && draw.draws > 0 && draw.attackAnimation);
        if (server.targetHealth < before.targetHealth && before.actors.some(actor => actor.resident === server.attacker)
            && server.actors.some(actor => actor.weaponDamage > 9)) {
          if (!damaged) await capture(pair, info, "actual-spider-native-first-hit.png");
          damaged = server;
        }
        // Save evidence and screenshots before reporting the exact production failure.
        const failed = server.actors.find(actor => actor.failure || actor.status === "FAILED" || ["FAILED", "FAILED_WAITING"].includes(actor.groupPhase)
          || ["FAILED", "PARTIAL_FAILURE", "NO_SAFE_ANCHOR", "REGROUP_TIMED_OUT", "ROUTE_NO_PATH", "ROUTE_MEMBER_BLOCKED"].includes(actor.groupEnd));
        if (failed || client.failure) {
          await capture(pair, info, "spider-pursuit-production-failure.png");
          throw new Error(`designated spider pursuit failed before positive completion: ${JSON.stringify({ failed, client, server })}`);
        }
        if (!server.targetAlive) { defeated = server; break; }
        await tick.sprint(pair.server, 1); await frames(pair.client, 3);
      }
      expect(commandedResidents.size, "both selected residents must enter the actual designated-attack command").toBe(2);
      expect(sawMovement, "real command must physically pursue across the initial gap").toBe(true);
      expect(sawElevation, "the raised-terrain case must physically climb to the target level").toBe(true);
      expect(sawNativeAttack, "pursuit must produce the real native weapon animation, not just an accepted route").toBe(true);
      expect(sawClientAttack, "the client must render an actual native attack during pursuit").toBe(true);
      expect(damaged, "a production attack must land real resident damage and spend real sword durability").toBeDefined();
      expect(defeated, "right-click attack must reach positive target defeat within the full tick budget").toBeDefined();
      expect(defeated!.targetHealth).toBe(0);
      await capture(pair, info, "two-residents-completed-spider-pursuit.png");
      const release = await pair.client.run(RELEASE_BINDING);
      expect(release.active && release.type === "KEYSYM").toBe(true);
      await input.key(pair.client, { keyCode: release.code });
      await waitClient(pair, scenes, evidence, "real RELEASE closes command and hand loans after defeat", state => state.selected.length === 0);
      const released = await pair.server.run(OBSERVE, { scenes });
      evidence.push({ step: "final physical inventory after actual target defeat and keyboard RELEASE", value: released });
      expect(released.selection).toHaveLength(0);
      for (const actor of released.actors) {
        physical(actor);
        expect(actor.session).toBe(false);
        expect(actor.owned || actor.reserved).toBe(false);
        expect(actor.weaponPlace).toMatch(/^pack:/);
      }
    } catch (error) {
      firstError = error; pair?.failing(error);
      if (pair) { try { await capture(pair, info, "spider-pursuit-failure-last-frame.png"); } catch { /* Preserve the production failure. */ } }
      throw error;
    } finally {
      const file = info.outputPath("epicfight-spider-pursuit-every-tick.json");
      writeFileSync(file, JSON.stringify(evidence, null, 2), "utf8");
      let cleanupError: unknown;
      try { await info.attach("epicfight-spider-pursuit-every-tick.json", { path: file, contentType: "application/json" }); } catch (error) { cleanupError = error; }
      if (pair) for (const scene of [...scenes].reverse()) {
        try { await pair.server.run(CLEANUP, scene); } catch (error) { cleanupError ??= error; }
      }
      try { await pair?.teardown(info); } catch (error) { cleanupError ??= error; }
      if (firstError === undefined && cleanupError !== undefined) throw cleanupError;
    }
  });
}

function physical(actor: Actor) {
  expect(actor.weaponCount, `${actor.resident} actual packed/borrowed/dropped sword count`).toBe(1);
  expect(actor.components, "every original sword component except legitimate hit wear remains intact").toBe(true);
  expect(actor.weaponDamage).toBeGreaterThanOrEqual(9);
  expect(actor.health).toBe(20);
  expect(actor.weaponPlace).toMatch(actor.session ? /^mainhand$/ : /^pack:/);
}
function sameIds(actual: string[], expected: string[]) {
  return actual.length === expected.length && [...actual].sort().every((id, index) => id === [...expected].sort()[index]);
}
function visible(point: Pixel) {
  expect(point.x).toBeGreaterThan(5); expect(point.x).toBeLessThan(point.width - 5);
  expect(point.y).toBeGreaterThan(40); expect(point.y).toBeLessThan(point.height - 65);
}
async function waitClient(pair: E2EPair, scenes: Scene[], evidence: Evidence, step: string, done: (client: ClientState) => boolean) {
  for (let attempt = 0; attempt < 80; attempt++) {
    const server = await pair.server.run(OBSERVE, { scenes });
    const client = await pair.client.run(READ_CLIENT);
    evidence.push({ step: `${step} tick ${attempt}`, value: { server, client } });
    if (client.failure) throw new Error(`${step}: real production failure ${JSON.stringify({ server, client })}`);
    if (done(client)) return client;
    await tick.sprint(pair.server, 1); await frames(pair.client, 3);
  }
  throw new Error(`${step}: no actual client/server acknowledgement within 80 ticks`);
}
async function capture(pair: E2EPair, info: TestInfo, name: string) {
  const file = info.outputPath(name);
  await media.screenshot(pair.client, { path: file, hideGui: false });
  await info.attach(name, { path: file, contentType: "image/png" });
}
async function terrain(pair: E2EPair, p: Vec, raised: boolean, prepareGround = true) {
  if (prepareGround) {
  for (const command of ["difficulty normal", "gamerule doMobSpawning false", "gamerule doDaylightCycle false", "time set noon", "weather clear",
    `fill ${p.x - 16} ${p.y - 1} ${p.z - 16} ${p.x + 20} ${p.y - 1} ${p.z + 20} minecraft:grass_block`]) await mustCommand(pair.server, command);
  const cleared = await world.command(pair.server, `fill ${p.x - 16} ${p.y} ${p.z - 16} ${p.x + 20} ${p.y + 7} ${p.z + 20} minecraft:air`, { allowFailure: true });
  if (!cleared.success && !cleared.output.every(line => line.trim() === "No blocks were filled")) throw new Error(JSON.stringify(cleared));
  return;
  }
  // Reproducible ordinary-scale grass banks; the selected actors and target never start on a uniform superflat floor.
  await mustCommand(pair.server, `fill ${p.x + 3} ${p.y} ${p.z + 1} ${p.x + 4} ${p.y} ${p.z + 3} minecraft:grass_block`);
  await mustCommand(pair.server, `fill ${p.x + 9} ${p.y} ${p.z + 11} ${p.x + 14} ${p.y} ${p.z + 13} minecraft:grass_block`);
  if (raised) {
    await mustCommand(pair.server, `fill ${p.x + 8} ${p.y} ${p.z + 2} ${p.x + 16} ${p.y} ${p.z + 11} minecraft:grass_block`);
    await mustCommand(pair.server, `fill ${p.x + 7} ${p.y} ${p.z + 2} ${p.x + 7} ${p.y} ${p.z + 11} minecraft:stone_stairs[facing=east,half=bottom,shape=straight]`);
    // A finite two-block grass bank leaves wide, walkable openings at both ends and forces a real turn before the steps.
    await mustCommand(pair.server, `fill ${p.x + 6} ${p.y} ${p.z + 4} ${p.x + 6} ${p.y + 1} ${p.z + 8} minecraft:dirt`);
  }
}

const IMPORTS = ["java.util.UUID", "java.util.List", "java.util.Map", "java.util.ArrayList", "java.util.LinkedHashMap", "java.util.Optional",
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation",
  "net.minecraft.core.component.DataComponents", "net.minecraft.network.chat.Component", "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.Mob", "net.minecraft.world.entity.item.ItemEntity",
  "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.item.Items", "net.minecraft.world.item.ItemStack", "net.minecraft.world.entity.EquipmentSlot",
  "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held", "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.PersonContent", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles", "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile", "io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession", "io.github.izakyl.folkways.core.api.terms.ItemSpec",
  "io.github.izakyl.folkways.rts.combat.command.ResidentCommands", "io.github.izakyl.folkways.rts.combat.group.UnitGroups", "io.github.izakyl.folkways.rts.combat.control.CombatSelectionOwnership",
  "io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments"];

const PREPARE_PURSUIT = defineTask<Scene & { raised: boolean }, Scene[]>({ name: "folkways.epicfight.spider-pursuit.prepare-physical-fixture", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var colony=Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).orElseThrow();
var first=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));var old=level.getEntity(UUID.fromString(a.string("monster")));if(old!=null)old.discard();
var second=PersonBody.RESIDENT.get().create(level);second.moveTo(first.getX(),first.getY(),first.getZ()+1.6,-90,0);
var sword=new ItemStack(Items.IRON_SWORD);sword.setDamageValue(9);sword.set(DataComponents.CUSTOM_NAME,Component.literal("Pursuit second actual iron sword"));sword.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE,true);
second.getPersistentData().put("epicfight_acceptance_original",sword.save(level.registryAccess()));second.pack().insert(sword);second.pack().insert(new ItemStack(Items.BREAD,16));
if(!level.addFreshEntity(second))throw new IllegalStateException("second resident spawn failed");colony.hold(PersonContent.ID,Held.Entity.of(second)).orElseThrow();second.joinColony(colony.id());
var profiles=LoadoutProfiles.of(colony);var profile=profiles.create("Spider pursuit second real sword");profiles.add(profile.id(),LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD)));profiles.assign(List.of(second.id()),Optional.of(profile.id()));
var target=EntityType.SPIDER.create(level);target.setNoAi(true);target.setPersistenceRequired();target.moveTo(first.getX()+9,first.getY()+(a.flag("raised",false)?1:0),first.getZ());target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);target.setHealth(40);
if(!level.addFreshEntity(target))throw new IllegalStateException("actual spider spawn failed");
var rows=new ArrayList<Map<String,Object>>();for(var actor:List.of(first,second)){var row=new LinkedHashMap<String,Object>();row.put("colony",colony.id().toString());row.put("resident",actor.id().toString());row.put("monster",target.getUUID().toString());row.put("weapon","minecraft:iron_sword");row.put("player",a.string("player"));row.put("duty","");for(var key:List.of("x","y","z"))row.put(key,a.integer(key));rows.add(row);}return rows;` }) });

const OBSERVE = defineTask<{ scenes: Scene[] }, Observation>({ name: "folkways.epicfight.spider-pursuit.observe-production-orders-and-actual-items", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var scenes=a.list("scenes").stream().map(raw->Args.of(raw,"scenes[]")).toList();var first=scenes.get(0);var server=(MinecraftServer)ctx.server();var level=server.overworld();
var colony=Colonies.of(server,UUID.fromString(first.string("colony"))).orElseThrow();var player=server.getPlayerList().getPlayerByName(first.string("player"));var target=(Mob)level.getEntity(UUID.fromString(first.string("monster")));
var ownership=CombatSelectionOwnership.of(colony);var snapshot=ownership.snapshot(player);var rows=new ArrayList<Map<String,Object>>();
for(var scene:scenes){var body=(ResidentEntity)level.getEntity(UUID.fromString(scene.string("resident")));var group=UnitGroups.of(colony).ofResident(body.id()).orElse(null);
 var order=ResidentCommands.of(colony).current(body.id()).orElse(null);var liveOrder=order;if(order==null&&group!=null){var field=group.getClass().getDeclaredField("orders");field.setAccessible(true);order=((Map<UUID,ResidentCommands.Order>)field.get(group)).get(body.id());}
 // Cache only an observed real attack object, never edit it. Regroup clears the group's map in the
 // same world tick; retaining its actual reference preserves terminal failure/attackEnd evidence.
 if(order!=null&&order.mode()==ResidentCommands.Mode.ATTACK)observedAttackOrders.put(body.id(),order);
 var observedAttack=observedAttackOrders.get(body.id());if(observedAttack!=null)order=observedAttack;
 var expected=ItemStack.parseOptional(level.registryAccess(),body.getPersistentData().getCompound("epicfight_acceptance_original"));var stacks=new ArrayList<ItemStack>(body.pack().contents());if(body.hasWeaponSession())stacks.add(body.getMainHandItem());for(var drop:level.getEntitiesOfClass(ItemEntity.class,body.getBoundingBox().inflate(48)))stacks.add(drop.getItem());
 int count=0,damage=-1;boolean components=true;for(var stack:stacks)if(stack.is(expected.getItem())){count+=stack.getCount();damage=stack.getDamageValue();var normalized=stack.copy();normalized.setDamageValue(expected.getDamageValue());components&=ItemStack.isSameItemSameComponents(normalized,expected);}
 String place="missing";var inventory=new ArrayList<Map<String,Object>>();for(int slot=0;slot<body.pack().getContainerSize();slot++){var stack=body.pack().getItem(slot);if(stack.isEmpty())continue;if(stack.is(expected.getItem()))place="pack:"+slot;inventory.add(Map.of("place","pack:"+slot,"count",stack.getCount(),"serialized",stack.save(level.registryAccess()).toString()));}if(body.hasWeaponSession()){place="mainhand";var stack=body.getMainHandItem();inventory.add(Map.of("place","mainhand","count",stack.getCount(),"serialized",stack.save(level.registryAccess()).toString()));}
 var field=ResidentEntity.class.getDeclaredField("weaponSession");field.setAccessible(true);var session=(WeaponSession)field.get(body);var row=new LinkedHashMap<String,Object>();row.put("resident",body.id().toString());row.put("x",body.getX());row.put("y",body.getY());row.put("z",body.getZ());row.put("distance",target==null?-1:body.distanceTo(target));row.put("health",body.getHealth());row.put("owned",ownership.owns(player,body));row.put("reserved",ownership.reserved(body.id()));row.put("navigationDone",body.getNavigation().isDone());row.put("doing",body.doing().toString());row.put("balk",body.balk().toString());
 row.put("liveOrderId",liveOrder==null?"":liveOrder.id().toString());row.put("liveMode",liveOrder==null?"NONE":liveOrder.mode().name());row.put("liveStatus",liveOrder==null?"NONE":liveOrder.status().name());row.put("orderId",order==null?"":order.id().toString());row.put("mode",order==null?"NONE":order.mode().name());row.put("status",order==null?"NONE":order.status().name());row.put("attackEnd",order==null?"NONE":order.attackEnd().name());row.put("failure",order==null?"":order.failure().map(why->why.translationKey()).orElse(""));
 row.put("group",group==null?"":group.id().toString());row.put("groupPhase",group==null?"NONE":group.phase().name());row.put("groupKind",group==null?"NONE":group.commandKind().name());row.put("groupEnd",group==null?"NONE":group.endReason().name());row.put("failedMembers",group==null?List.of():group.failedMembers().stream().map(Object::toString).toList());
 row.put("session",session!=null);row.put("sessionType",session==null?"":session.getClass().getName());row.put("sessionState",session==null?"NONE":session.state().name());row.put("weaponCount",count);row.put("weaponDamage",damage);row.put("components",components);row.put("weaponPlace",place);row.put("inventory",inventory);rows.add(row);}
var out=new LinkedHashMap<String,Object>();out.put("tick",level.getGameTime());out.put("bookInHand",player.getMainHandItem().is(BuiltInRegistries.ITEM.get(ResourceLocation.parse("folkways:colony_book"))));out.put("currentColony",player.getData(FolkwaysPlayerAttachments.CURRENT_COLONY).map(Object::toString).orElse(""));out.put("selection",snapshot.selection().stream().map(Object::toString).toList());out.put("targetPresent",target!=null);out.put("targetAlive",target!=null&&target.isAlive());out.put("targetHealth",target==null?-1:target.getHealth());out.put("targetMax",target==null?40:target.getMaxHealth());out.put("targetType",target==null?"":BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());out.put("targetNoAi",target!=null&&target.isNoAi());out.put("attacker",target!=null&&target.getLastDamageSource()!=null&&target.getLastDamageSource().getEntity()!=null?target.getLastDamageSource().getEntity().getUUID().toString():"");out.put("targetX",target==null?0:target.getX());out.put("targetY",target==null?0:target.getY());out.put("targetZ",target==null?0:target.getZ());out.put("actors",rows);return out;`, members: `private static final Map<UUID,ResidentCommands.Order> observedAttackOrders = new LinkedHashMap<>();` }) });
