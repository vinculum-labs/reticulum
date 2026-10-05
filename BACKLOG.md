# Backlog

Ideas set aside for later, and known gaps chosen to leave for now. Move items out as they are done or decided against.

## Ideas (parked)

- Blueprint slot "kind of block" picker on the catalog dialog. It mixes the network's devices with every block, which
  the dialog doesn't offer yet.
- A wire probe: hover a signal wire to see its live value, or pin the value as a label on the wire. The editor already
  gets every signal each second.
- Display: keep passing its signal on while switched off (only hidden from the dashboard). Decided to leave as is for
  now: off cuts the signal like any node.
- Built-in alarm toggles; a chat message for critical alarms.
- Charts: log scale; surviving a server restart (kept in memory only today).
- A machine placer's real owner where its mod records one (e.g. Create's Deployer), instead of treating it as no
  player. Each mod differs, so one at a time.
- A personal blueprint library across your networks (copied in, with "update from library"). User to think about it;
  filter templates would follow the same rule.

## Known gaps (left for now)

- Two quick edits to the same node within a tick: an edit queued behind one the server refuses still carries the refused
  change, and two quick list resizes (adding two inputs) can merge to the last only. Edits rebase on the snapshot plus
  what is in flight; a proper fix means rebasing on each confirmed step.
- A cable placed by a command or machine between blocks of a private network stays cut off from it, silently (no player
  to tell); someone with access can connect its joints by hand.
- A contraption moving part of a network across unloaded chunks: the part left behind and the part moved may not
  reconcile until both are loaded.
- Joints cut by hand survive a merge of two networks (`/reticulum merge`): they stay cut on the merged one. Kept: a cut
  made by hand is meant.
- Server bookkeeping: finding whether a block is reached scans linearly, and the version and alias maps are never
  pruned. Fine at today's sizes.
- Cut joints aren't rotated with a block: a rotated contraption or a rotated paste keeps the cuts on the old faces.
- A blueprint placed inside a blueprint keeps the slot blocks set inside the outer one: every instance of the outer
  blueprint drives the same blocks.
- Redstone outputs and ME links keep working while a network is unpowered (only item, fluid, chemical and kit moves
  stop). By design: redstone often switches the generator that would refill it, and ME links already draw AE2 power.
- A connector lifted by a contraption that never sets it down (the contraption destroyed) leaves its record behind,
  moving, with no devices (it costs no upkeep; it keeps its network from counting as empty).
- A network too large for the editor (well past 64 MiB of snapshot) can't be opened there: it says so.
- Admins can demote or remove other admins (only the owner is protected). Kept on purpose.
- "Copy as JSON" copies only the sheet: sheets inside it, the blueprints it places and the filter templates it uses are
  left out (the import says what was, and drops placed blueprints whose blueprint isn't there).
