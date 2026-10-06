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

- A cable placed by a command or machine between blocks of a private network stays cut off from it, silently (no player
  to tell); someone with access can connect its joints by hand.
- Networks are only worked out over loaded chunks: a section reaching into unloaded ones (a contraption's part left
  behind there, neighbors of a block that left) is only rechecked once it is loaded and touched again; two controllers
  meeting in one section there are found only then.
- Joints cut by hand survive a merge of two networks (`/reticulum merge`): they stay cut on the merged one. Kept: a cut
  made by hand is meant.
- Server bookkeeping: finding whether a block is reached scans linearly, and the version and alias maps are never
  pruned. Fine at today's sizes.
- A sheet whose gate turns back on runs its routes that tick, while the signal nodes inside it still give what they
  held when it went off (they wake the tick after): an inner gate can let one tick through on an old reading.
- Status-marker labels ("orphaned", "missing", "unreachable") and "Missing connector" are English strings the server
  sends: they can't be translated until the packets carry components.
- A facade doesn't turn with its block: stairs or logs worn by a cable in a turned structure keep their old facing.
- Cut joints and switched-off device sides turn with a block only through the game's own turns (around the vertical
  axis, and mirrors): a Create bearing turning sideways leaves them on the sides of the world they had (Create's own
  hook would fix it; left out to need no mod-specific code). A block copied with ctrl+pick-block from a turned one
  gets them turned back.
- Redstone outputs and ME links keep working while a network is unpowered (only item, fluid, chemical and kit moves
  stop). By design: redstone often switches the generator that would refill it, and ME links already draw AE2 power.
- A connector lifted by a contraption that never sets it down (the contraption destroyed) leaves its record behind,
  moving, with no devices (it costs no upkeep; it keeps its network from counting as empty).
- A network too large for the editor (well past 64 MiB of snapshot) can't be opened there: it says so.
- Admins can demote or remove other admins (only the owner is protected). Kept on purpose.
