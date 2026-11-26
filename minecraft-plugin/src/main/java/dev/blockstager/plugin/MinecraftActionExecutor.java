package dev.blockstager.plugin;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Ejecutor seguro de acciones remotas {@code minecraft.action} en el hilo principal
 * de Bukkit usando exclusivamente la API pública de Paper/Bukkit 1.21+ (cero NMS).
 */
public final class MinecraftActionExecutor {

    private final BlockstagerPlugin plugin;
    private final RemoteActionPolicy policy;
    private volatile String defaultTargetPlayer;

    public MinecraftActionExecutor(BlockstagerPlugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.policy = new RemoteActionPolicy(loadSettings(config));
        this.defaultTargetPlayer = normalizeTarget(config.getString("targetPlayer", ""));
    }

    public RemoteActionPolicy getPolicy() {
        return policy;
    }

    public void reloadFromConfig(FileConfiguration config) {
        this.policy.updateSettings(loadSettings(config));
        this.defaultTargetPlayer = normalizeTarget(config.getString("targetPlayer", ""));
    }

    public boolean isAllowRemoteActions() {
        return policy.isAllowRemoteActions();
    }

    public void setAllowRemoteActions(boolean enabled) {
        policy.setAllowRemoteActions(enabled);
        plugin.getConfig().set("allowRemoteActions", enabled);
        plugin.saveConfig();
    }

    /**
     * Entrada invocada desde el hilo del WebSocket al recibir {@code minecraft.action}.
     * Siempre despacha validación y ejecución al hilo principal de Bukkit.
     */
    public void handleIncomingAction(String envelopeId, JsonObject payload) {
        if (!plugin.isEnabled()) {
            return;
        }
        JsonObject payloadCopy = payload != null ? payload.deepCopy() : new JsonObject();
        Bukkit.getScheduler().runTask(plugin, () -> processActionOnMainThread(envelopeId, payloadCopy, null));
    }

    /**
     * Ejecuta una acción de prueba local disparada por {@code /bs test-action <tipo>}.
     */
    public void executeTestAction(CommandSender sender, String rawType, String extraArg) {
        if (!plugin.isEnabled()) {
            sender.sendMessage("[Blockstager] El plugin no está activo.");
            return;
        }
        String norm = rawType == null ? "" : rawType.trim().toLowerCase(Locale.ROOT);
        JsonObject payload = new JsonObject();
        payload.addProperty("actionId", "test_" + System.currentTimeMillis());
        payload.addProperty("triggeredBy", sender.getName());
        if (sender instanceof Player player) {
            payload.addProperty("targetPlayer", player.getName());
        }

        switch (norm) {
            case "spawn_mob", "spawn" -> {
                payload.addProperty("action", "spawn_mob");
                payload.addProperty("entity", extraArg != null && !extraArg.isBlank() ? extraArg : "allay");
                payload.addProperty("count", 1);
                payload.addProperty("radiusBlocks", 3);
            }
            case "give_item", "give" -> {
                payload.addProperty("action", "give_item");
                payload.addProperty("item", extraArg != null && !extraArg.isBlank() ? extraArg : "golden_apple");
                payload.addProperty("amount", 1);
            }
            case "potion_effect", "apply_effect", "effect" -> {
                payload.addProperty("action", "potion_effect");
                payload.addProperty("effect", extraArg != null && !extraArg.isBlank() ? extraArg : "speed");
                payload.addProperty("durationSec", 5);
                payload.addProperty("amplifier", 0);
            }
            case "world_effect", "world_fx", "world" -> {
                payload.addProperty("action", "world_effect");
                payload.addProperty("fx", extraArg != null && !extraArg.isBlank() ? extraArg : "lightning_effect");
                payload.addProperty("text", "Prueba de Blockstager Studio");
            }
            default -> {
                sender.sendMessage("[Blockstager] Tipo no reconocido. Usa: spawn_mob, give_item, potion_effect o world_effect.");
                return;
            }
        }

        if (Bukkit.isPrimaryThread()) {
            processActionOnMainThread(payload.get("actionId").getAsString(), payload, sender);
        } else {
            Bukkit.getScheduler().runTask(plugin, () ->
                    processActionOnMainThread(payload.get("actionId").getAsString(), payload, sender));
        }
    }

    private void processActionOnMainThread(String envelopeId, JsonObject payload, CommandSender feedbackSender) {
        RemoteActionPolicy.ValidationResult check = policy.validate(envelopeId, payload);
        if (!check.isOk()) {
            reportOutcome(check.actionId(), check.requestId(), check.wireAction(),
                    check.outcome().wireValue(), check.detail(), null, feedbackSender);
            return;
        }

        RemoteActionPolicy.ValidatedAction action = check.action();
        Player target = resolveTargetPlayer(action.targetPlayer());
        if (target == null) {
            String missingDetail = action.targetPlayer() != null
                    ? "El jugador objetivo «" + action.targetPlayer() + "» no está conectado."
                    : "No hay ningún jugador objetivo conectado en el servidor.";
            reportOutcome(action.actionId(), action.requestId(), action.wireAction(),
                    RemoteActionPolicy.Outcome.REJECTED.wireValue(), missingDetail, null, feedbackSender);
            return;
        }

        try {
            ExecutionOutcome result = switch (action.kind()) {
                case SPAWN_MOB -> executeSpawnMob(target, action);
                case GIVE_ITEM -> executeGiveItem(target, action);
                case POTION_EFFECT -> executePotionEffect(target, action);
                case WORLD_EFFECT -> executeWorldEffect(target, action);
            };
            reportOutcome(action.actionId(), action.requestId(), action.wireAction(),
                    result.outcome().wireValue(), result.detail(), target.getName(), feedbackSender);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[Blockstager] Error ejecutando acción remota '" + action.wireAction() + "': " + ex.getMessage(), ex);
            reportOutcome(action.actionId(), action.requestId(), action.wireAction(),
                    RemoteActionPolicy.Outcome.REJECTED.wireValue(),
                    "Error al ejecutar acción en el servidor: " + ex.getMessage(),
                    target.getName(),
                    feedbackSender);
        }
    }

    private ExecutionOutcome executeSpawnMob(Player target, RemoteActionPolicy.ValidatedAction action) {
        String entityId = action.primaryToken();
        EntityType entityType = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(entityId));
        if (entityType == null || !entityType.isSpawnable()) {
            return ExecutionOutcome.rejected("La criatura «" + entityId + "» no se puede invocar en esta versión.");
        }

        World world = target.getWorld();
        int count = action.intParam1();
        double radius = action.doubleParam();

        for (int i = 0; i < count; i++) {
            Location loc = findSafeSpawnLocation(target.getLocation(), radius);
            if (entityType == EntityType.FIREWORK_ROCKET) {
                spawnCosmeticFirework(world, loc.clone().add(0.0, 0.5, 0.0));
            } else {
                world.spawnEntity(loc, entityType);
            }
        }

        String detail = "Invocado " + count + "× " + entityId + " cerca de " + target.getName() + ".";
        return ExecutionOutcome.ok(detail);
    }

    private ExecutionOutcome executeGiveItem(Player target, RemoteActionPolicy.ValidatedAction action) {
        String itemId = action.primaryToken();
        Material material = Material.matchMaterial(itemId);
        if (material == null || !material.isItem() || material.isAir()) {
            return ExecutionOutcome.rejected("El ítem «" + itemId + "» no existe o no es entregable.");
        }

        int amount = action.intParam1();
        ItemStack stack = new ItemStack(material, amount);
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(stack);
        int dropped = 0;
        if (!overflow.isEmpty()) {
            World world = target.getWorld();
            Location dropLoc = target.getLocation();
            for (ItemStack leftover : overflow.values()) {
                if (leftover != null && !leftover.getType().isAir()) {
                    dropped += leftover.getAmount();
                    world.dropItemNaturally(dropLoc, leftover);
                }
            }
        }

        String detail = dropped > 0
                ? "Entregado " + amount + "× " + itemId + " a " + target.getName() + " (" + dropped + " al suelo)."
                : "Entregado " + amount + "× " + itemId + " a " + target.getName() + ".";
        return ExecutionOutcome.ok(detail);
    }

    private ExecutionOutcome executePotionEffect(Player target, RemoteActionPolicy.ValidatedAction action) {
        String effectId = action.primaryToken();
        PotionEffectType effectType = Registry.EFFECT.get(NamespacedKey.minecraft(effectId));
        if (effectType == null) {
            return ExecutionOutcome.rejected("El efecto de poción «" + effectId + "» no existe en esta versión.");
        }

        int durationSec = action.intParam1();
        int amplifier = action.intParam2();
        int durationTicks = Math.max(20, durationSec * 20);
        PotionEffect effect = new PotionEffect(effectType, durationTicks, amplifier, true, true, true);
        target.addPotionEffect(effect);

        String detail = "Aplicado " + effectId + " (nivel " + (amplifier + 1) + ", " + durationSec + " s) a " + target.getName() + ".";
        return ExecutionOutcome.ok(detail);
    }

    private ExecutionOutcome executeWorldEffect(Player target, RemoteActionPolicy.ValidatedAction action) {
        String fx = action.primaryToken();
        String text = action.textParam();
        World world = target.getWorld();
        Location loc = target.getLocation();

        return switch (fx) {
            case "lightning_effect" -> {
                world.strikeLightningEffect(loc);
                yield ExecutionOutcome.ok("Rayo cosmético sobre " + target.getName() + ".");
            }
            case "title_overlay" -> {
                String titleText = !text.isBlank() ? text : "¡Alerta del directo!";
                String subtitle = action.triggeredBy() != null && !action.triggeredBy().isBlank()
                        ? "Por " + action.triggeredBy()
                        : "";
                Title title = Title.title(
                        Component.text(titleText),
                        Component.text(subtitle),
                        Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500))
                );
                target.showTitle(title);
                yield ExecutionOutcome.ok("Título mostrado a " + target.getName() + ": «" + titleText + "».");
            }
            case "knockback" -> {
                Vector dir = loc.getDirection().setY(0);
                if (dir.lengthSquared() < 0.0001) {
                    dir = new Vector(0.0, 0.0, 1.0);
                } else {
                    dir.normalize().multiply(-1.0);
                }
                Vector push = dir.multiply(0.65).setY(0.38);
                target.setVelocity(target.getVelocity().add(push));
                yield ExecutionOutcome.ok("Empuje aplicado a " + target.getName() + ".");
            }
            case "play_sound", "sound_levelup", "sound_bell" -> {
                Sound sound = resolveSafeSound(fx, text);
                target.playSound(loc, sound, 1.0f, 1.0f);
                yield ExecutionOutcome.ok("Sonido reproducido para " + target.getName() + ".");
            }
            case "firework" -> {
                spawnCosmeticFirework(world, loc.clone().add(0.0, 1.0, 0.0));
                yield ExecutionOutcome.ok("Fuego artificial lanzado cerca de " + target.getName() + ".");
            }
            case "heart_particles" -> {
                world.spawnParticle(Particle.HEART, loc.clone().add(0.0, 1.4, 0.0), 18, 0.7, 0.6, 0.7, 0.05);
                yield ExecutionOutcome.ok("Partículas de corazones en " + target.getName() + ".");
            }
            case "explosion_particles" -> {
                world.spawnParticle(Particle.EXPLOSION, loc.clone().add(0.0, 1.0, 0.0), 10, 0.6, 0.5, 0.6, 0.05);
                target.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.55f, 1.15f);
                yield ExecutionOutcome.ok("Partículas de explosión cosmética en " + target.getName() + ".");
            }
            case "weather_clear" -> {
                world.setStorm(false);
                world.setThundering(false);
                yield ExecutionOutcome.ok("Clima despejado en " + world.getName() + ".");
            }
            case "weather_rain" -> {
                world.setStorm(true);
                yield ExecutionOutcome.ok("Lluvia activada en " + world.getName() + ".");
            }
            case "time_day" -> {
                world.setTime(1000L);
                yield ExecutionOutcome.ok("Hora ajustada a día en " + world.getName() + ".");
            }
            case "time_night" -> {
                world.setTime(13000L);
                yield ExecutionOutcome.ok("Hora ajustada a noche en " + world.getName() + ".");
            }
            default -> ExecutionOutcome.rejected("Efecto de mundo no soportado: «" + fx + "».");
        };
    }

    private void spawnCosmeticFirework(World world, Location loc) {
        Firework fw = world.spawn(loc, Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.setPower(1);
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.AQUA, Color.LIME)
                .withFade(Color.WHITE)
                .trail(true)
                .build());
        fw.setFireworkMeta(meta);
    }

    private Sound resolveSafeSound(String fx, String text) {
        String hint = (text == null ? "" : text.trim().toLowerCase(Locale.ROOT));
        if ("sound_bell".equals(fx) || hint.contains("bell") || hint.contains("campana")) {
            return Sound.BLOCK_BELL_USE;
        }
        if (hint.contains("thunder") || hint.contains("trueno")) {
            return Sound.ENTITY_LIGHTNING_BOLT_THUNDER;
        }
        return Sound.ENTITY_PLAYER_LEVELUP;
    }

    private Location findSafeSpawnLocation(Location origin, double radius) {
        World world = origin.getWorld();
        if (world == null || radius <= 0.5) {
            return origin.clone();
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = rng.nextDouble(0.0, Math.PI * 2.0);
            double dist = Math.max(1.0, rng.nextDouble(Math.min(1.5, radius), radius));
            int bx = (int) Math.floor(origin.getX() + Math.cos(angle) * dist);
            int bz = (int) Math.floor(origin.getZ() + Math.sin(angle) * dist);
            int baseY = origin.getBlockY();

            for (int dy = 2; dy >= -2; dy--) {
                int y = baseY + dy;
                if (y <= world.getMinHeight() + 1 || y >= world.getMaxHeight() - 2) {
                    continue;
                }
                Block floor = world.getBlockAt(bx, y - 1, bz);
                Block feet = world.getBlockAt(bx, y, bz);
                Block head = world.getBlockAt(bx, y + 1, bz);
                if (floor.getType().isSolid() && feet.isPassable() && head.isPassable()) {
                    return new Location(world, bx + 0.5, y, bz + 0.5);
                }
            }
        }
        return origin.clone();
    }

    private Player resolveTargetPlayer(String requestedPlayer) {
        if (requestedPlayer != null && !requestedPlayer.isBlank()) {
            Player exact = Bukkit.getPlayerExact(requestedPlayer);
            if (exact != null && exact.isOnline()) {
                return exact;
            }
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().equalsIgnoreCase(requestedPlayer)) {
                    return online;
                }
            }
            return null;
        }

        String configured = this.defaultTargetPlayer;
        if (!configured.isEmpty()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().equalsIgnoreCase(configured)) {
                    return online;
                }
            }
            return null;
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.isOnline()) {
                return online;
            }
        }
        return null;
    }

    private void reportOutcome(
            String actionId,
            String requestId,
            String action,
            String outcome,
            String detail,
            String playerName,
            CommandSender feedbackSender
    ) {
        WsBridgeClient bridge = plugin.getBridgeClient();
        if (bridge != null && bridge.isConnected()) {
            bridge.sendMinecraftActionResult(actionId, requestId, action, outcome, detail, playerName);
        }
        if (feedbackSender != null) {
            feedbackSender.sendMessage("[Blockstager] Resultado (" + outcome + "): " + detail);
        } else if (!"ok".equals(outcome)) {
            plugin.getLogger().info("[Blockstager] Acción '" + action + "' -> " + outcome + ": " + detail);
        }
    }

    public static RemoteActionPolicy.Settings loadSettings(FileConfiguration cfg) {
        if (cfg == null) {
            return RemoteActionPolicy.Settings.defaults();
        }
        boolean allowRemoteActions = cfg.getBoolean("allowRemoteActions", true);
        int maxMobCount = cfg.getInt("maxMobCount", RemoteActionPolicy.DEFAULT_MAX_MOB_COUNT);
        double maxSpawnRadius = cfg.getDouble("maxSpawnRadius", RemoteActionPolicy.DEFAULT_MAX_SPAWN_RADIUS);
        int maxItemAmount = cfg.getInt("maxItemAmount", RemoteActionPolicy.DEFAULT_MAX_ITEM_AMOUNT);
        int maxEffectDurationSec = cfg.getInt("maxEffectDurationSec", RemoteActionPolicy.DEFAULT_MAX_EFFECT_DURATION_SEC);
        int maxEffectAmplifier = cfg.getInt("maxEffectAmplifier", RemoteActionPolicy.DEFAULT_MAX_EFFECT_AMPLIFIER);
        long actionCooldownMs = cfg.getLong("actionCooldownMs", RemoteActionPolicy.DEFAULT_ACTION_COOLDOWN_MS);

        List<String> allowedEntitiesCfg = cfg.getStringList("allowedEntities");
        List<String> blockedEntitiesCfg = cfg.getStringList("blockedEntities");
        List<String> allowedItemsCfg = cfg.getStringList("allowedItems");
        List<String> blockedItemsCfg = cfg.getStringList("blockedItems");
        List<String> allowedEffectsCfg = cfg.getStringList("allowedEffects");
        List<String> allowedWorldEffectsCfg = cfg.getStringList("allowedWorldEffects");

        Set<String> allowedEntities = allowedEntitiesCfg.isEmpty()
                ? RemoteActionPolicy.DEFAULT_ALLOWED_ENTITIES
                : RemoteActionPolicy.normalizeTokenList(allowedEntitiesCfg);
        Set<String> blockedEntities = cfg.contains("blockedEntities")
                ? RemoteActionPolicy.normalizeTokenList(blockedEntitiesCfg)
                : RemoteActionPolicy.DEFAULT_BLOCKED_ENTITIES;
        Set<String> allowedItems = RemoteActionPolicy.normalizeTokenList(allowedItemsCfg);
        Set<String> blockedItems = cfg.contains("blockedItems")
                ? RemoteActionPolicy.normalizeTokenList(blockedItemsCfg)
                : RemoteActionPolicy.DEFAULT_BLOCKED_ITEMS;
        Set<String> allowedEffects = allowedEffectsCfg.isEmpty()
                ? RemoteActionPolicy.DEFAULT_ALLOWED_EFFECTS
                : RemoteActionPolicy.normalizeTokenList(allowedEffectsCfg);
        Set<String> allowedWorldEffects = allowedWorldEffectsCfg.isEmpty()
                ? RemoteActionPolicy.DEFAULT_ALLOWED_WORLD_EFFECTS
                : RemoteActionPolicy.normalizeTokenList(allowedWorldEffectsCfg);

        return RemoteActionPolicy.sanitizeSettings(new RemoteActionPolicy.Settings(
                allowRemoteActions,
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
        ));
    }

    private static String normalizeTarget(String value) {
        return value == null ? "" : value.trim();
    }

    private record ExecutionOutcome(RemoteActionPolicy.Outcome outcome, String detail) {
        static ExecutionOutcome ok(String detail) {
            return new ExecutionOutcome(RemoteActionPolicy.Outcome.OK, detail);
        }

        static ExecutionOutcome rejected(String detail) {
            return new ExecutionOutcome(RemoteActionPolicy.Outcome.REJECTED, detail);
        }
    }
}
