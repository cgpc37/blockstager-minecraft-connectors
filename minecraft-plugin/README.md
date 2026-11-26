# Blockstager Studio Plugin (Paper / Spigot 1.21+ — Java 21)

Free, open-source (MIT) **server-side** Paper/Spigot 1.21+ plugin connecting your Minecraft server to **Blockstager Studio** over the authenticated bidirectional local WebSocket protocol (`blockstager.proto/1.2`).

> **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**

---

## English

### Overview & Security
Built for streamers hosting their own **Paper / Spigot 1.21+** server who want both gameplay-to-avatar reactivity and safe, rate-limited stream-to-world actions:

- **Compatibility:** Paper / Spigot **1.21+** (`api-version: '1.21'`), Java 21.
- **Public API only:** uses strictly public Bukkit/Paper/Adventure APIs (`org.bukkit.*`, `org.bukkit.Registry`, `org.bukkit.profile.PlayerTextures`, `net.kyori.adventure.*`) — zero NMS, zero bundled Mojang code or assets.
- **Automatic reconnection:** exponential backoff with jitter (`1 s` – `30 s`), stopping cleanly on close code `4401` (invalid token).
- **Bidirectional bridge (always executed on Bukkit's main thread):**
  - **Minecraft → Blockstager Studio:** `trigger.fire`, `minecraft.event`, `skin.set`, `item.equip`, and `minecraft.action.result`.
  - **Blockstager Studio → Minecraft:** safe, allowlist/blocklist-guarded `minecraft.action` (`spawn_mob`, `give_item`, `potion_effect` / `apply_effect`, `world_effect` / `world_fx`) with per-action cooldowns and strict caps.

### Installation
1. Download `BlockstagerStudioPlugin.jar` from **Modrinth** or **GitHub Releases**.
2. Place the `.jar` file in your Paper 1.21+ server's `plugins/` folder.
3. Start the server once to generate `plugins/BlockstagerStudioPlugin/config.yml`.
4. Open **Blockstager Studio → Integrations → Minecraft**, copy your local WebSocket URL (`ws://127.0.0.1:28490/ws`) and Bearer token (`bs_tok_...`), paste them into `config.yml`, and run `/bs reload`.

### Key Configuration (`config.yml`)
```yaml
wsUrl: "ws://127.0.0.1:28490/ws"
token: ""
targetPlayer: ""
syncSkinOnJoin: true

allowRemoteActions: true
actionCooldownMs: 1000
maxMobCount: 10
maxSpawnRadius: 16
maxItemAmount: 64
maxEffectDurationSec: 120
maxEffectAmplifier: 4
```
Default `allowedEntities` and `blockedEntities`/`blockedItems` block destructive bosses and admin blocks (such as `wither`, `ender_dragon`, `warden`, `tnt`, `bedrock`, `command_block`, `barrier`) out of the box.

### Commands (`/bs`, permission `blockstager.use`, default OP)
| Command | Description |
| --- | --- |
| `/bs status` | Shows connection status, endpoint, reconnect count, latency, and remote action policy. |
| `/bs connect` | Forces an immediate reconnect to Blockstager Studio. |
| `/bs trigger <slug> [k=v ...]` | Sends a typed `trigger.fire` command to Blockstager Studio Nodes / Rules. |
| `/bs skin sync [player]` | Re-syncs the player's official signed skin texture (`skin.set`). |
| `/bs actions status\|on\|off` | Inspects limits or toggles `allowRemoteActions` live (persisted to `config.yml`). |
| `/bs test-action <type> [value]` | Runs a safe main-thread test action (`spawn_mob`, `give_item`, `potion_effect`, `world_effect`). |
| `/bs reload` | Hot-reloads `config.yml` and reconnects if `wsUrl` or `token` changed. |

### Emitted Events (`source: "minecraft.paper"`)
- `player.join` (with `skinUrl`, `skinModel` — triggers `skin.set` when `syncSkinOnJoin: true`)
- `player.damage`, `player.death`, `player.advancement`, `player.totem_pop`, `player.item_consume`, `player.dimension_change`, `player.held_item_change`

### Build From Source (JDK 21, no Gradle/Maven)
```powershell
powershell -ExecutionPolicy Bypass -File tools/plugin-build/build.ps1
java -cp "tools/plugin-build/build/selftest;tools/plugin-build/lib/gson-2.11.0.jar" dev.blockstager.plugin.SelfTest --self-contained
```

### AI Disclosure
- **Development assistance:** AI coding and documentation tools were used to assist in drafting, refactoring, and documenting portions of the Java source code, unit tests, and this README.
- **Human authorship & maintenance:** Project architecture, the `blockstager.proto/1.2` protocol design, allowlist/blocklist security controls, main-thread execution guarantees, testing, and ongoing maintenance are directed and verified by the human maintainer.
- **No AI assets or runtime AI:** This plugin contains **zero** AI-generated textures, models, icons, or promotional art, and performs **no** generative AI / LLM functionality at runtime.

---

## Español

### Resumen y seguridad
Plugin oficial de servidor de código abierto (MIT) para **Paper / Spigot 1.21+ (Java 21)** que conecta tu servidor con **Blockstager Studio** mediante el protocolo local bidireccional `blockstager.proto/1.2`:

- **API pública estricta:** sin NMS ni código/assets de Mojang.
- **Acciones remotas seguras:** todas las acciones `minecraft.action` (`spawn_mob`, `give_item`, `potion_effect`, `world_effect`) se validan contra listas blancas/negras, topes y enfriamiento (`actionCooldownMs`) y se ejecutan en el hilo principal de Bukkit con confirmación `minecraft.action.result`.
- **Instalación:** copia `BlockstagerStudioPlugin.jar` en `plugins/`, inicia el servidor una vez para generar `plugins/BlockstagerStudioPlugin/config.yml`, pega `wsUrl` y `token` desde **Blockstager Studio → Integraciones → Minecraft**, y ejecuta `/bs reload`.
- **Comandos (`/bs`, permiso `blockstager.use`):** `/bs status`, `/bs connect`, `/bs trigger <slug>`, `/bs skin sync [jugador]`, `/bs actions status|on|off`, `/bs test-action <tipo> [valor]`, `/bs reload`.
- **Transparencia sobre uso de IA (AI Disclosure):** se utilizaron herramientas de asistencia por IA para acelerar la redacción, refactorización y documentación de partes del código Java, pruebas y este README. La arquitectura, el diseño del protocolo, los controles de seguridad, la verificación y el mantenimiento son responsabilidad del autor humano. El plugin no incluye assets ni arte generados por IA y no ejecuta ninguna función de IA generativa.
