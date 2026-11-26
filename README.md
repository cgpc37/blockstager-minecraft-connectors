# Blockstager Studio — Minecraft Connectors (MIT)

Free, open-source Minecraft connectors for **Blockstager Studio** using the local authenticated WebSocket protocol `blockstager.proto/1.2`.

> **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**

## Included Connectors

| Connector | Target | Folder | License |
| --- | --- | --- | --- |
| **Blockstager Studio Client Mod** (`BlockstagerStudioFabric.jar`) | Minecraft 1.21+ · Fabric Loader 0.16+ · Java 21 | `minecraft-fabric-mod/` | MIT |
| **Blockstager Studio Paper Plugin** (`BlockstagerStudioPlugin.jar`) | Paper / Spigot 1.21+ · Java 21 | `minecraft-plugin/` | MIT |

## Build From Source (JDK 21, zero Gradle required)

```powershell
powershell -ExecutionPolicy Bypass -File tools/fabric-build/build.ps1
powershell -ExecutionPolicy Bypass -File tools/plugin-build/build.ps1
```

Both artifacts contain **zero** Mojang/Minecraft classes or assets and link strictly against public APIs at runtime.

## AI Disclosure

AI coding and documentation tools were used to assist in drafting, refactoring, and documenting portions of the Java source code, self-tests, and documentation in this repository. Project architecture, protocol design (`blockstager.proto/1.2`), security controls, verification, and ongoing maintenance are directed by the human maintainer. Neither connector includes AI-generated art/assets nor runs any generative AI functionality at runtime.
