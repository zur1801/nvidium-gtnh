# Nvidium for GTNH (Angelica 2.2)

Nvidium is an alternative terrain renderer that uses NVIDIA mesh shaders and GPU-side culling to draw very large
amounts of real (full detail, not LOD) terrain at playable framerates. This is a port for
**GT New Horizons 2.9 / Minecraft 1.7.10**, built on top of [Angelica](https://github.com/GTNewHorizons/Angelica) 2.2.x.

It is based on [ohentis' 1.7.10 port](https://github.com/ohentis/nvidium), which in turn ports
[Cortex's Nvidium](https://github.com/MCRcortex/nvidium). Expect rough edges.

## What this version adds

- Works with Angelica 2.2.19 (Celeritas 2.5.11), the renderer shipped with GTNH 2.9.
- Render distance slider raised from 32 up to 256 chunks (`max_render_distance` in `config/nvidium.cfg`).
- **Mesh cache**: built terrain is saved to `.minecraft/nvidium-cache/` so it shows up almost instantly when you
  rejoin a server instead of being rebuilt chunk by chunk (`mesh_cache` option).
- When the GPU runs out of memory for terrain, the farthest terrain is dropped first instead of leaving holes.
- Textured Distant Horizons LODs with shader packs (ports the upstream Iris `dhBlockAtlas` support to Angelica).
  Only active when Distant Horizons is installed.
- A block whose renderer crashes while meshing is skipped instead of taking down the whole renderer.
- Fixes for invisible entities, distant terrain flicker and several crashes on current Angelica.

## Requirements

- An NVIDIA GPU with mesh shader support: GTX 16xx / RTX 20xx or newer.
- GT New Horizons 2.9.x with Angelica 2.2.19 and lwjgl3ify 3.x (Java 17+).

Nvidium switches itself off while a shader pack is active; Angelica then renders terrain as usual.

## Installation

1. Download the jar from the [Releases](../../releases) page.
2. Put it in your instance's `mods` folder.
3. Start the game. Nvidium has its own page in Video Settings.

### Recommended companions

- [Bobbert](https://github.com/Onyxmn/Bobbert/releases) (Onyxmn's fork, which fixes Angelica 2.2) keeps chunks from
  beyond the server's view distance cached on disk. Set `noBlockEntities=false` in `config/bobbert.cfg`, otherwise
  blocks such as Forestry logs and GregTech machines can render incorrectly.
- [Distant Horizons for 1.7.10](https://github.com/DarkShadow44/DistantHorizonsStandalone/releases) draws LODs beyond
  your render distance.

## Choosing a render distance

Nvidium keeps all terrain on the GPU. As a rough guide it needs about 0.1 MB of VRAM per chunk column, so render
distance 128 needs roughly 6 GB of VRAM for terrain and 256 roughly 25 GB. On a 12 GB card, 128 is a sensible maximum;
use Distant Horizons for anything farther.

## Building

```
./gradlew build
```

The jar ends up in `build/libs/` (use the one without `-dev`, `-sources` or `-api` in its name). The first build
downloads Minecraft, Forge and dependencies and takes a few minutes. Java 25 is used to run Gradle.

## Credits

- [Cortex (MCRcortex)](https://github.com/MCRcortex/nvidium): original Nvidium
- [ohentis](https://github.com/ohentis/nvidium): 1.7.10 / Angelica port
- [GTNewHorizons](https://github.com/GTNewHorizons/Angelica): Angelica

## License

[LGPL-3.0](LICENSE.txt), like upstream Nvidium.
