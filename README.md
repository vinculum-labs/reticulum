# Reticulum

A logistics mod for Minecraft 1.21.1 (NeoForge). Cables and connectors join machines into a network, and you draw
what moves between them — items, fluids, energy, Mekanism chemicals, redstone and AE2 channels — as flow charts in a
web editor that opens in your browser.

- **One network, many flows.** A connector touches up to six machines. Every flow runs through the same cables; no
  separate pipes per resource and no per-side settings on the block.
- **Flows are drawn, not configured.** Wire a chest to a furnace to a chest. Add a filter, a splitter or a rate limit
  in between. Gate a sheet on redstone, a timer or how full a tank is.
- **Built for big bases.** A network with 1,024 connectors and 2,048 routes ticks in about half a millisecond.

## Getting started

1. Craft a **Network Controller**, some **Cable** and a few **Connectors**.
2. Place the controller, run cable from it, and put a connector next to each machine you want on the network.
   Anything a connector touches that holds items, fluids, energy or chemicals (or takes or gives redstone, or is an
   AE2 cable or machine) becomes a **device**.
3. Right-click the controller and press **Open editor**. Your browser opens the editor for that network.
4. Make a **sheet**, drag two devices onto it from the device list, and wire the output of one to the input of the
   other. The flow runs as soon as the wire is there.

The controller's screen also shows the network at a glance: its sheets (with a switch for each), power, overflow and
the redstone condition.

## Blocks and items

| | What it does |
|---|---|
| **Network Controller** | The heart of a network. One per network. Holds its energy and its antenna. |
| **Cable** | Joins network blocks. Cable without a controller that touches a network joins it; two networks that each have a controller stay separate where they touch. |
| **Connector** | Joins the machines next to it to the network. Can be named, and so can each device face. |
| **Network Tool** | Three modes (sneak + scroll to switch): **Configure** — right-click a joint to connect or disconnect it, or a device side to attach or detach it. **Link** — copy a network from a controller or connector (sneak + right-click), bind a wireless connector to it (right-click), or pick one from a list (right-click the air). **Identify** — light up a connector or device so you can find it (sneak for one face). |
| **Antenna** (tiers 1–3) | Installed in the controller (right-click it; take it out with the tool in Configure mode, sneak + right-click). Lets connectors that no cable reaches join the network: tier 1 within 128 blocks, tier 2 anywhere in the dimension, tier 3 in every dimension. |
| **Facade** | Makes a cable or connector look like any full block. Crafted blank, eight at a time. Right-click with a stack to pick its block from a searchable list of every block — owned or not — or drag one in from JEI; sneak + right-click a block in the world to copy it exactly, rotation included. Right-click a cable or connector to put one on; take it off with the tool in Configure mode (sneak + right-click). While holding the tool, faced cables show through as outlines. |

## The editor

The editor runs on your own computer, served by the game client, and only accepts this machine by default. Open it
from the controller's screen. One link logs you in; it stays logged in for 90 days unless you
turn that off in the client config.

### Sheets, folders and sub-sheets

A network has any number of **sheets**, each a flow chart. Sheets can sit in folders, and each sheet and folder can
be switched off, given a run interval and a priority, or gated on redstone. A sheet marked as a **sub-sheet** can be
used as a single node in other sheets, with **Sheet input** and **Sheet output** nodes as its pins.

Copy a sheet as JSON and import it into this world or another. Devices are matched by position; ones that don't
exist show as offline until you pick local ones for them.

### Nodes

| Node | |
|---|---|
| **Device** | A machine. Its inputs are fed and its outputs are emptied, per resource and per face. Redstone and ME are pins too: read a device's redstone, drive it, or link it to AE2. |
| **Filter** | Sends matches one way and the rest the other way. |
| **Rate limit** | Caps how much passes per tick or per second. |
| **Splitter** | Shares between its outputs: round robin, in order, by weight, or nearest first. |
| **Kit** | Moves a set of items all together or not at all (a recipe's worth, for example). |
| **Level sensor** | How much of a resource a device holds. |
| **Compare, Logic, Timer, Pulse, Latch, Counter, Delay** | Redstone-style logic on signals of 0–15. |
| **Publish / Network signal** | Share a signal by name between sheets. |

### Filters

Every filter box has a **Pick…** button that opens a browser of every item, fluid and chemical in the game, or of
what a device holds right now. Double-click something to add it, or pick one of its tags or its mod instead;
alt-click or right-click excludes. The text stays editable.

Filters are short text expressions:

```
minecraft:*_ore            ids, with * as a wildcard
#c:ingots                  a tag
mod:mekanism               everything from a mod
name:"ancient"             display name contains (or /regex/)
ench:minecraft:mending     an enchantment (ench:sharpness>=4, ench:any)
dura:<25%                  durability
is:food  is:fuel  is:damaged  is:enchanted  is:stackable  is:smeltable
in:sink                    only what the destination already holds
@name                      a saved filter template
```

Terms next to each other must all match; `|` means or, `!` means not, and parentheses group. Fluids understand
`id`, `tag`, `mod`, `name`, `temp` and `is:gaseous`. Energy has no types and ignores filters.

### Overflow

Items, fluids and chemicals that were taken out but couldn't be put in land in the network's overflow buffer and are
delivered first next time. Flows pause while the buffer is full. The dashboard and the controller screen show what
is in it.

### Dashboard

The dashboard shows what the network costs per tick, its busiest flows, flows that are waiting (and why), sheets and
folders that are switched off, and what is in the overflow buffer.

## Redstone

Every device a connector touches can be read as a redstone source and driven as a redstone target, so a lever on one
connector can switch a lamp on another: put both on a sheet and wire the lever's redstone output to the lamp's
redstone input. A device's comparator output can be read the same way.

Sheets, folders and the whole network can be gated on redstone: **High** runs while powered, **Low** while not, and
**Pulse** once per rising edge.

## Wireless

A controller with an antenna reaches connectors that no cable joins to it. Bind such a connector with the network tool
in Link mode. Connectors outside the antenna's reach, and the devices only they reach, go offline until the antenna
covers them again.

## Power

Networks are free to run by default. Servers that want a cost can pick the **BALANCED** preset or set their own costs
in the server config: a base cost per network, per connector, per wireless connector (more for distance and for other
dimensions), and per item operation. The controller holds the FE; feed it with any FE cable or put a generator next
to it. An unpowered network pauses its item, fluid and chemical flows, while energy flows keep running so a network
can power itself back up.

## Identify

Lost a machine? Press **ID** next to any device, face or connector — in the editor or in game — or right-click it with
the tool in Identify mode. It is outlined in color through walls, with a beam when it is far away, and listed on
screen until you turn it off.

## Look-at info

With [Jade](https://www.curseforge.com/minecraft/mc-mods/jade) or
[The One Probe](https://www.curseforge.com/minecraft/mc-mods/the-one-probe) installed, looking at a network block or
device shows its network, names, wireless reach, redstone and whether it is identified. Without either, the same
lines appear under the crosshair while you hold the network tool.

## Security

Every network has an owner and an access mode: **Public**, **Password** or **Private**. Members have roles — viewer,
user, editor or admin — and each role can do everything the ones below it can. Blocks of private networks can only be
broken by members (configurable), and operators can bypass security, with every bypass logged.

```
/reticulum list
/reticulum join <network> <password>
/reticulum member <network> add <player> <role>
/reticulum member <network> remove <player>
/reticulum merge <from> <into>
/reticulum delete <network>
```

## Mod support

- **Mekanism:** chemicals (gases, infusion types, pigments and slurries) flow like fluids, with their own filters.
- **Applied Energistics 2:** a connector next to AE2 cable or machines links them to other AE2 devices on the network,
  carrying channels between AE2 networks with no cable between them. The editor shows channel use.
- **Jade** and **The One Probe:** look-at info.

None of these are required.

## Configuration

The server config (`reticulum-server.toml`, per world) has sections for security, wireless range, power costs,
throughput and overflow limits, Identify and AE2. The client config (`reticulum-client.toml`) covers the web editor's
address, port and login, and how identified blocks are drawn. Both can also be edited from the Mods screen.

## Building from source

Requires Java 21. The build downloads its own Node.js for the web editor.

```
./gradlew build            # builds the web editor and the mod jar
./gradlew runClient        # a dev client with Mekanism and AE2
./gradlew runGameTestServer
```

The web editor lives in `web/` (Svelte 5 and Vite). The dev client serves it straight from `web/dist`, so running
`npm run dev` there (a watching build) shows changes on a browser refresh.

## License

MIT — see [LICENSE](LICENSE). Copyright © LeadAssimilator.
