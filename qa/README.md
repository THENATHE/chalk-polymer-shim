# Reproducible Chalk shim verification

`run.py` compiles test-only fixtures, launches a fresh dedicated server with the
production JAR, then optionally connects a real Minecraft client. Fixture JARs
are never packaged in the shim. Runs bind only to localhost and use disposable
worlds under `build/qa/`; original dependency JAR SHA-256 hashes are audited before
and after. Existing worlds and launcher profiles are not modified.

Prerequisites: Python 3, Java 25 runtime, a compiler supporting `--release 25`, a
working graphical display for client tests, cached Minecraft/Fabric libraries
from building both versions, and Minecraft assets. The script performs no
network downloads. Pass `--assets` and `--java` to override the local defaults.
Place each version's original Chalk, Fabric API, Cloth Config, and bundled
Polymer JARs in `libs/26.2/` or `libs/26.3/`. The 26.3 Chalk port may also be at
`libs/chalk-3.2.1+26.3.jar`. `--mods` accepts another dependency directory.

```sh
python3 qa/run.py --version 26.2 --client unsupported
python3 qa/run.py --version 26.2 --client native
python3 qa/run.py --version 26.3 --client unsupported --autohost
python3 qa/run.py --version 26.3 --client native
python3 qa/run.py --version 26.2 --client none --colorful
python3 qa/run.py --version 26.3 --client none --colorful
```

`--shim` overrides the production JAR, `--build-only` only compiles fixtures, and
`--label` names a fresh run. Reusing an existing run label is rejected.
`--creative-only --client native` runs the native creative-inventory packet check
without repeating the client gameplay sequence. Library extraction and fixture
build directories are unique per process; fixture handshakes use atomic writes.

The server fixture checks both crafting recipes, configuration initialization,
64-use durability, and every registered mark variant across all six faces and
nine click regions. Placement, orientation, empty collision, targetable outline,
piston reaction, support survival, and automatic support removal are checked
both at startup and after datapack reload: 216 placements without the addon or
3,456 with all 16 colors. Generated resource-pack ZIP integrity, JSON validity,
artwork license, and generated mark item definitions are checked.

The client fixture contains no Chalk/shim imports. `unsupported` installs only
Fabric API and the test fixture; `native` also installs the unmodified original
Chalk and dependencies. Both connect through actual game networking, receive the
server inventory, and use the ordinary client game-mode methods to draw normal
and glow marks in all nine regions on a south-facing surface. Server assertions
verify original item/block identities, orientation, durability consumption,
redrawing, erasure without destroying the support, and creative pick. Both
normal and glow chalk are also crafted through real client inventory clicks:
ingredients enter the 2×2 grid, the synchronized output is shift-clicked into
inventory, and the server verifies the original item identity and zero damage. Client
assertions inspect synchronization, one virtual display, and ordinary versus
full-bright display metadata. Native clients also submit a fresh local Chalk
item through the actual Creative inventory packet, and the server verifies its
original Chalk identity and undamaged state.

By default the generated resource pack is installed as a selected local client
pack. With `--autohost`, Polymer serves the required pack on its real local HTTP
endpoint; the client fixture enables server packs through Minecraft's ordinary
pack preference and download manager. The harness verifies the received pack
SHA-1 in the client download log. This validates hosting, acceptance, download,
and resource reload. The fixture presses Proceed only on the resource-pack
confirmation screen; it does not approve unrelated dialogs. Screenshots of
normal and glow center marks are saved in each client's `screenshots/` directory. A client containing
Fabric API and the test fixture is not an unmodified vanilla client. Logs retain
any offline-account Realms or local audio/narrator warnings separately from
fixture outcomes. The checks are automated, not a claim of manual visual review
of every mark orientation.
