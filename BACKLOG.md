# Backlog

What is left to do, ideas set aside for later, and known gaps chosen to leave for now. Move items out as they are done
or decided against.

## To check in game

- Switches in a blueprint placed twice: flipping one placed blueprint's switch (dashboard or card) leaves the other's
  alone.

## Next

- Bug pass over everything since the last one: dashboard (full canvas), Switch and Display nodes (icon, color, Message
  style, `{value}`, steady sizing), notes on every node, the shared catalog browser and pick dialog, ColorField and
  ColorButton, the switch control on cards, cached snapshots when switching networks, claiming ownerless networks.
- README and design doc: alarms, Status Beacon, the network tool's Status mode, Sequencer, charts, wire layers,
  dashboard, Switch and Display, notes.

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
