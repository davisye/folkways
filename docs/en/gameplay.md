# Gameplay

[Documentation](README.md) · [简体中文](../zh-CN/gameplay.md)

For installation and the first five steps, see the
[project README](../../README.md#getting-started). The Colony Book's **Lessons**
tab contains Ponder tutorials; holding the ponder key over a page tab opens its
lesson too.

## Colonies and members

Colonies belong to players. The founder invites players from the **Members**
page; an invitation takes effect after the player accepts it. Only the founder
can end the colony. Click the panel's title to switch colonies or found another.
Any Colony Book opens the colony you are acting for, so losing a book does not
lose the colony.

With FTB Teams, a founder can hand a colony to their party. Party membership then
determines who keeps the colony, and the party owner holds the founder's rights.

## Work and storage

Rank each resident's trades on the **Workforce** page. The colony chooses the
tasks, supplies and order of work. Fields, pens, fisheries and patrol routes
describe where work happens; stock orders describe what a container should hold.

Each container can allow or refuse residents taking goods and using it for
ordinary storage. Orders still deliver to a container that refuses ordinary
storage. Blacklists and whitelists support items, tags and search rules through
the shared item picker. These rules apply to residents; players and external
automation retain their normal access.

## Searching

Install [Just Enough Characters (JEC)](https://www.curseforge.com/minecraft/mc-mods/just-enough-characters)
on the client to use Pinyin, initials and mixed Chinese/Pinyin input in item and
folder pickers, Workforce and stock searches. It follows JEC's keyboard and
fuzzy-spelling settings. JEC is optional and is not bundled with Folkways;
literal text searches remain available without it.

With Chinese item names and JEC's Quanpin keyboard, `tieding`, `td` and `tie锭`
find **铁锭** (iron ingot). `@minecraft tieding` limits item results to Minecraft.
Other keyboard schemes use their own spellings. Searches remain case-insensitive.

Item searches keep the existing `@mod`, `#tag`, item ID and wildcard syntax.
These terms match registry identifiers. Select an item found by name to add it
to a filter. A search saved as a filter rule uses IDs and tags; it does not use
translated names or Pinyin. Its meaning therefore does not depend on a player's
language or JEC settings.

If residents stop working, enable **Debug logs** on the Colony Book's **Settings**
page. Every ten seconds, your chat shows the current planning duration, idle
residents, task counts, recent blockers and whether the route optimizer used its
fallback. Blockers and task samples describe the last completed schedule; they can
be older than the live resident counts while planning is busy. Only the player
who enabled the option receives these messages. Closing the book keeps it enabled;
disconnecting disables it.

With the same option enabled, task interruptions, work and path failures, planning
exceptions and optimizer fallbacks appear as they happen. Work messages identify
the resident, task, position and target; pickup messages also name the dropped
item and its entity ID. Interruptions name the preempting behavior and the observed
combat target and recent damage source. These are clues, not proof of what triggered
the behavior. Events cover the colony across dimensions. Repeats are grouped for
five seconds and bursts are limited to four messages per two seconds; queued
occurrences retain their counts, with an overflow summary for larger bursts.
Turning the option off discards that reader's pending messages.

Route optimization has a work budget and, in the normal planning mode, a
100-millisecond deadline. If either is exhausted, the scheduler restores and
publishes its initial valid assignment so residents can work while further
improvement waits for another schedule.

## Food safety

Residents skip food with any chance of a harmful status effect, including effects
stored on suspicious stew. This also applies to food already in their packs;
safe meals in later slots are still eaten. Beneficial food effects and returned
bowls or bottles keep their normal behavior.

Food types known to be unsafe are excluded from ration deliveries and pack
reserves, even when selected in the colony's food settings. Unsafe food already
carried does not count toward the resident's nourishment when restocking.

Kaleidoscope Cookery's red and green chilies are also refused because eating them
causes direct damage. Datapacks can add other foods with special harmful eating
behavior to the `folkways:unsafe_food` item tag.

## Pastures

The target is the number of grown animals to retain. Residents breed ready adult
pairs while the total is below or at the target. At the target, one additional
birth is allowed; feeding waits while surplus young grow. Only adults above the
retained count are culled, so a newborn does not replace a breeding adult.

For example, retain two cows: two adults breed, their calf grows, then one of
the three adults is culled and the cycle can repeat after the normal breeding
cooldown. A target of zero disables breeding and culls all adults. One animal
has no breeding partner; keep at least two for continued meat production.

## Composting

Register an ordinary composter with the Colony Book. On the colony's
**Composting settings** page, enable or disable automatic composting, choose a
default seed reserve, and override reserves for individual planting materials.
The default reserve is **64**. These settings belong to the colony, not the book.

Only surplus seeds are available for composting. Sowing, reserved inputs and
stock orders remain protected. Native composters work with Core and Vanilla;
neither Cookery nor a straw hat is required. When the Cookery extension is
installed, its planting materials participate in the same reserve system.

The creative supply box also belongs to Vanilla and works without Cookery.

## Groundwork clearing

Clearing follows support dependencies. Side attachments have first priority,
followed by blocks that need support below or above, including falling blocks,
then ordinary blocks. A support waits until its dependents have been removed;
ordinary blocks still come down from the top. Residents check dependencies again
before breaking a block, and return products directly through colony logistics.

Clearing stays inside the marked box. Attachments outside it, or attachments the
settings preserve, also prevent their supports from being removed. Adjust the box
or remove those attachments first. Paired structures such as doors and beds must
be entirely inside the selection. Ordinary clearing dismantles scaffolds one cell
at a time, taking side decks before their supports to avoid a chain collapse.

## Groundwork

Fill works upwards in layers and advances from the deeper work towards its
entrance. Builders keep a way out for residents and players, and keep unfinished
work accessible. The passage moves onto completed ground as the fill rises;
builders retreat along it and finish the last opening from a safe stance.

If filling would block a passage, the zone waits and shows an access message.
Residents standing in the last space receive a task to walk clear. Players and
other bystanders must move themselves. Provide a usable path or steps to an
external working area when a pit has no safe exit.

## Buildings

Frame ground or draw a line, then choose a pattern. Builders clear the site and
use the colony's stores to construct roads, arch bridges, walls, docks, mines,
railways, arcades and terraced fields. Whole structures can also be placed from
the **Blueprints** page; Create schematics require Folkways × Create.

To add your own patterns and structures, see [Custom content](custom-content.md).

## Maid model packs

Touhou Little Maid's built-in and installed add-on models can become resident
looks, individually or by pack. These looks require GeckoLib; without it they
are disabled with a dependency tooltip.

Place ZIP files or pack folders directly under `<gamedir>/tlm_custom_pack/` on
the server and each client. ZIP files do not need extracting. Each client reads
its own installed packs; pack assets are not sent over the network.

After changing packs, run `/reload` on the server and reload client resources
with F3+T, or restart the game. Files in `config/touhou_little_maid/file/` are
download caches and do not count as installed packs.
