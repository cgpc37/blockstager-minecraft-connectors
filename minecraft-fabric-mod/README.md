# Blockstager Studio Client Mod (Fabric 1.21+ — Java 21)

Free, open-source (MIT) lightweight **client-side** Fabric mod connecting your local Minecraft 1.21+ game state to **Blockstager Studio** over the authenticated local WebSocket protocol (`blockstager.proto/1.2`).

> **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**

---

## English

### Overview & Public-Server Safety
Designed specifically for streamers playing on **public multiplayer servers** (where you do not control the server) as well as **Singleplayer** worlds:

- **Compatibility:** Minecraft **1.21+** (`1.21` – `1.21.5+`), **Fabric Loader 0.16+**, **Fabric API**, Java 21.
- **100% client-side & safe on public servers:**
  - Reads only your own local player state visible on the client (no altered packets, no competitive advantages, no server-side installation required).
  - In-world actions (`spawn_mob`, `give_item`, `potion_effect`, weather/time) run **only in Singleplayer** (integrated server) or if you explicitly enable `allowMultiplayerOpActions: true` while holding OP level `>= 2`. On public servers they are safely rejected with a `minecraft.action.result` acknowledgement.
  - Local cosmetic effects (`title_overlay`, `sound_bell`, `sound_levelup`, `play_sound`) render/play strictly on your own client with zero packets sent to the server.
- **Zero Mojang assets or classes in the JAR:** links dynamically via `FabricLoader.getMappingResolver()`.
- **Automatic reconnection:** exponential backoff with jitter (`1 s` – `30 s`), stopping cleanly on HTTP/WS close code `4401` (invalid token).

### Installation
1. Install **Fabric Loader 0.16+** and **Fabric API** for your Minecraft `1.21+` version.
2. Download `BlockstagerStudioFabric.jar` from **Modrinth** or **GitHub Releases**.
3. Place the `.jar` file in `.minecraft/mods/`.
4. Launch Minecraft once to generate `.minecraft/config/blockstager-fabric.json`.
5. Open **Blockstager Studio → Integrations → Minecraft**, copy your local address (`ws://127.0.0.1:28490/ws`) and Bearer token (`bs_tok_...`), paste them into `blockstager-fabric.json`, and run `/bs reload` in-game.

### Configuration (`config/blockstager-fabric.json`)
```json
{
  "wsUrl": "ws://127.0.0.1:28490/ws",
  "token": "",
  "autoConnect": true,
  "syncSkinOnJoin": true,
  "sendMovementEvents": true,
  "allowRemoteActions": true,
  "allowClientCosmeticEffects": true,
  "allowMultiplayerOpActions": false,
  "actionCooldownMs": 1000,
  "maxMobCount": 10,
  "maxSpawnRadius": 16.0,
  "maxItemAmount": 64,
  "maxEffectDurationSec": 120,
  "maxEffectAmplifier": 4
}
```

### Local Client Commands (`/bs` and `/bsc`)
All commands run **100% locally** and never send chat messages to public servers:

| Command | Description |
| --- | --- |
| `/bs status` | Shows connection status, mode (Singleplayer / Public multiplayer), actions policy, and latency. |
| `/bs connect` | Forces an immediate reconnect to Blockstager Studio. |
| `/bs reload` | Hot-reloads `config/blockstager-fabric.json`. |
| `/bs trigger <slug>` | Fires `trigger.fire` directly into Blockstager Studio Nodes / Rules. |
| `/bs skin sync` | Re-syncs your active skin (`skin.set`). |
| `/bs actions status\|on\|off` | Inspects limits or toggles incoming actions live. |
| `/bs test-action <type> [value]` | Runs a safe local test (`world_effect`, `spawn_mob`, `give_item`, `potion_effect`). |

*(Use the `/bsc` alias if a Paper server already registers `/bs`.)*

### Emitted Events (`source: "minecraft.fabric"`)
- `player.join` (with `skinUrl`, `skinModel`, `mode`, `dimension` — triggers `skin.set` if `syncSkinOnJoin: true`)
- `player.damage`, `player.death`, `player.totem_pop`, `player.item_consume`, `player.dimension_change`, `player.held_item_change`
- `player.sneak`, `player.sprint`, `player.jump` (when `sendMovementEvents: true`)

### Build From Source (JDK 21, no Gradle)
```powershell
powershell -ExecutionPolicy Bypass -File tools/fabric-build/build.ps1
java -cp "tools/fabric-build/build/selftest;tools/fabric-build/lib/gson-2.11.0.jar" dev.blockstager.fabric.FabricSelfTest --self-contained
```

### AI Disclosure
- **Development assistance:** AI coding and documentation tools were used to assist in drafting, refactoring, and documenting portions of the Java source code, unit tests, and this README.
- **Human authorship & maintenance:** Project architecture, the `blockstager.proto/1.2` protocol design, security boundaries (public-server safety, local-only commands, zero Mojang code), testing, and ongoing maintenance are directed and verified by the human maintainer.
- **No AI assets or runtime AI:** This mod contains **zero** AI-generated textures, models, icons, or promotional art, and performs **no** generative AI / LLM functionality at runtime.

---

## Español

### Resumen y seguridad en servidores públicos
Mod cliente ligero de código abierto (MIT) para conectar **Minecraft Fabric 1.21+** con **Blockstager Studio** mediante el protocolo local `blockstager.proto/1.2`:

- **Compatibilidad:** Minecraft **1.21+**, **Fabric Loader 0.16+**, **Fabric API**, Java 21.
- **100 % cliente y seguro en servidores públicos:** lee únicamente tu estado local visible en el cliente (cero paquetes alterados). Las acciones en el mundo solo se ejecutan en **Un jugador** (o con `allowMultiplayerOpActions: true` y OP `>= 2`). Los efectos cosméticos locales se muestran únicamente en tu pantalla.
- **Instalación:** coloca `BlockstagerStudioFabric.jar` en `.minecraft/mods/`, inicia el juego una vez para crear `.minecraft/config/blockstager-fabric.json`, pega tu dirección local y token desde **Blockstager Studio → Integraciones → Minecraft**, y ejecuta `/bs reload` dentro del juego.
- **Comandos locales:** `/bs status`, `/bs connect`, `/bs reload`, `/bs trigger <slug>`, `/bs skin sync`, `/bs actions status|on|off`, `/bs test-action <tipo> [valor]` (alias `/bsc`).
- **Transparencia sobre uso de IA (AI Disclosure):** se utilizaron herramientas de asistencia por IA para acelerar la redacción, refactorización y documentación de partes del código Java, pruebas y este README. La arquitectura, el diseño del protocolo, las reglas de seguridad, la verificación y el mantenimiento son responsabilidad del autor humano. El mod no incluye assets ni arte generados por IA y no ejecuta ninguna función de IA generativa.
