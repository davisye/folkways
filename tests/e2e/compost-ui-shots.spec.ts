import { expect, test } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { defineTask, input, javaTask, media, screen, world } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, openColonyPanelViaBook, optInViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { clickElement, panelElements, scrollElementIntoView } from "../ldlib2";
import { settlePanel, toggleCell, typeCell } from "../shared/ui/site-panel";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { seededServer } from "../shared/harness/mod-settings";
import { outAbs, tracePath } from "../shared/harness/out-paths";
import { frames } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const DISCOVERED = defineTask<void, string[]>({
  name: "folkways.compost-shots.discovered", side: "server",
  source: javaTask({ imports: ["io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.plugins.compost.CompostSettings", "net.minecraft.core.registries.BuiltInRegistries"], body: `
return CompostSettings.discovered(Colonies.all(ctx.server()).getFirst(), ctx.server()).stream().map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).toList();
` }),
});

const LANGUAGE = defineTask<void, boolean>({
  name: "folkways.compost-shots.language", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var mc = Minecraft.getInstance();
mc.getLanguageManager().setSelected("zh_cn"); mc.options.languageCode = "zh_cn";
mc.options.guiScale().set(2); mc.resizeDisplay(); mc.reloadResourcePacks();
return true;
` }),
});
const READY = defineTask<void, boolean>({
  name: "folkways.compost-shots.ready", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
return Minecraft.getInstance().getOverlay() == null;
` }),
});

test("compost settings: capture the independent entry, shared rice row and individual reserves", async ({}, testInfo) => {
  const runId = newRunId();
  const directory = outAbs("e2e", "shots", "compost-settings");
  mkdirSync(directory, { recursive: true });
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml#cookery",
      server: { instance: seededServer("compost-shots-server-" + runId), trace: tracePath("e2e", "compost-shots-server-" + runId) },
      client: { instance: "compost-shots-client-" + runId, trace: tracePath("e2e", "compost-shots-client-" + runId) },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    await client.run(LANGUAGE);
    for (let attempt = 0; attempt < 100; attempt++) {
      await frames(client, 5);
      if (await client.run(READY)) break;
    }
    expect(await client.run(READY)).toBe(true);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    const founded = await foundColonyFast(server, client, player.name, { origin, residentCount: 1 });
    const chest = { x: origin.x + 3, y: origin.y, z: origin.z + 3 };
    const compost = { x: origin.x + 5, y: origin.y, z: origin.z + 3 };
    const pos = (cell: typeof origin) => `${cell.x} ${cell.y} ${cell.z}`;
    await world.command(server, `setblock ${pos(chest)} minecraft:chest`);
    await world.command(server, `setblock ${pos(compost)} minecraft:composter`);
    await optInViaBook(server, client, player.name, chest, "chest");
    await optInViaBook(server, client, player.name, compost, "worksite");
    await screen.dismiss(client);
    for (const [slot, item] of ["minecraft:beetroot_seeds", "minecraft:carrot", "minecraft:wheat_seeds", "kaleidoscope_cookery:wild_rice", "kaleidoscope_cookery:rice"].entries()) {
      await world.command(server, `item replace block ${pos(chest)} container.${slot + 20} with ${item} 64`);
    }
    await openColonyPanelViaBook(server, client, player.name, founded.standBlock);
    await scrollElementIntoView(client, { id: "folkways.page.compost_settings" });
    const shot = async (name: string) => {
      await input.move(client, { x: 0, y: 0 });
      await frames(client, 5);
      const result = await media.screenshot(client, { path: `${directory}/${name}.png` });
      await testInfo.attach(name, { path: result.path, contentType: "image/png" });
    };
    await shot("01-independent-entry");
    await clickElement(client, { id: "folkways.page.compost_settings" });
    await settlePanel(server, client);
    const discovered = await server.run(DISCOVERED);
    expect(discovered.filter(id => id === "kaleidoscope_cookery:wild_rice" || id === "kaleidoscope_cookery:rice")).toHaveLength(1);
    const elements = await panelElements(client);
    expect(discovered).toHaveLength(4);
    expect(elements.some(element => element.text?.includes("野生稻种和稻米共享"))).toBe(false);
    await shot("02-default-reserves");
    await toggleCell(server, client, "folkways.settings.compost.enabled");
    await typeCell(server, client, "folkways.settings.compost.default_reserve", "96");
    await typeCell(server, client, "folkways.settings.compost.reserve." + discovered.indexOf("minecraft:beetroot_seeds"), "128");
    await shot("03-individual-reserve");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
