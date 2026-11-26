package dev.blockstager.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Política de seguridad pura (sin dependencias de Minecraft/Fabric) para validar
 * acciones remotas {@code minecraft.action} recibidas en el cliente Fabric 1.21+.
 *
 * <p>Garantiza juego limpio en servidores públicos: las acciones de mundo
 * ({@code spawn_mob}, {@code give_item}, {@code potion_effect}, clima/hora, etc.)
 * solo se permiten en <strong>Un jugador (servidor integrado local)</strong> o
 * cuando el usuario activa explícitamente {@code allowMultiplayerOpActions} y
 * posee nivel de operador {@code >= 2}. En servidores públicos únicamente pueden
 * ejecutarse efectos cosméticos locales (título en pantalla o sonido local).</p>
 */
public final class ClientActionPolicy {

    public static final int DEFAULT_MAX_MOB_COUNT = 10;
    public static final double DEFAULT_MAX_SPAWN_RADIUS = 16.0;
    public static final int DEFAULT_MAX_ITEM_AMOUNT = 64;
    public static final int DEFAULT_MAX_EFFECT_DURATION_SEC = 120;
    public static final int DEFAULT_MAX_EFFECT_AMPLIFIER = 4;
    public static final long DEFAULT_ACTION_COOLDOWN_MS = 1_000L;

    public static final Set<String> DEFAULT_ALLOWED_ENTITIES = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "zombie",
            "skeleton",
            "creeper",
            "pig",
            "sheep",
            "cow",
            "chicken",
            "wolf",
            "cat",
            "villager",
            "slime",
            "enderman",
            "iron_golem",
            "allay",
            "fox",
            "bee",
            "axolotl",
            "snow_golem",
            "spider",
            "phantom",
            "llama",
            "horse",
            "rabbit",
            "firework_rocket"
    )));

    public static final Set<String> DEFAULT_BLOCKED_ENTITIES = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "wither",
            "ender_dragon",
            "warden",
            "elder_guardian",
            "tnt",
            "primed_tnt",
            "tnt_minecart",
            "end_crystal"
    )));

    public static final Set<String> DEFAULT_BLOCKED_ITEMS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "bedrock",
            "barrier",
            "command_block",
            "chain_command_block",
            "repeating_command_block",
            "command_block_minecart",
            "structure_block",
            "structure_void",
            "jigsaw",
            "debug_stick",
            "light",
            "spawner",
            "end_portal_frame",
            "reinforced_deepslate",
            "knowledge_book"
    )));

    public static final Set<String> DEFAULT_ALLOWED_EFFECTS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "speed",
            "slowness",
            "haste",
            "mining_fatigue",
            "strength",
            "jump_boost",
            "nausea",
            "regeneration",
            "resistance",
            "fire_resistance",
            "water_breathing",
            "invisibility",
            "blindness",
            "night_vision",
            "hunger",
            "weakness",
            "poison",
            "absorption",
            "saturation",
            "glowing",
            "levitation",
            "luck",
            "unluck",
            "slow_falling",
            "conduit_power",
            "dolphins_grace",
            "darkness"
    )));

    public static final Set<String> DEFAULT_ALLOWED_WORLD_EFFECTS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "lightning_effect",
            "title_overlay",
            "knockback",
            "play_sound",
            "firework",
            "heart_particles",
            "explosion_particles",
            "sound_bell",
            "sound_levelup",
            "weather_clear",
            "weather_rain",
            "time_day",
            "time_night"
    )));

    /**
     * Efectos 100% locales al cliente (HUD/sonido del streamer, cero paquetes al servidor).
     */
    public static final Set<String> CLIENT_ONLY_COSMETIC_FX = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "title_overlay",
            "play_sound",
            "sound_bell",
            "sound_levelup"
    )));

    public enum ActionKind {
        SPAWN_MOB,
        GIVE_ITEM,
        POTION_EFFECT,
        WORLD_EFFECT
    }

    public enum Outcome {
        OK("ok"),
        REJECTED("rejected"),
        RATE_LIMITED("rate_limited"),
        DISABLED("disabled");

        private final String wireValue;

        Outcome(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public enum ExecutionMode {
        /** Sin mundo activo (menú principal). */
        DISCONNECTED,
        /** Partida en un jugador con servidor integrado local. */
        SINGLEPLAYER,
        /** Servidor multijugador público (sin permisos o sin opt-in de comandos). */
        MULTIPLAYER_PUBLIC,
        /** Servidor multijugador donde el jugador es operador (>= 2) y activó el opt-in. */
        MULTIPLAYER_OP
    }

    public enum ExecutionRoute {
        CLIENT_COSMETIC,
        INTEGRATED_SERVER,
        MULTIPLAYER_OP_COMMAND
    }

    public record Settings(
            boolean allowRemoteActions,
            boolean allowClientCosmeticEffects,
            boolean allowMultiplayerOpActions,
            int maxMobCount,
            double maxSpawnRadius,
            int maxItemAmount,
            int maxEffectDurationSec,
            int maxEffectAmplifier,
            long actionCooldownMs,
            Set<String> allowedEntities,
            Set<String> blockedEntities,
            Set<String> allowedItems,
            Set<String> blockedItems,
            Set<String> allowedEffects,
            Set<String> allowedWorldEffects
    ) {
        public static Settings defaults() {
            return new Settings(
                    true,
                    true,
                    false,
                    DEFAULT_MAX_MOB_COUNT,
                    DEFAULT_MAX_SPAWN_RADIUS,
                    DEFAULT_MAX_ITEM_AMOUNT,
                    DEFAULT_MAX_EFFECT_DURATION_SEC,
                    DEFAULT_MAX_EFFECT_AMPLIFIER,
                    DEFAULT_ACTION_COOLDOWN_MS,
                    DEFAULT_ALLOWED_ENTITIES,
                    DEFAULT_BLOCKED_ENTITIES,
                    Collections.emptySet(),
                    DEFAULT_BLOCKED_ITEMS,
                    DEFAULT_ALLOWED_EFFECTS,
                    DEFAULT_ALLOWED_WORLD_EFFECTS
            );
        }

        public Settings withAllowRemoteActions(boolean enabled) {
            return new Settings(
                    enabled,
                    allowClientCosmeticEffects,
                    allowMultiplayerOpActions,
                    maxMobCount,
                    maxSpawnRadius,
                    maxItemAmount,
                    maxEffectDurationSec,
                    maxEffectAmplifier,
                    actionCooldownMs,
                    allowedEntities,
                    blockedEntities,
                    allowedItems,
                    blockedItems,
                    allowedEffects,
                    allowedWorldEffects
            );
        }

        public Settings withAllowMultiplayerOpActions(boolean enabled) {
            return new Settings(
                    allowRemoteActions,
                    allowClientCosmeticEffects,
                    enabled,
                    maxMobCount,
                    maxSpawnRadius,
                    maxItemAmount,
                    maxEffectDurationSec,
                    maxEffectAmplifier,
                    actionCooldownMs,
                    allowedEntities,
                    blockedEntities,
                    allowedItems,
                    blockedItems,
                    allowedEffects,
                    allowedWorldEffects
            );
        }
    }

    public record ValidatedAction(
            String actionId,
            String requestId,
            String wireAction,
            ActionKind kind,
            ExecutionRoute route,
            String targetPlayer,
            String triggeredBy,
            String primaryToken,
            int intParam1,
            int intParam2,
            double doubleParam,
            String textParam
    ) {
    }

    public record ValidationResult(
            Outcome outcome,
            String actionId,
            String requestId,
            String wireAction,
            String detail,
            ValidatedAction action
    ) {
        public boolean isOk() {
            return outcome == Outcome.OK && action != null;
        }
    }

    private final LongSupplier clockMs;
    private final Object cooldownLock = new Object();
    private volatile Settings settings;
    private long lastAcceptedAtMs = Long.MIN_VALUE;

    public ClientActionPolicy(Settings settings) {
        this(settings, System::currentTimeMillis);
    }

    public ClientActionPolicy(Settings settings, LongSupplier clockMs) {
        this.settings = sanitizeSettings(settings);
        this.clockMs = clockMs != null ? clockMs : System::currentTimeMillis;
    }

    public Settings getSettings() {
        return settings;
    }

    public void updateSettings(Settings next) {
        this.settings = sanitizeSettings(next);
    }

    public boolean isAllowRemoteActions() {
        return settings.allowRemoteActions();
    }

    public void setAllowRemoteActions(boolean enabled) {
        this.settings = settings.withAllowRemoteActions(enabled);
    }

    public void resetCooldown() {
        synchronized (cooldownLock) {
            lastAcceptedAtMs = Long.MIN_VALUE;
        }
    }

    public ExecutionMode resolveExecutionMode(boolean inWorld, boolean hasIntegratedServer, int playerPermissionLevel) {
        if (!inWorld) {
            return ExecutionMode.DISCONNECTED;
        }
        if (hasIntegratedServer) {
            return ExecutionMode.SINGLEPLAYER;
        }
        if (settings.allowMultiplayerOpActions() && playerPermissionLevel >= 2) {
            return ExecutionMode.MULTIPLAYER_OP;
        }
        return ExecutionMode.MULTIPLAYER_PUBLIC;
    }

    public static Settings sanitizeSettings(Settings raw) {
        Settings base = raw != null ? raw : Settings.defaults();
        return new Settings(
                base.allowRemoteActions(),
                base.allowClientCosmeticEffects(),
                base.allowMultiplayerOpActions(),
                clampInt(base.maxMobCount(), 1, 50),
                clampDouble(base.maxSpawnRadius(), 1.0, 32.0),
                clampInt(base.maxItemAmount(), 1, 64),
                clampInt(base.maxEffectDurationSec(), 1, 600),
                clampInt(base.maxEffectAmplifier(), 0, 10),
                Math.max(0L, Math.min(60_000L, base.actionCooldownMs())),
                normalizeSet(base.allowedEntities(), DEFAULT_ALLOWED_ENTITIES),
                normalizeSetOrEmpty(base.blockedEntities()),
                normalizeSetOrEmpty(base.allowedItems()),
                normalizeSetOrEmpty(base.blockedItems()),
                normalizeSet(base.allowedEffects(), DEFAULT_ALLOWED_EFFECTS),
                normalizeSet(base.allowedWorldEffects(), DEFAULT_ALLOWED_WORLD_EFFECTS)
        );
    }

    public ValidationResult validate(String envelopeId, JsonObject payload, ExecutionMode mode) {
        JsonObject safePayload = payload != null ? payload : new JsonObject();
        ExecutionMode safeMode = mode != null ? mode : ExecutionMode.DISCONNECTED;

        String requestId = firstNonBlank(
                getString(safePayload, "requestId"),
                getString(safePayload, "actionId"),
                envelopeId,
                ""
        );
        String actionId = firstNonBlank(
                getString(safePayload, "actionId"),
                envelopeId,
                requestId,
                ""
        );
        String wireAction = firstNonBlank(getString(safePayload, "action"), "unknown");
        String normalizedAction = wireAction.trim().toLowerCase(Locale.ROOT);
        Settings cfg = this.settings;

        if (!cfg.allowRemoteActions()) {
            return new ValidationResult(
                    Outcome.DISABLED,
                    actionId,
                    requestId,
                    wireAction,
                    "Las acciones remotas están desactivadas en el mod cliente (/bs actions on).",
                    null
            );
        }

        if (safeMode == ExecutionMode.DISCONNECTED) {
            return rejected(actionId, requestId, wireAction,
                    "No hay ninguna partida activa en Minecraft.");
        }

        long now = clockMs.getAsLong();
        synchronized (cooldownLock) {
            if (cfg.actionCooldownMs() > 0 && lastAcceptedAtMs != Long.MIN_VALUE) {
                long elapsed = now - lastAcceptedAtMs;
                if (elapsed >= 0 && elapsed < cfg.actionCooldownMs()) {
                    double remainingSec = Math.max(0.1, (cfg.actionCooldownMs() - elapsed) / 1000.0);
                    return new ValidationResult(
                            Outcome.RATE_LIMITED,
                            actionId,
                            requestId,
                            wireAction,
                            String.format(Locale.US, "Enfriamiento activo; espera %.1f s.", remainingSec),
                            null
                    );
                }
            }
        }

        String targetPlayer = trimToNull(getString(safePayload, "targetPlayer"));
        String triggeredBy = firstNonBlank(getString(safePayload, "triggeredBy"), "Blockstager");

        ValidationResult parsed = switch (normalizedAction) {
            case "spawn_mob" -> validateSpawnMob(cfg, safeMode, actionId, requestId, wireAction, targetPlayer, triggeredBy, safePayload);
            case "give_item" -> validateGiveItem(cfg, safeMode, actionId, requestId, wireAction, targetPlayer, triggeredBy, safePayload);
            case "apply_effect", "potion_effect" ->
                    validatePotionEffect(cfg, safeMode, actionId, requestId, wireAction, targetPlayer, triggeredBy, safePayload);
            case "world_fx", "world_effect" ->
                    validateWorldEffect(cfg, safeMode, actionId, requestId, wireAction, targetPlayer, triggeredBy, safePayload);
            default -> rejected(actionId, requestId, wireAction,
                    "Acción remota no soportada: «" + wireAction + "».");
        };

        if (parsed.isOk()) {
            synchronized (cooldownLock) {
                lastAcceptedAtMs = clockMs.getAsLong();
            }
        }
        return parsed;
    }

    private ExecutionRoute requireWorldRoute(ExecutionMode mode) {
        return switch (mode) {
            case SINGLEPLAYER -> ExecutionRoute.INTEGRATED_SERVER;
            case MULTIPLAYER_OP -> ExecutionRoute.MULTIPLAYER_OP_COMMAND;
            case MULTIPLAYER_PUBLIC, DISCONNECTED -> null;
        };
    }

    private ValidationResult validateSpawnMob(
            Settings cfg,
            ExecutionMode mode,
            String actionId,
            String requestId,
            String wireAction,
            String targetPlayer,
            String triggeredBy,
            JsonObject payload
    ) {
        ExecutionRoute route = requireWorldRoute(mode);
        if (route == null) {
            return rejected(actionId, requestId, wireAction,
                    "Las acciones de mundo solo están permitidas en un jugador o con permisos de operador en servidor privado.");
        }

        String entity = normalizeMinecraftId(getString(payload, "entity"));
        if (entity.isEmpty() || !entity.matches("[a-z0-9_]+")) {
            return rejected(actionId, requestId, wireAction, "Falta un tipo válido de criatura a invocar.");
        }
        if (cfg.blockedEntities().contains(entity)) {
            return rejected(actionId, requestId, wireAction,
                    "La criatura «" + entity + "» está bloqueada por seguridad.");
        }
        if (!cfg.allowedEntities().isEmpty() && !cfg.allowedEntities().contains(entity)) {
            return rejected(actionId, requestId, wireAction,
                    "La criatura «" + entity + "» no está en la lista permitida del mod.");
        }

        Integer countOpt = getInt(payload, "count");
        int count = countOpt != null ? countOpt : 1;
        if (count < 1 || count > cfg.maxMobCount()) {
            return rejected(actionId, requestId, wireAction,
                    "Cantidad de criaturas fuera del límite permitido (1–" + cfg.maxMobCount() + ").");
        }

        Double radiusOpt = getDouble(payload, "radiusBlocks");
        if (radiusOpt == null) {
            radiusOpt = getDouble(payload, "radius");
        }
        double radius = radiusOpt != null ? radiusOpt : 3.0;
        if (!Double.isFinite(radius) || radius < 0.0 || radius > cfg.maxSpawnRadius()) {
            return rejected(actionId, requestId, wireAction,
                    String.format(Locale.US, "Radio de invocación fuera de límite (0–%.0f bloques).", cfg.maxSpawnRadius()));
        }

        ValidatedAction action = new ValidatedAction(
                actionId, requestId, wireAction, ActionKind.SPAWN_MOB, route,
                targetPlayer, triggeredBy, entity, count, 0, radius, ""
        );
        return ok(action, "Invocar " + count + "× " + entity + " (radio " + formatBlocks(radius) + ").");
    }

    private ValidationResult validateGiveItem(
            Settings cfg,
            ExecutionMode mode,
            String actionId,
            String requestId,
            String wireAction,
            String targetPlayer,
            String triggeredBy,
            JsonObject payload
    ) {
        ExecutionRoute route = requireWorldRoute(mode);
        if (route == null) {
            return rejected(actionId, requestId, wireAction,
                    "Las acciones de mundo solo están permitidas en un jugador o con permisos de operador en servidor privado.");
        }

        String item = normalizeMinecraftId(getString(payload, "item"));
        if (item.isEmpty() || !item.matches("[a-z0-9_]+")) {
            return rejected(actionId, requestId, wireAction, "Identificador de ítem no válido.");
        }
        if (cfg.blockedItems().contains(item)) {
            return rejected(actionId, requestId, wireAction,
                    "El ítem «" + item + "» está bloqueado por seguridad.");
        }
        if (!cfg.allowedItems().isEmpty() && !cfg.allowedItems().contains(item)) {
            return rejected(actionId, requestId, wireAction,
                    "El ítem «" + item + "» no está en la lista permitida del mod.");
        }

        Integer amountOpt = getInt(payload, "amount");
        if (amountOpt == null) {
            amountOpt = getInt(payload, "count");
        }
        int amount = amountOpt != null ? amountOpt : 1;
        if (amount < 1 || amount > cfg.maxItemAmount()) {
            return rejected(actionId, requestId, wireAction,
                    "Cantidad de ítem fuera del límite permitido (1–" + cfg.maxItemAmount() + ").");
        }

        ValidatedAction action = new ValidatedAction(
                actionId, requestId, wireAction, ActionKind.GIVE_ITEM, route,
                targetPlayer, triggeredBy, item, amount, 0, 0.0, ""
        );
        return ok(action, "Entregar " + amount + "× " + item + ".");
    }

    private ValidationResult validatePotionEffect(
            Settings cfg,
            ExecutionMode mode,
            String actionId,
            String requestId,
            String wireAction,
            String targetPlayer,
            String triggeredBy,
            JsonObject payload
    ) {
        ExecutionRoute route = requireWorldRoute(mode);
        if (route == null) {
            return rejected(actionId, requestId, wireAction,
                    "Las acciones de mundo solo están permitidas en un jugador o con permisos de operador en servidor privado.");
        }

        String effect = normalizeEffectAlias(normalizeMinecraftId(getString(payload, "effect")));
        if (effect.isEmpty() || !effect.matches("[a-z0-9_]+")) {
            return rejected(actionId, requestId, wireAction, "Falta el efecto de poción a aplicar.");
        }
        if (!cfg.allowedEffects().isEmpty() && !cfg.allowedEffects().contains(effect)) {
            return rejected(actionId, requestId, wireAction,
                    "El efecto «" + effect + "» no está permitido en el mod.");
        }

        Integer durationOpt = getInt(payload, "durationSec");
        if (durationOpt == null) {
            durationOpt = getInt(payload, "duration");
        }
        int durationSec = durationOpt != null ? durationOpt : 5;
        if (durationSec < 1 || durationSec > cfg.maxEffectDurationSec()) {
            return rejected(actionId, requestId, wireAction,
                    "Duración del efecto fuera de límite (1–" + cfg.maxEffectDurationSec() + " s).");
        }

        Integer amplifierOpt = getInt(payload, "amplifier");
        int amplifier = amplifierOpt != null ? amplifierOpt : 0;
        if (amplifier < 0 || amplifier > cfg.maxEffectAmplifier()) {
            return rejected(actionId, requestId, wireAction,
                    "Nivel del efecto fuera de límite (0–" + cfg.maxEffectAmplifier() + ").");
        }

        ValidatedAction action = new ValidatedAction(
                actionId, requestId, wireAction, ActionKind.POTION_EFFECT, route,
                targetPlayer, triggeredBy, effect, durationSec, amplifier, 0.0, ""
        );
        return ok(action, "Efecto " + effect + " (" + durationSec + " s, nivel " + (amplifier + 1) + ").");
    }

    private ValidationResult validateWorldEffect(
            Settings cfg,
            ExecutionMode mode,
            String actionId,
            String requestId,
            String wireAction,
            String targetPlayer,
            String triggeredBy,
            JsonObject payload
    ) {
        String fx = normalizeWorldFxAlias(normalizeMinecraftId(getString(payload, "fx")));
        if (fx.isEmpty()) {
            return rejected(actionId, requestId, wireAction, "Falta el tipo de efecto de mundo.");
        }
        if (!cfg.allowedWorldEffects().isEmpty() && !cfg.allowedWorldEffects().contains(fx)) {
            return rejected(actionId, requestId, wireAction,
                    "El efecto de mundo «" + fx + "» no está permitido en el mod.");
        }

        boolean isLocalCosmetic = CLIENT_ONLY_COSMETIC_FX.contains(fx);
        ExecutionRoute route;
        if (isLocalCosmetic && cfg.allowClientCosmeticEffects()) {
            route = ExecutionRoute.CLIENT_COSMETIC;
        } else {
            route = requireWorldRoute(mode);
            if (route == null) {
                return rejected(actionId, requestId, wireAction,
                        isLocalCosmetic
                                ? "Los efectos cosméticos locales están desactivados en el mod cliente."
                                : "Las acciones de mundo solo están permitidas en un jugador o con permisos de operador en servidor privado.");
            }
        }

        String rawText = getString(payload, "text");
        String safeText = rawText != null ? rawText.replace('\n', ' ').replace('\r', ' ').trim() : "";
        if (safeText.length() > 96) {
            safeText = safeText.substring(0, 96);
        }

        ValidatedAction action = new ValidatedAction(
                actionId, requestId, wireAction, ActionKind.WORLD_EFFECT, route,
                targetPlayer, triggeredBy, fx, 0, 0, 0.0, safeText
        );
        return ok(action, "Efecto " + fx + ".");
    }

    /**
     * Genera la lista de comandos vanilla estrictamente sanitizados para ejecutar
     * en el servidor integrado local (o como operador autorizado).
     */
    public static List<String> buildVanillaCommands(ValidatedAction action, String localPlayerName) {
        if (action == null) {
            return Collections.emptyList();
        }
        String selector = sanitizePlayerSelector(
                action.targetPlayer() != null ? action.targetPlayer() : localPlayerName
        );
        List<String> commands = new ArrayList<>();
        switch (action.kind()) {
            case SPAWN_MOB -> {
                String entity = action.primaryToken();
                if (!entity.matches("[a-z0-9_]+")) {
                    return Collections.emptyList();
                }
                int count = Math.max(1, Math.min(50, action.intParam1()));
                double r = Math.max(0.0, Math.min(32.0, action.doubleParam()));
                for (int i = 0; i < count; i++) {
                    double angle = (2.0 * Math.PI * i) / count;
                    double dx = r > 0.25 ? Math.cos(angle) * Math.min(r, Math.max(1.5, r * 0.65)) : 0.0;
                    double dz = r > 0.25 ? Math.sin(angle) * Math.min(r, Math.max(1.5, r * 0.65)) : 0.0;
                    commands.add(String.format(
                            Locale.US,
                            "execute at %s run summon minecraft:%s ~%.2f ~ ~%.2f",
                            selector, entity, dx, dz
                    ));
                }
            }
            case GIVE_ITEM -> {
                String item = action.primaryToken();
                if (!item.matches("[a-z0-9_]+")) {
                    return Collections.emptyList();
                }
                int amount = Math.max(1, Math.min(64, action.intParam1()));
                commands.add("give " + selector + " minecraft:" + item + " " + amount);
            }
            case POTION_EFFECT -> {
                String effect = action.primaryToken();
                if (!effect.matches("[a-z0-9_]+")) {
                    return Collections.emptyList();
                }
                int sec = Math.max(1, Math.min(600, action.intParam1()));
                int amp = Math.max(0, Math.min(10, action.intParam2()));
                commands.add("effect give " + selector + " minecraft:" + effect + " " + sec + " " + amp);
            }
            case WORLD_EFFECT -> {
                switch (action.primaryToken()) {
                    case "lightning_effect" ->
                            commands.add("execute at " + selector + " run summon minecraft:lightning_bolt ~ ~ ~");
                    case "firework" ->
                            commands.add("execute at " + selector + " run summon minecraft:firework_rocket ~ ~1 ~");
                    case "heart_particles" ->
                            commands.add("execute at " + selector + " run particle minecraft:heart ~ ~1.5 ~ 0.6 0.6 0.6 0.05 18");
                    case "explosion_particles" ->
                            commands.add("execute at " + selector + " run particle minecraft:explosion ~ ~1.2 ~ 0.4 0.4 0.4 0.05 8");
                    case "weather_clear" -> commands.add("weather clear");
                    case "weather_rain" -> commands.add("weather rain");
                    case "time_day" -> commands.add("time set day");
                    case "time_night" -> commands.add("time set night");
                    case "title_overlay" -> {
                        String text = action.textParam().isEmpty() ? "Blockstager" : action.textParam();
                        commands.add("title " + selector + " title {\"text\":\"" + escapeJsonText(text) + "\"}");
                    }
                    case "sound_bell" ->
                            commands.add("execute at " + selector + " run playsound minecraft:block.bell.use master " + selector + " ~ ~ ~ 1 1");
                    case "sound_levelup", "play_sound" ->
                            commands.add("execute at " + selector + " run playsound minecraft:entity.player.levelup master " + selector + " ~ ~ ~ 1 1");
                    case "knockback" ->
                            commands.add("execute at " + selector + " run tp " + selector + " ~ ~0.6 ~");
                    default -> {
                        // No reconocido
                    }
                }
            }
        }
        return commands;
    }

    static String sanitizePlayerSelector(String playerName) {
        if (playerName != null) {
            String trimmed = playerName.trim();
            if (trimmed.matches("[A-Za-z0-9_]{1,16}")) {
                return trimmed;
            }
        }
        return "@s";
    }

    static String escapeJsonText(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\t', '\r', '\n' -> sb.append(' ');
                default -> {
                    if (c >= 0x20) {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public static String normalizeMinecraftId(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("minecraft:")) {
            value = value.substring("minecraft:".length());
        }
        return value.replace('-', '_').replace(' ', '_');
    }

    static String normalizeEffectAlias(String effect) {
        return switch (effect) {
            case "slow" -> "slowness";
            case "fast_digging" -> "haste";
            case "slow_digging" -> "mining_fatigue";
            case "increase_damage" -> "strength";
            case "jump" -> "jump_boost";
            case "confusion" -> "nausea";
            case "damage_resistance" -> "resistance";
            default -> effect;
        };
    }

    static String normalizeWorldFxAlias(String fx) {
        return switch (fx) {
            case "lightning" -> "lightning_effect";
            case "title" -> "title_overlay";
            default -> fx;
        };
    }

    public static Set<String> normalizeTokenList(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String item : raw) {
            String norm = normalizeMinecraftId(item);
            if (!norm.isEmpty()) {
                out.add(norm);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static Set<String> normalizeSet(Collection<String> raw, Set<String> fallback) {
        Set<String> normalized = normalizeTokenList(raw);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static Set<String> normalizeSetOrEmpty(Collection<String> raw) {
        return normalizeTokenList(raw);
    }

    private static ValidationResult ok(ValidatedAction action, String detail) {
        return new ValidationResult(Outcome.OK, action.actionId(), action.requestId(), action.wireAction(), detail, action);
    }

    private static ValidationResult rejected(String actionId, String requestId, String wireAction, String detail) {
        return new ValidationResult(Outcome.REJECTED, actionId, requestId, wireAction, detail, null);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String formatBlocks(double radius) {
        if (Math.abs(radius - Math.rint(radius)) < 0.01) {
            return ((long) Math.rint(radius)) + " bloques";
        }
        return String.format(Locale.US, "%.1f bloques", radius);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return "";
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return null;
        }
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        return el.getAsString();
    }

    private static Integer getInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return null;
        }
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        try {
            if (el.getAsJsonPrimitive().isNumber()) {
                double d = el.getAsDouble();
                if (!Double.isFinite(d)) {
                    return null;
                }
                return (int) Math.round(d);
            }
            if (el.getAsJsonPrimitive().isString()) {
                String s = el.getAsString().trim();
                if (s.isEmpty()) {
                    return null;
                }
                return (int) Math.round(Double.parseDouble(s));
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static Double getDouble(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return null;
        }
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        try {
            if (el.getAsJsonPrimitive().isNumber()) {
                return el.getAsDouble();
            }
            if (el.getAsJsonPrimitive().isString()) {
                String s = el.getAsString().trim();
                if (s.isEmpty()) {
                    return null;
                }
                return Double.parseDouble(s);
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }
}
