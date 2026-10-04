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
