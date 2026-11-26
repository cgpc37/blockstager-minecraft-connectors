# Blockstager Studio Plugin (Paper / Spigot — Java 21)

Plugin oficial de servidor para conectar servidores de Minecraft (**Paper 1.21+**) con **Blockstager Studio** mediante el protocolo local bidireccional `blockstager.proto/1.2` sobre WebSocket (`java.net.http.WebSocket`).

- **Compatibilidad:** Paper 1.21+ (`api-version: '1.21'`), Java 21.
- **API pública estricta:** utiliza únicamente las APIs públicas de Bukkit/Paper (`org.bukkit.*`, `org.bukkit.Registry`, `org.bukkit.profile.PlayerTextures` y Adventure `net.kyori.adventure.*`), sin NMS ni dependencias internas dependientes de versión.
- **Reconexión automática:** backoff exponencial con *jitter* entre `1 s` y `30 s`. Si el servidor rechaza el token con código `4401`, detiene los reintentos automáticamente hasta actualizar `config.yml`.
- **Puente bidireccional completo:**
  - **Minecraft → Blockstager Studio:** `trigger.fire`, `minecraft.event`, `skin.set`, `item.equip` y `minecraft.action.result`.
  - **Blockstager Studio → Minecraft:** recepción y ejecución segura en el hilo principal de Bukkit (`Bukkit.getScheduler().runTask(...)`) de `minecraft.action` (`spawn_mob`, `give_item`, `potion_effect` / `apply_effect`, `world_effect` / `world_fx`).

---

## Instalación

1. Descarga o compila `BlockstagerStudioPlugin.jar`.
2. Copia el archivo JAR en la carpeta `plugins/` de tu servidor Paper 1.21+.
3. Inicia el servidor una vez para generar `plugins/BlockstagerStudioPlugin/config.yml`.
4. Abre **Blockstager Studio → Integraciones** y copia tu token local (`bs_tok_...`) y la URL WebSocket (`ws://127.0.0.1:28490/ws`).
5. Edita `plugins/BlockstagerStudioPlugin/config.yml` y recarga con `/bs reload`.

---

## Configuración (`config.yml`)

```yaml
wsUrl: "ws://127.0.0.1:28490/ws"
token: ""
targetPlayer: ""
syncSkinOnJoin: true

# Controles de seguridad para acciones remotas (minecraft.action)
allowRemoteActions: true
actionCooldownMs: 1000
maxMobCount: 10
maxSpawnRadius: 16
maxItemAmount: 64
maxEffectDurationSec: 120
maxEffectAmplifier: 4

allowedEntities:
  - zombie
  - skeleton
  - creeper
  - pig
  - sheep
  - cow
  - chicken
  - wolf
  - cat
  - villager
  - slime
  - enderman
  - iron_golem
  - allay
  - fox
  - bee
  - axolotl
  - snow_golem
  - spider
  - phantom
  - llama
  - horse
  - rabbit
  - firework_rocket

blockedEntities:
  - wither
  - ender_dragon
  - warden
  - elder_guardian
  - tnt
  - primed_tnt
  - tnt_minecart
  - end_crystal

allowedItems: []

blockedItems:
  - bedrock
  - barrier
  - command_block
  - chain_command_block
  - repeating_command_block
  - command_block_minecart
  - structure_block
  - structure_void
  - jigsaw
  - debug_stick
  - light
  - spawner
  - end_portal_frame
  - reinforced_deepslate
  - knowledge_book
```

| Opción | Descripción |
| :--- | :--- |
| `wsUrl` | Endpoint WebSocket del servidor local de Blockstager Studio (por defecto `ws://127.0.0.1:28490/ws`). |
| `token` | Token Bearer generado por Blockstager Studio (`bs_tok_...`). Obligatorio para autenticarse. |
| `targetPlayer` | Filtro opcional por nombre de jugador (sin distinguir mayúsculas/minúsculas). También determina el jugador objetivo por defecto al ejecutar `minecraft.action`. |
| `syncSkinOnJoin` | Si es `true`, envía automáticamente `skin.set` al conectarse el jugador usando la URL de textura firmada de `PlayerTextures`. |
| `allowRemoteActions` | Interruptor maestro para permitir o bloquear todas las acciones remotas `minecraft.action` enviadas desde Blockstager Studio. Puede cambiarse en vivo con `/bs actions on\|off`. |
| `actionCooldownMs` | Enfriamiento mínimo anti-spam entre acciones remotas aceptadas (por defecto `1000`, equivalente a `1 s`; rango `0`–`60000`). |
| `maxMobCount` | Tope máximo de criaturas invocables por acción `spawn_mob` (por defecto `10`; rango `1`–`50`). |
| `maxSpawnRadius` | Radio máximo en bloques alrededor del jugador para `spawn_mob` (por defecto `16`; rango `1`–`32`). |
| `maxItemAmount` | Tope máximo de unidades entregables por acción `give_item` (por defecto `64`; rango `1`–`64`). |
| `maxEffectDurationSec` | Duración máxima en segundos para `potion_effect` / `apply_effect` (por defecto `120`; rango `1`–`600`). |
| `maxEffectAmplifier` | Amplificador máximo de Bukkit (`0` = nivel I, `1` = nivel II, …, por defecto `4`; rango `0`–`10`). |
| `allowedEntities` | Lista blanca de criaturas permitidas en `spawn_mob` (excluye jefes destructivos como `wither` o `ender_dragon` por defecto). |
| `blockedEntities` | Lista negra prioritaria de criaturas bloqueadas siempre por seguridad. |
| `allowedItems` | Lista blanca opcional de ítems para `give_item`. Si se deja vacía (`[]`), permite cualquier ítem válido que no esté en `blockedItems`. |
| `blockedItems` | Lista negra de ítems restringidos (como `bedrock`, `barrier` o `command_block`). |

---

## Comandos (`/bs`)

Permiso requerido: `blockstager.use` (asignado por defecto a operadores `op`).

| Comando | Descripción |
| :--- | :--- |
| `/bs status` | Muestra estado de conexión, autenticación, endpoint URL, contador de reconexiones, latencia y estado de acciones remotas. |
| `/bs connect` | Fuerza una reconexión inmediata hacia Blockstager Studio. |
| `/bs trigger <slug> [k=v ...]` | Envía `trigger.fire` con `command: <slug>` y argumentos tipados (números, booleanos y cadenas). Ejemplo: `/bs trigger backrooms intensity=2 dramatic=true`. |
| `/bs skin sync [jugador]` | Reenvía la skin actual del jugador (o del jugador especificado desde consola) mediante `skin.set`. |
| `/bs actions status\|on\|off` | Consulta los límites y estado actual del puente entrante o activa/desactiva al instante `allowRemoteActions` (persistido en `config.yml`). |
| `/bs test-action <tipo> [valor]` | Ejecuta una prueba local en el hilo principal (`spawn_mob`, `give_item`, `potion_effect`, `world_effect`) pasando por la política de seguridad y reportando `minecraft.action.result` si hay conexión activa. |
| `/bs reload` | Recarga `config.yml` en caliente (incluidos límites, listas y `allowRemoteActions`) y reconecta el cliente WebSocket si cambió `wsUrl` o `token`. |

---

## Acciones remotas entrantes (`minecraft.action`) y confirmación (`minecraft.action.result`)

Todas las acciones entrantes `type: "minecraft.action"` se validan contra la política de seguridad y se ejecutan **siempre en el hilo principal de Bukkit**:

1. **`spawn_mob`** — Invoca criaturas de `allowedEntities` (`entity`, `count`, `radiusBlocks` / `radius`, `targetPlayer` opcional) buscando bloques transitables seguros cerca del jugador.
2. **`give_item`** — Entrega ítems válidos fuera de `blockedItems` (`item`, `amount`, `targetPlayer` opcional) al inventario del jugador, soltando el sobrante al suelo de forma natural.
3. **`potion_effect` / `apply_effect`** — Aplica efectos canónicos (`speed`, `jump_boost`, `regeneration`, `levitation`, `slow_falling`, `blindness`, `nausea`, `glowing`, `resistance`, `strength`, etc.) con `durationSec` y `amplifier`.
4. **`world_effect` / `world_fx`** — Efectos seguros en el mundo (`lightning_effect` sin fuego ni daño, `title_overlay` / `title`, `knockback`, `play_sound`, `sound_bell`, `sound_levelup`, `firework`, `heart_particles`, `explosion_particles`, `weather_clear`, `weather_rain`, `time_day`, `time_night`).

Al finalizar cada acción (o al rechazarla), el plugin responde por el mismo WebSocket con un sobre `type: "minecraft.action.result"`:

```json
{
  "protocolVersion": "blockstager.proto/1.2",
  "id": "mc_a1b2c3d4",
  "type": "minecraft.action.result",
  "source": "minecraft.paper",
  "timestamp": 1764040000000,
  "payload": {
    "actionId": "uuid-de-la-accion",
    "requestId": "uuid-de-la-accion",
    "action": "give_item",
    "outcome": "ok",
    "detail": "Entregado 1× golden_apple a Chris.",
    "player": "Chris"
  }
}
```

Valores posibles de `outcome`: `ok`, `rejected`, `rate_limited`, `disabled`. Blockstager Studio registra cada confirmación en la **Consola de tráfico** (*Integraciones → Seguridad y tráfico*) y muestra avisos claros en **Nodos** e **Integraciones**.

---

## Eventos reenviados (`minecraft.event`)

Todos los mensajes usan el sobre `blockstager.proto/1.2` con `source: "minecraft.paper"`:

1. `player.join` — `skinUrl`, `skinModel` (`slim` o `classic`). Además dispara `skin.set` si `syncSkinOnJoin: true`.
2. `player.damage` — `damage`, `finalDamage`, `cause`, `finalHealth`.
3. `player.death` — `cause`, `world`.
4. `player.advancement` — `key`.
5. `player.totem_pop` — `hand`.
6. `player.item_consume` — `material`.
7. `player.dimension_change` — `fromWorld`, `toWorld`, `toEnvironment`.
8. `player.held_item_change` — `slot`, `material`, `item`.

---

## Compilación local sin Gradle ni Maven

El proyecto incluye un script en PowerShell que usa únicamente el JDK 21 (`javac` y `jar`) y cachea los JAR de compilación en `tools/plugin-build/lib/`:

```powershell
powershell -ExecutionPolicy Bypass -File tools/plugin-build/build.ps1
```

Esto genera:
- `minecraft-plugin/BlockstagerStudioPlugin.jar`
- Clases de prueba local en `tools/plugin-build/build/selftest/`

### Verificación local (`SelfTest`)

Prueba autocontenida sin servidor Paper (verifica autenticación, `trigger.fire`, rechazo `4401`, puente bidireccional `minecraft.action` ↔ `minecraft.action.result` con allowlist/blocklist, límites, cooldown y `disabled`, desconexión de `10 s` con reconexión automática por backoff y `0` sockets/hilos huérfanos):

```powershell
java -cp "tools/plugin-build/build/selftest;tools/plugin-build/lib/gson-2.11.0.jar" dev.blockstager.plugin.SelfTest --self-contained
```

Prueba contra una instancia activa de Blockstager Studio:

```powershell
java -cp "tools/plugin-build/build/selftest;tools/plugin-build/lib/gson-2.11.0.jar" dev.blockstager.plugin.SelfTest --url ws://127.0.0.1:28490/ws --token bs_tok_TU_TOKEN
```
