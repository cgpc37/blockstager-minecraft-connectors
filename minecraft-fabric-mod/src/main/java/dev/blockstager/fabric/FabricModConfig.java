package dev.blockstager.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Configuración local persistida en {@code <minecraft>/config/blockstager-fabric.json}.
 *
 * <p>No depende de APIs de Minecraft ni Fabric para poder probarse de forma aislada.</p>
 */
public final class FabricModConfig {

    public static final String DEFAULT_WS_URL = "ws://127.0.0.1:28490/ws";
    public static final String FILE_NAME = "blockstager-fabric.json";

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    public String wsUrl = DEFAULT_WS_URL;
    public String token = "";
    public boolean autoConnect = true;
    public boolean syncSkinOnJoin = true;
    public boolean sendMovementEvents = true;

    public boolean allowRemoteActions = true;
    public boolean allowClientCosmeticEffects = true;
    public boolean allowMultiplayerOpActions = false;

    public long actionCooldownMs = ClientActionPolicy.DEFAULT_ACTION_COOLDOWN_MS;
    public int maxMobCount = ClientActionPolicy.DEFAULT_MAX_MOB_COUNT;
    public double maxSpawnRadius = ClientActionPolicy.DEFAULT_MAX_SPAWN_RADIUS;
    public int maxItemAmount = ClientActionPolicy.DEFAULT_MAX_ITEM_AMOUNT;
    public int maxEffectDurationSec = ClientActionPolicy.DEFAULT_MAX_EFFECT_DURATION_SEC;
    public int maxEffectAmplifier = ClientActionPolicy.DEFAULT_MAX_EFFECT_AMPLIFIER;

    public List<String> allowedEntities = new ArrayList<>(ClientActionPolicy.DEFAULT_ALLOWED_ENTITIES);
    public List<String> blockedEntities = new ArrayList<>(ClientActionPolicy.DEFAULT_BLOCKED_ENTITIES);
    public List<String> allowedItems = new ArrayList<>();
    public List<String> blockedItems = new ArrayList<>(ClientActionPolicy.DEFAULT_BLOCKED_ITEMS);

    public static FabricModConfig defaults() {
        return new FabricModConfig();
    }

    public ClientActionPolicy.Settings toPolicySettings() {
        return ClientActionPolicy.sanitizeSettings(new ClientActionPolicy.Settings(
                allowRemoteActions,
                allowClientCosmeticEffects,
                allowMultiplayerOpActions,
                maxMobCount,
                maxSpawnRadius,
                maxItemAmount,
                maxEffectDurationSec,
                maxEffectAmplifier,
                actionCooldownMs,
                toSet(allowedEntities),
                toSet(blockedEntities),
                toSet(allowedItems),
                toSet(blockedItems),
                ClientActionPolicy.DEFAULT_ALLOWED_EFFECTS,
                ClientActionPolicy.DEFAULT_ALLOWED_WORLD_EFFECTS
        ));
    }

    public void sanitizeInPlace() {
        if (wsUrl == null || wsUrl.isBlank()) {
            wsUrl = DEFAULT_WS_URL;
        } else {
            wsUrl = wsUrl.trim();
        }
        token = token != null ? token.trim() : "";
        actionCooldownMs = Math.max(0L, Math.min(60_000L, actionCooldownMs));
        maxMobCount = Math.max(1, Math.min(50, maxMobCount));
        if (!Double.isFinite(maxSpawnRadius)) {
            maxSpawnRadius = ClientActionPolicy.DEFAULT_MAX_SPAWN_RADIUS;
        }
        maxSpawnRadius = Math.max(1.0, Math.min(32.0, maxSpawnRadius));
        maxItemAmount = Math.max(1, Math.min(64, maxItemAmount));
        maxEffectDurationSec = Math.max(1, Math.min(600, maxEffectDurationSec));
        maxEffectAmplifier = Math.max(0, Math.min(10, maxEffectAmplifier));

        if (allowedEntities == null || allowedEntities.isEmpty()) {
            allowedEntities = new ArrayList<>(ClientActionPolicy.DEFAULT_ALLOWED_ENTITIES);
        }
        if (blockedEntities == null) {
            blockedEntities = new ArrayList<>(ClientActionPolicy.DEFAULT_BLOCKED_ENTITIES);
        }
        if (allowedItems == null) {
            allowedItems = new ArrayList<>();
        }
        if (blockedItems == null) {
            blockedItems = new ArrayList<>(ClientActionPolicy.DEFAULT_BLOCKED_ITEMS);
        }
    }

    public static FabricModConfig loadOrCreate(Path filePath) throws IOException {
        if (Files.isRegularFile(filePath)) {
            String raw = Files.readString(filePath, StandardCharsets.UTF_8);
            FabricModConfig parsed = fromJson(raw);
            parsed.sanitizeInPlace();
            return parsed;
        }
        FabricModConfig created = defaults();
        created.sanitizeInPlace();
        saveAtomic(filePath, created);
        return created;
    }

    public static void saveAtomic(Path filePath, FabricModConfig config) throws IOException {
        FabricModConfig safe = config != null ? config : defaults();
        safe.sanitizeInPlace();
        Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = filePath.resolveSibling(filePath.getFileName().toString() + ".tmp");
        String json = toJson(safe) + "\n";
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String toJson(FabricModConfig config) {
        FabricModConfig safe = config != null ? config : defaults();
        safe.sanitizeInPlace();
        return PRETTY_GSON.toJson(safe);
    }

    public static FabricModConfig fromJson(String raw) {
        FabricModConfig cfg = defaults();
        if (raw == null || raw.isBlank()) {
            return cfg;
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return cfg;
            }
            JsonObject obj = parsed.getAsJsonObject();
            cfg.wsUrl = readString(obj, "wsUrl", cfg.wsUrl);
            cfg.token = readString(obj, "token", cfg.token);
            cfg.autoConnect = readBoolean(obj, "autoConnect", cfg.autoConnect);
            cfg.syncSkinOnJoin = readBoolean(obj, "syncSkinOnJoin", cfg.syncSkinOnJoin);
            cfg.sendMovementEvents = readBoolean(obj, "sendMovementEvents", cfg.sendMovementEvents);
            cfg.allowRemoteActions = readBoolean(obj, "allowRemoteActions", cfg.allowRemoteActions);
            cfg.allowClientCosmeticEffects = readBoolean(obj, "allowClientCosmeticEffects", cfg.allowClientCosmeticEffects);
            cfg.allowMultiplayerOpActions = readBoolean(obj, "allowMultiplayerOpActions", cfg.allowMultiplayerOpActions);
            cfg.actionCooldownMs = readLong(obj, "actionCooldownMs", cfg.actionCooldownMs);
            cfg.maxMobCount = readInt(obj, "maxMobCount", cfg.maxMobCount);
            cfg.maxSpawnRadius = readDouble(obj, "maxSpawnRadius", cfg.maxSpawnRadius);
            cfg.maxItemAmount = readInt(obj, "maxItemAmount", cfg.maxItemAmount);
            cfg.maxEffectDurationSec = readInt(obj, "maxEffectDurationSec", cfg.maxEffectDurationSec);
            cfg.maxEffectAmplifier = readInt(obj, "maxEffectAmplifier", cfg.maxEffectAmplifier);
            if (obj.has("allowedEntities") && obj.get("allowedEntities").isJsonArray()) {
                cfg.allowedEntities = readStringList(obj, "allowedEntities");
            }
            if (obj.has("blockedEntities") && obj.get("blockedEntities").isJsonArray()) {
                cfg.blockedEntities = readStringList(obj, "blockedEntities");
            }
            if (obj.has("allowedItems") && obj.get("allowedItems").isJsonArray()) {
                cfg.allowedItems = readStringList(obj, "allowedItems");
            }
            if (obj.has("blockedItems") && obj.get("blockedItems").isJsonArray()) {
                cfg.blockedItems = readStringList(obj, "blockedItems");
            }
        } catch (RuntimeException ignored) {
            // Conservar valores seguros por defecto si el JSON está dañado
        }
        cfg.sanitizeInPlace();
        return cfg;
    }

    private static Set<String> toSet(List<String> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(list);
    }

    private static String readString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return obj.get(key).getAsString();
    }

    private static boolean readBoolean(JsonObject obj, String key, boolean fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static int readInt(JsonObject obj, String key, int fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static long readLong(JsonObject obj, String key, long fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static double readDouble(JsonObject obj, String key, double fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsDouble();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static List<String> readStringList(JsonObject obj, String key) {
        List<String> out = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray(key)) {
            if (el != null && el.isJsonPrimitive()) {
                String value = el.getAsString().trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        }
        return out;
    }
}
