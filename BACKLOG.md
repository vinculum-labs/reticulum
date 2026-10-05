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

- Undoing "stop charting" brings the chart back with an empty history: the server forgets a removed chart's series.
- Chart colors don't follow a light/dark switch while Charts is open (reopening fixes it).
- On a stopped network (and for a moment after switching networks) switches show off, whatever the game remembers:
  the editor reads them from live values, which a stopped network doesn't send. A flip there shows until the next
  snapshot.
- Two placed blueprints can end up with one numbered name (undo of a removal after the number was reused, or a paste
  keeping its label): the name field warns, nothing renumbers.
- Two quick edits to the same node within a tick: an edit queued behind one the server refuses still carries the refused
  change, and two quick list resizes (adding two inputs) can merge to the last only. Edits rebase on the snapshot plus
  what is in flight; a proper fix means rebasing on each confirmed step.
- A cable placed by a command or machine between blocks of a private network stays cut off from it, silently (no player
  to tell); someone with access can connect its joints by hand.

- A contraption moving part of a network across unloaded chunks: the part left behind and the part moved may not
  reconcile until both are loaded.
- Joints cut by hand survive a merge of two networks (`/reticulum merge`): they stay cut on the merged one.
- Server bookkeeping: finding whether a block is reached scans linearly, and the version and alias maps are never
  pruned. Fine at today's sizes.
- Cut joints aren't rotated with a block: a rotated contraption or a rotated paste keeps the cuts on the old faces.
- Deleting an orphaned device record while nodes still use it leaves those nodes pointing at nothing (they show as
  missing).
- Importing a sheet skips some of the checks a hand edit gets (unique ids across the network, blueprint loops).
- Latch and counter memory inside a blueprint is lost on "make blueprint" or "detach".
- Filter names aren't checked (length, blanks) the way node names are.
- Adding a member by name looks the profile up on the server thread, which can stall it briefly for an unknown name.
- Merging networks doesn't check the count limits (sheets, members, filter templates): two full networks joined go
  past them; only new additions are refused afterwards.
- An undo or redo whose answer is lost (connection dropped, or the 30 s timeout) is dropped from the history, whether
  or not the game applied it.
- Per-node engine state (pulse and delay memory, rate-limit tokens, splitter turns) isn't pruned when nodes go; cleared
  only when the network stops. A slow leak; a node changed in place to another kind can read a stale edge once.
- A blueprint placed inside a blueprint keeps the slot blocks set inside the outer one: every instance of the outer
  blueprint drives the same blocks.
- Tidy up lays out the sheet as it was when clicked: a card dragged while the layout runs is put back where the layout
  wanted it.
- A wire drawn across a frame edge doesn't set the type of an untyped Sheet pin at either end (drawn on one sheet it
  does).
- A Level sensor detached from a blueprint whose slot was removed reads where that slot was laid out, unchecked (it
  reads nothing unless that block is one of the network's devices).
- Web editor icons are cached for the session: after a resource reload (F3+T, a resource pack) they stay as they were.
- KitMover may plan two parts into one empty slot seen through two faces of a sided block; the kit then rolls back
  (or, if the block refuses extraction, part of it stays).
- With "auto" faces, slot ranges index each face's own slots, while keep rules and caps count by the block's slots.
- Fluid, energy and chemical caps aren't shared between two routes into the same sink in one tick.
- Redstone outputs and ME links keep working while a network is unpowered (only item, fluid, chemical and kit moves
  stop). By design: redstone often switches the generator that would refill it, and ME links already draw AE2 power.
- Deliveries from the overflow buffer cost no power per operation and don't show in rates or charts.
- The look-at overlay shows the last reach and power of a network that has since stopped.
- A connector lifted by a contraption keeps serving the devices at its old spot until it is set down; one that never
  is (contraption destroyed) leaves its record moving forever.
- Rescans after a toggle, merge or rebind still read a device in an unloaded chunk as gone (only the rolling rescan
  waits for loaded neighbors): its names can stay behind on the old network.
- A private controller with an empty network, moved by a contraption, comes back on a new ownerless public network.
- A fresh controller broken by an explosion or a machine drops remembering a network that is deleted a moment later.
- Chart times are stamped from the browser's clock at each poll, so the newest point's time shifts by up to a minute
  between polls in the 24 h view.
- An untyped sheet output pin wired to nothing (or to something untyped) takes the resource of the sheet's input on
  the parent canvas (color, layer, rate unit).
- A network save that fails at the file level (bad gzip/NBT) isn't copied aside: Minecraft hands back nothing and the
  next save replaces it. Only files that read but don't decode are kept.
- Dashboard "Busiest" lists a placed blueprint and the nodes inside it for the same flow; "Waiting" shows the placed
  blueprint, not the node inside that waits.
- Admins can demote or remove other admins (only the owner is protected). Kept on purpose.
- A wire from a placed blueprint inside an open placed blueprint never lights (its value is kept under a nested name
  the editor doesn't work out).
- The alarm settings screen is cut off at the bottom on very short windows (scaled height under about 254).
- Typing in a box that rebuilds as you type (sound search) or while a refresh comes in puts the cursor at the end.
- A device whose chunk loads or unloads (across a border from its connector) doesn't refresh the editor's snapshot
  until something else changes.
- No server-side size limit on snapshots: a huge network (well past 64 MiB of JSON) never reaches the editor.
- A request of more than 64 KiB (about 22k CJK characters in an import) closes the editor's connection instead of
  being refused.
- Op/deop doesn't refresh an open editor's edit and admin controls until the network next changes (the server still
  refuses what they may no longer do).
- "Hold" on a sheet switched off by its own switch is lost on restart or when the network stops: the held value is kept
  in memory only, so the output gives 0 until the sheet is switched on.
- A sheet or placed blueprint on a "one per pulse" gate puts its signal logic to sleep between pulses (devices are
  exempt): outputs set to Fixed flicker, Timers and Pulses inside freeze. Undecided whether that's wanted.
- "Copy as JSON" copies only the sheet: sheets inside it, the blueprints it places and the filter templates it uses are
  left out, and an import keeps references to missing ones without saying so.
- The sheet tree accepts a drop the server then refuses for the depth or node-count limit (a toast says why).
