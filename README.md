> **Archived on 2026-10-04.** Future combined Minecraft 26.3 development continues in [Vanilla++ Quality of Life Suite](https://github.com/THENATHE/vanilla-plusplus-quality-of-life-suite). Existing standalone releases and source remain available here.
>
> This standalone project also retains Minecraft 26.2 support. The suite targets Minecraft 26.3 and is not a replacement for the older-version installation.

# Chalk Polymer Shim

**Use Chalk on a Fabric server with vanilla or modded clients.**

Chalk Polymer Shim is an unofficial, separate, server-only compatibility layer. It makes Chalk's drawing, arrows, crosses, glow marks, crafting, durability, redrawing, erasing, and pick-block available to players without a client mod. The server keeps the original Chalk items and blocks, including saved marks. The original Chalk and Polymer JARs are required separately and are never modified or bundled.

[Download releases](https://github.com/THENATHE/chalk-polymer-shim/releases) · [Validation](VALIDATION.md) · [Original Chalk](https://modrinth.com/mod/chalk) · [26.3 Chalk port](https://github.com/THENATHE/Chalk/tree/port/fabric-26.3)

## Compatibility

Use the artifact matching your Minecraft version. The public Chalk release and the 26.3 port have separate builds:

| Minecraft | Chalk | Polymer Bundled | Shim artifact |
|---|---|---|---|
| 26.2 | Official **3.2.0+26.2** | **0.17.5+26.2** | `chalk-polymer-compat-1.0.0+26.2.jar` |
| 26.3 | THENATHE port **3.2.1+26.3** | **0.18.2+26.3** | `chalk-polymer-compat-1.0.0+26.3.jar` |

Both use Java 25 or newer, Fabric Loader 0.19.5 or newer, and Fabric API 0.161.0 for the matching Minecraft version. Chalk also requires Cloth Config: 26.2.155 on Minecraft 26.2 or 26.3.159 on Minecraft 26.3. Internal Chalk and Polymer versions are pinned because the shim integrates with their APIs.

## Install

1. Install the matching **Chalk**, **Fabric API**, **Cloth Config**, **Polymer Bundled**, and **shim** JARs in the server's `mods` directory.
2. Start once to create Polymer's configuration, then stop the server.
3. Enable Polymer resource-pack hosting in `config/polymer/auto-host.json` by setting `"enabled": true` and `"required": true`. Preserve other existing settings. Restart to generate and offer the combined pack.
4. Accept the server resource pack when joining. No client mod or client-side shim is needed.

**The server resource pack is required for visible chalk items and marks.** The shim marks its resources as required; configure a working Polymer host before players join. Use your server's combined generated pack so other Polymer mods' resources are included. See [Polymer hosting documentation](https://polymer.pb4.eu/latest/user/resource-pack-hosting/) for alternative hosting.

Do not install the shim on clients. Players may keep the original Chalk and its normal client dependencies installed. Chalk does not advertise a client network channel, so the server uses the same complete Polymer presentation for every connection instead of guessing which clients have Chalk.

## Features and behavior

- Right-click a solid block with chalk. Its nine click regions produce eight arrow directions and a central cross, on floors, walls, and ceilings.
- Right-click an existing mark with chalk to redraw or replace it. Left-click to erase it. The supporting block remains intact.
- Pick-block on a marked surface selects the appropriate chalk through normal server pick-item rules, including Creative inventory creation and Survival inventory selection.
- Normal and glow chalk retain their original recipes, 64-use durability, sounds, particle configuration, and Creative behavior. Glow marks remain fully bright in darkness.
- Removing a supporting block removes its mark. Marks remain real Chalk blocks in world saves and survive ordinary chunk loading and server restarts.
- The optional **Chalk: Colorful Addon** enables the original 16 colors. The shim derives colors and artwork from Chalk's registered variants.

The shim sends vanilla item-display packets for marks, using the original geometry, textures, colors, and blockstate rotations. Client-visible air keeps marks collision-free. Server targeting translates clicks on the supporting face to the real mark, then retains normal reach, game-mode, and protection checks. Chalk items appear through Polymer's item translation while inventory and saved data retain their original identities.

Chalk's own client rendering is replaced by this shared presentation; this does not expose its client configuration screen to vanilla clients. The server's `EmitParticles` setting controls the shim's drawing and erasing particles. Other mods with custom protection hooks or client rendering changes require their own compatibility testing.

## Verification

Both Minecraft versions passed dedicated-server, no-Chalk client, native Chalk client, unmodified vanilla connection, crafting, Creative inventory, protection, and restart tests. The optional Colorful Addon passed all 1,728 mark states. See [VALIDATION.md](VALIDATION.md) for exact coverage, artifact hashes, and limits.

## Build

Use JDK 25 and Python 3. Public dependency downloads are pinned by SHA-512 in `dependencies.lock.json`:

```sh
python3 fetch-dependencies.py --mc 26.2
./gradlew build -Pmc=26.2
```

For 26.3, first build the separately maintained [Chalk port](https://github.com/THENATHE/Chalk/tree/port/fabric-26.3), source commit `dfb461ef9eb6f7bf5188570594c3bd21171c2391`, with its own `./gradlew build`. Then:

```sh
python3 fetch-dependencies.py --mc 26.3 --chalk-port /path/to/chalk-3.2.1+26.3.jar
./gradlew build -Pmc=26.3
```

The installable outputs are under `build/26.2/libs/` and `build/26.3/libs/`. Files ending in `-sources.jar` are for development. On Windows, use `gradlew.bat`. The 26.3 port's test-artifact hash is recorded for provenance; a locally rebuilt port is accepted by mod ID and declared version because compiler differences can change its bytes.

## License and credits

The shim is [MIT licensed](LICENSE), copyright 2026 THENATHE. Chalk is by DaFuqs and mortuusars; Polymer is a separate upstream project by Patbox and contributors. Original Chalk artwork remains under its [upstream MIT notice](src/main/resources/licenses/chalk-LICENSE.txt), which is also placed in the generated pack. The pack generator repairs a missing comma in Chalk's Korean translation without changing the original mod archive.
