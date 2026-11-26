# Blockstager Studio Client Mod (Fabric 1.21+ — Java 21)

Mod cliente ligero y oficial para conectar tu cliente de **Minecraft (Fabric 1.21+)** con **Blockstager Studio** mediante el protocolo local `blockstager.proto/1.2` sobre WebSocket (`java.net.http.WebSocket`).

Diseñado especialmente para **servidores públicos** donde el streamer no controla el servidor Paper, y compatible también con partidas de **Un jugador**.

- **Compatibilidad:** Minecraft **1.21+** con **Fabric Loader 0.16+** y **Fabric API**, Java 21.
- **100 % legal y seguro en servidores públicos:**
  - Lee únicamente el estado local del propio jugador visible en el cliente (cero paquetes alterados, cero ventajas competitivas).
  - Las acciones de mundo (`spawn_mob`, `give_item`, `potion_effect`, clima/hora) **solo se ejecutan en Un jugador (servidor integrado)** o cuando el jugador tiene permisos de operador (`>= 2`) y activa explícitamente `allowMultiplayerOpActions: true`. En servidores públicos se rechazan automáticamente con confirmación `minecraft.action.result`.
  - Los efectos cosméticos locales (`title_overlay`, `sound_bell`, `sound_levelup`, `play_sound`) se muestran y reproducen únicamente en tu cliente sin enviar ningún paquete al servidor.
- **Cero código ni assets de Mojang en el JAR:** enlaza dinámicamente mediante `FabricLoader.getMappingResolver()`.
- **Reconexión automática:** backoff exponencial con *jitter* entre `1 s` y `30 s`, deteniendo reintentos ante código `4401` (token inválido).

---

## Instalación

1. Instala **Fabric Loader** para Minecraft `1.21+` y **Fabric API**.
2. Descarga gratis `BlockstagerStudioFabric.jar` desde **Modrinth** o **GitHub Releases** (o compílalo localmente desde este repositorio MIT).
3. Copia `BlockstagerStudioFabric.jar` en tu carpeta `.minecraft/mods/`.
4. Inicia Minecraft una vez para generar `.minecraft/config/blockstager-fabric.json`.
5. Abre **Blockstager Studio → Integraciones**, copia el token (`bs_tok_...`), pégalo en `blockstager-fabric.json` y ejecuta `/bs reload` dentro del juego.

---

## Configuración (`config/blockstager-fabric.json`)

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

| Opción | Descripción |
| :--- | :--- |
| `wsUrl` | Dirección WebSocket local de Blockstager Studio (`ws://127.0.0.1:28490/ws`). |
| `token` | Token Bearer generado en Blockstager Studio (`bs_tok_...`). |
| `autoConnect` | Conecta automáticamente al iniciar el cliente de Minecraft. |
| `syncSkinOnJoin` | Envía tu skin actual (`skin.set`) al entrar en un mundo o servidor. |
| `sendMovementEvents` | Envía también `player.sneak`, `player.sprint` y `player.jump` locales. |
| `allowRemoteActions` | Interruptor maestro para acciones entrantes `minecraft.action` (modificable en vivo con `/bs actions on\|off`). |
| `allowClientCosmeticEffects` | Permite títulos en pantalla y sonidos locales en servidores públicos (cero paquetes al servidor). |
| `allowMultiplayerOpActions` | Desactivado (`false`) por defecto. Solo si es `true` **y** tienes nivel OP `>= 2` permite despachar comandos de mundo en un servidor privado. |

---

## Comandos locales del cliente (`/bs` y `/bsc`)

Todos los comandos se ejecutan **100 % en local en tu cliente** y nunca envían texto al chat del servidor público:

| Comando | Descripción |
| :--- | :--- |
| `/bs status` | Estado de conexión, modo (`Un jugador` / `Multijugador público`), acciones y latencia. |
| `/bs connect` | Fuerza una reconexión inmediata con Blockstager Studio. |
| `/bs reload` | Recarga `config/blockstager-fabric.json` en caliente. |
| `/bs trigger <slug>` | Dispara `trigger.fire` directamente hacia Nodos/Reglas de Blockstager Studio. |
| `/bs skin sync` | Reenvía la skin actual del jugador (`skin.set`). |
| `/bs actions status\|on\|off` | Consulta límites o activa/desactiva las acciones remotas en vivo. |
| `/bs test-action <tipo> [valor]` | Ejecuta una prueba local (`world_effect`, `spawn_mob`, `give_item`, `potion_effect`) aplicando la política de seguridad actual. |

*(Si juegas en un servidor Paper que ya usa `/bs`, puedes usar el alias `/bsc` sin conflictos).*

---

## Eventos emitidos (`source: "minecraft.fabric"`)

1. `player.join` — `skinUrl`, `skinModel`, `mode` (`singleplayer` / `multiplayer`), `dimension`. Dispara `skin.set` si `syncSkinOnJoin: true`.
2. `player.damage` — `damage`, `finalDamage`, `cause`, `finalHealth`.
3. `player.death` — `cause`, `world`.
4. `player.totem_pop` — `hand`.
5. `player.item_consume` — `material`, `itemKey`.
6. `player.dimension_change` — `fromWorld`, `toWorld`, `toEnvironment`.
7. `player.held_item_change` — `slot`, `material`, `item`, `itemKey`.
8. `player.sneak`, `player.sprint`, `player.jump` — si `sendMovementEvents: true`.

---

## Compilación y verificación local (JDK 21, sin Gradle)

```powershell
powershell -ExecutionPolicy Bypass -File tools/fabric-build/build.ps1
java -cp "tools/fabric-build/build/selftest;tools/fabric-build/lib/gson-2.11.0.jar" dev.blockstager.fabric.FabricSelfTest --self-contained
```
