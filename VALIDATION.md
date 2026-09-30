# Validation

The shim is tested as a separate production JAR against the unmodified official
Chalk **3.2.0+26.2** release and the independently built Chalk **3.2.1+26.3** port.
The matching Polymer Bundled versions are **0.17.5+26.2** and **0.18.2+26.3**.
Original dependency hashes and the tested shim hashes are recorded by the QA
harness. Original mod JARs are not patched or bundled.

## Release acceptance: 2026-09-30

Every acceptance record below identifies the exact shipped JAR hash. Both JARs
also rebuilt byte-for-byte identically from a clean source checkout.
Portable results are in [qa/evidence/1.0.0.json](qa/evidence/1.0.0.json).

| Check | Minecraft 26.2 | Minecraft 26.3 |
|---|---|---|
| No-Chalk client gameplay and actual normal/glow crafting | Passed | Passed |
| Native Chalk client gameplay, crafting, and raw Creative-tab items | Passed | Passed |
| Unmodified vanilla client connection and item/chunk delivery | Passed | Passed |
| Startup/reload, all 108 white mark states | Passed | Passed |
| Colorful Addon 2.1.1, all 1,728 mark states | Passed | Passed |
| Full restart and 108 restored display holders | Passed | Passed |
| Spawn-protection boundary denial and allowed-action controls | Passed | Passed |
| Required pack HTTP delivery, acceptance, download hash, and reload | Local pack tested | Passed |

Each full client matrix exercises 18 original placements and 18 redraws across
both chalk types and nine regions, erasure, Creative pick-block, and crafting
both recipes through actual inventory clicks. Native clients also send a raw
Chalk stack from their local item registry through the Creative inventory packet.
The colorful server cases each perform 1,728 placements at startup and another
1,728 after datapack reload. Static pack checks validate 518 JSON files, 384
mark model definitions, and all 1,728 source transforms per colorful pack.

The protection regression sends support-face packets at a mark just inside the
spawn boundary, with its support just outside. Non-operators are denied;
operators and unprotected interactions succeed. The earlier implementation fails
this same test, providing a negative control. This focused regression uses the
actual Minecraft packet handler with a synthetic server player/transport.

## What the checks cover

- Dedicated-server startup and datapack reload, original chalk/glow recipes,
  configuration, 64-use durability, collision-free marks, targeting outlines,
  support removal, and every combination of six faces and nine click regions.
- Connected client gameplay with and without Chalk: normal/glow placement,
  orientation, inventory synchronization, durability consumption, redrawing,
  erasing while retaining the support, and Creative pick-block. Client gameplay
  uses ordinary Minecraft interaction packets; the test fixture has no Chalk
  imports on clients without Chalk.
- Original artwork in the generated pack: JSON parsing, model/texture references,
  tint values, 108 white-only states, exact blockstate rotations, and upstream
  artwork license. The Colorful Addon expands this to 1,728 mark states.
- In-game screenshot inspection of normal and glow marks, plus client assertions
  for one item display per mark and ordinary versus full-bright display metadata.
  The display's extra vanilla item-renderer rotation is explicitly cancelled.
- Full server stop/restart with 108 saved mark states: original IDs, facing,
  orientation, supporting blocks, and reattached Polymer display holders.
- A separately launched unmodified Minecraft client, using `net.minecraft.client.main.Main`
  with no Fabric loader or client mods, connects, loads the pack, and receives
  chalk items and mark chunks. This smoke test is separate from the automated
  interaction fixtures, which use Fabric API solely for test automation.

## Reproduce

See [qa/README.md](qa/README.md) for the client/server matrix. Additional checks:

```sh
python3 qa/persistence.py --version 26.2
python3 qa/persistence.py --version 26.3
python3 qa/vanilla_connect.py --version 26.2
python3 qa/vanilla_connect.py --version 26.3
python3 qa/protection.py --version 26.2
python3 qa/protection.py --version 26.3
python3 qa/validate_pack.py --pack /path/to/resource_pack.zip --chalk /path/to/chalk.jar --shim /path/to/chalk-polymer-compat.jar
```

These are isolated local tests with fresh worlds and offline test accounts.
Original client profiles and game worlds are not used. Production servers should
retain their usual authentication and security configuration. QA fixture JARs
are excluded from release artifacts.

## Limits

All 54 orientations per chalk variant receive server and resource-transform
checks; actual client interaction tests target one wall face, and screenshots
sample center marks. This is not a claim of visual inspection of every
color/orientation, every third-party protection mod, or every rendering mod.
Vanilla clients need the server pack to see the artwork. They do not receive
Chalk's client configuration screen; the server particle setting is used.
