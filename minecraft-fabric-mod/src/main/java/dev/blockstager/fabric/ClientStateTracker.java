package dev.blockstager.fabric;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Máquina de estados pura (sin dependencias de Minecraft/Fabric) que observa
 * instantáneas tick a tick del jugador local y emite los eventos canónicos
 * {@code minecraft.event} y {@code skin.set} de {@code blockstager.proto/1.2}.
 *
 * <p>Incluye sincronización de daño, muerte/respawn, fuego, armadura (4 piezas +
 * brillo de encantamiento), ítems en mano principal/secundaria y micro-locomoción
 * en vivo (caminar, correr, agacharse, saltar, golpear/picar).</p>
 */
public final class ClientStateTracker {

    private static final double WALK_SPEED_THRESHOLD = 0.045;
    private static final double SPRINT_SPEED_THRESHOLD = 0.245;
    private static final double TELEPORT_SPEED_CAP = 2.5;
    private static final int STOP_HYSTERESIS_TICKS = 3;
    private static final float LOOK_ANGLE_EMIT_THRESHOLD_DEG = 2.2f;

    public record PlayerSnapshot(
            boolean inWorld,
            boolean singleplayer,
            int permissionLevel,
            String playerName,
            String skinUrl,
            String skinModel,
            String dimensionId,
            float health,
            float maxHealth,
            boolean dead,
            int hurtTime,
            int selectedSlot,
            String mainHandItemKey,
            String offHandItemKey,
            boolean mainHandGlint,
            boolean offHandGlint,
            boolean usingItem,
            String activeUseItemKey,
            int itemUseTimeLeft,
            boolean sneaking,
            boolean sprinting,
            boolean onGround,
            boolean onFire,
            boolean swimming,
            boolean inWater,
            boolean flying,
            String helmetItemKey,
            String chestplateItemKey,
            String leggingsItemKey,
            String bootsItemKey,
            boolean armorEnchanted,
            double posX,
            double posZ,
            boolean handSwinging,
            int handSwingTicks,
            float pitch,
            float yaw,
            float bodyYaw,
            float headYaw,
            String gameDir
    ) {
        public static PlayerSnapshot disconnected() {
            return new PlayerSnapshot(
                    false, false, 0, "", null, "classic", "",
                    0f, 20f, false, 0, 0, "minecraft:air", "minecraft:air",
                    false, false,
                    false, "minecraft:air", 0, false, false, true,
                    false, false, false, false, "minecraft:air", "minecraft:air", "minecraft:air", "minecraft:air", false,
                    0.0, 0.0, false, 0,
                    0f, 0f, 0f, 0f, ""
            );
        }
    }

    public record EmittedSignal(
            String type,
            String eventName,
            String playerName,
            JsonObject data,
            String skinUrl,
            String skinModel
    ) {
        public static EmittedSignal minecraftEvent(String eventName, String playerName, JsonObject data) {
            return new EmittedSignal("minecraft.event", eventName, playerName, data, null, null);
        }

        public static EmittedSignal skinSet(String playerName, String skinUrl, String skinModel) {
            return new EmittedSignal("skin.set", null, playerName, null, skinUrl, skinModel);
        }
    }

    private volatile boolean syncSkinOnJoin;
    private volatile boolean sendMovementEvents;

    private PlayerSnapshot previous = PlayerSnapshot.disconnected();
    private boolean joinedEmitted = false;
    private boolean skinSentForSession = false;

    // Estado filtrado de locomoción y ángulo de vista relativo para evitar parpadeos
    private boolean lastWalking = false;
    private boolean lastSprinting = false;
    private boolean lastSneaking = false;
    private boolean lastAirborne = false;
    private boolean lastSwinging = false;
    private boolean lastSwimming = false;
    private boolean lastFlying = false;
    private int stillTicks = 0;
    private float smoothedTurnYawDeg = 0f;
    private float lastLookPitchDeg = 0f;
    private float lastLookYawDeg = 0f;

    public ClientStateTracker(boolean syncSkinOnJoin, boolean sendMovementEvents) {
        this.syncSkinOnJoin = syncSkinOnJoin;
        this.sendMovementEvents = sendMovementEvents;
    }

    public void updateSettings(boolean syncSkinOnJoin, boolean sendMovementEvents) {
        this.syncSkinOnJoin = syncSkinOnJoin;
        this.sendMovementEvents = sendMovementEvents;
    }

    public void reset() {
        this.previous = PlayerSnapshot.disconnected();
        this.joinedEmitted = false;
        this.skinSentForSession = false;
        this.lastWalking = false;
        this.lastSprinting = false;
        this.lastSneaking = false;
        this.lastAirborne = false;
        this.lastSwinging = false;
        this.lastSwimming = false;
        this.lastFlying = false;
        this.stillTicks = 0;
        this.smoothedTurnYawDeg = 0f;
        this.lastLookPitchDeg = 0f;
        this.lastLookYawDeg = 0f;
    }

    public List<EmittedSignal> step(PlayerSnapshot current) {
        List<EmittedSignal> out = new ArrayList<>();
        PlayerSnapshot snap = current != null ? current : PlayerSnapshot.disconnected();

        if (!snap.inWorld()) {
            reset();
            return out;
        }

        String playerName = snap.playerName() != null ? snap.playerName().trim() : "";
        String normMain = normalizeItemKey(snap.mainHandItemKey());
        String normOff = normalizeItemKey(snap.offHandItemKey());
        String normDim = normalizeDimension(snap.dimensionId());

        String curHelmet = extractArmorMaterial(snap.helmetItemKey());
        String curChest = extractArmorMaterial(snap.chestplateItemKey());
        String curLegs = extractArmorMaterial(snap.leggingsItemKey());
        String curBoots = extractArmorMaterial(snap.bootsItemKey());

        // 1. Entrada a partida / servidor (o reconexión): sincroniza estado inicial completo
        if (!joinedEmitted) {
            joinedEmitted = true;
            JsonObject joinData = new JsonObject();
            if (snap.skinUrl() != null && !snap.skinUrl().isBlank()) {
                joinData.addProperty("skinUrl", snap.skinUrl());
            }
            String skinModel = normalizeSkinModel(snap.skinModel());
            joinData.addProperty("skinModel", skinModel);
            joinData.addProperty("mode", snap.singleplayer() ? "singleplayer" : "multiplayer");
            joinData.addProperty("dimension", normDim);
            if (snap.gameDir() != null && !snap.gameDir().isBlank()) {
                joinData.addProperty("gameDir", snap.gameDir());
            }
            out.add(EmittedSignal.minecraftEvent("player.join", playerName, joinData));

            if (syncSkinOnJoin && snap.skinUrl() != null && !snap.skinUrl().isBlank()) {
                skinSentForSession = true;
                out.add(EmittedSignal.skinSet(playerName, snap.skinUrl(), skinModel));
            }

            // Sincronización inmediata de ítem en mano, armadura y fuego al entrar
            out.add(EmittedSignal.minecraftEvent(
                    "player.held_item_change",
                    playerName,
                    buildHeldItemData(snap.selectedSlot(), normMain, normOff, snap.mainHandGlint(), snap.offHandGlint())
            ));
            out.add(EmittedSignal.minecraftEvent(
                    "player.armor_change",
                    playerName,
                    buildArmorData(curHelmet, curChest, curLegs, curBoots, snap.armorEnchanted())
            ));
            JsonObject fireInit = new JsonObject();
            fireInit.addProperty("active", snap.onFire());
            out.add(EmittedSignal.minecraftEvent("player.fire_state", playerName, fireInit));

            this.previous = snap;
            return out;
        }

        // Sincronizar skin si tardó unos ticks en resolver desde el perfil del cliente
        if (syncSkinOnJoin && !skinSentForSession && snap.skinUrl() != null && !snap.skinUrl().isBlank()) {
            skinSentForSession = true;
            out.add(EmittedSignal.skinSet(playerName, snap.skinUrl(), normalizeSkinModel(snap.skinModel())));
        }

        // 2. Cambio de dimensión
        String prevDim = normalizeDimension(previous.dimensionId());
        if (!prevDim.isEmpty() && !normDim.isEmpty() && !Objects.equals(prevDim, normDim)) {
            JsonObject dimData = new JsonObject();
            dimData.addProperty("fromWorld", prevDim);
            dimData.addProperty("toWorld", normDim);
            dimData.addProperty("toEnvironment", inferEnvironment(normDim));
            out.add(EmittedSignal.minecraftEvent("player.dimension_change", playerName, dimData));
        }

        // 3. Estado de fuego en tiempo real
        if (previous.onFire() != snap.onFire()) {
            JsonObject fireData = new JsonObject();
            fireData.addProperty("active", snap.onFire());
            out.add(EmittedSignal.minecraftEvent("player.fire_state", playerName, fireData));
        }

        // 4. Cambios de armadura (4 piezas + brillo de encantamiento)
        String prevHelmet = extractArmorMaterial(previous.helmetItemKey());
        String prevChest = extractArmorMaterial(previous.chestplateItemKey());
        String prevLegs = extractArmorMaterial(previous.leggingsItemKey());
        String prevBoots = extractArmorMaterial(previous.bootsItemKey());
        if (!Objects.equals(prevHelmet, curHelmet)
                || !Objects.equals(prevChest, curChest)
                || !Objects.equals(prevLegs, curLegs)
                || !Objects.equals(prevBoots, curBoots)
                || previous.armorEnchanted() != snap.armorEnchanted()) {
            out.add(EmittedSignal.minecraftEvent(
                    "player.armor_change",
                    playerName,
                    buildArmorData(curHelmet, curChest, curLegs, curBoots, snap.armorEnchanted())
            ));
        }

        // 5. Daño recibido (bajada real de vida o nuevo flanco de hurtTime)
        float healthDrop = previous.health() - snap.health();
        boolean newHurtEdge = snap.hurtTime() > previous.hurtTime() && snap.hurtTime() >= 6;
        if (!previous.dead() && (healthDrop >= 0.25f || newHurtEdge)) {
            double dmg = healthDrop > 0f ? Math.max(0.5, Math.round(healthDrop * 100.0) / 100.0) : 1.0;
            JsonObject dmgData = new JsonObject();
            dmgData.addProperty("damage", dmg);
            dmgData.addProperty("finalDamage", dmg);
            dmgData.addProperty("cause", snap.onFire() ? "fire" : "client_damage");
            dmgData.addProperty("onFire", snap.onFire());
            dmgData.addProperty("finalHealth", Math.max(0.0, Math.round(snap.health() * 100.0) / 100.0));
            out.add(EmittedSignal.minecraftEvent("player.damage", playerName, dmgData));
        }

        // 6. Muerte local y reaparición (respawn)
        boolean isNowDead = snap.dead() || snap.health() <= 0.0f;
        boolean wasDead = previous.dead() || previous.health() <= 0.0f;
        if (!wasDead && isNowDead) {
            JsonObject deathData = new JsonObject();
            deathData.addProperty("cause", snap.onFire() ? "fire" : "client_death");
            deathData.addProperty("world", normDim);
            out.add(EmittedSignal.minecraftEvent("player.death", playerName, deathData));
            resetLocomotionFlags();
        } else if (wasDead && !isNowDead) {
            JsonObject respawnData = new JsonObject();
            respawnData.addProperty("world", normDim);
            out.add(EmittedSignal.minecraftEvent("player.respawn", playerName, respawnData));
            resetLocomotionFlags();
        }

        // 7. Detección heurística de tótem de inmortalidad
        String prevMain = normalizeItemKey(previous.mainHandItemKey());
        String prevOff = normalizeItemKey(previous.offHandItemKey());
        boolean mainLostTotem = "minecraft:totem_of_undying".equals(prevMain) && !"minecraft:totem_of_undying".equals(normMain);
        boolean offLostTotem = "minecraft:totem_of_undying".equals(prevOff) && !"minecraft:totem_of_undying".equals(normOff);
        if (!isNowDead && (mainLostTotem || offLostTotem) && previous.selectedSlot() == snap.selectedSlot()
                && (snap.hurtTime() > 0 || previous.health() <= 6.0f)) {
            JsonObject totemData = new JsonObject();
            totemData.addProperty("hand", offLostTotem ? "off_hand" : "hand");
            out.add(EmittedSignal.minecraftEvent("player.totem_pop", playerName, totemData));
        }

        // 8. Consumo completado de ítem (comida/poción)
        if (previous.usingItem() && !snap.usingItem() && previous.itemUseTimeLeft() <= 2) {
            String consumedKey = normalizeItemKey(previous.activeUseItemKey());
            if (!"minecraft:air".equals(consumedKey)) {
                JsonObject consumeData = new JsonObject();
                consumeData.addProperty("material", toMaterialName(consumedKey));
                consumeData.addProperty("itemKey", consumedKey);
                out.add(EmittedSignal.minecraftEvent("player.item_consume", playerName, consumeData));
            }
        }

        // 9. Cambio de ítem en mano principal, mano secundaria, encantamiento o slot seleccionado
        if (previous.selectedSlot() != snap.selectedSlot()
                || !Objects.equals(prevMain, normMain)
                || !Objects.equals(prevOff, normOff)
                || previous.mainHandGlint() != snap.mainHandGlint()
                || previous.offHandGlint() != snap.offHandGlint()) {
            out.add(EmittedSignal.minecraftEvent(
                    "player.held_item_change",
                    playerName,
                    buildHeldItemData(snap.selectedSlot(), normMain, normOff, snap.mainHandGlint(), snap.offHandGlint())
            ));
        }

        // 10. Eventos de movimiento, mirada relativa y micro-animaciones
        if (sendMovementEvents && !isNowDead) {
            if (previous.sneaking() != snap.sneaking()) {
                JsonObject sneakData = new JsonObject();
                sneakData.addProperty("active", snap.sneaking());
                out.add(EmittedSignal.minecraftEvent("player.sneak", playerName, sneakData));
            }
            if (previous.sprinting() != snap.sprinting()) {
                JsonObject sprintData = new JsonObject();
                sprintData.addProperty("active", snap.sprinting());
                out.add(EmittedSignal.minecraftEvent("player.sprint", playerName, sprintData));
            }
            if (previous.onGround() && !snap.onGround()) {
                JsonObject jumpData = new JsonObject();
                jumpData.addProperty("dimension", normDim);
                out.add(EmittedSignal.minecraftEvent("player.jump", playerName, jumpData));
            }

            double dx = snap.posX() - previous.posX();
            double dz = snap.posZ() - previous.posZ();
            double horizSpeed = Math.hypot(dx, dz);
            if (!Double.isFinite(horizSpeed) || horizSpeed > TELEPORT_SPEED_CAP) {
                horizSpeed = 0.0;
            }

            boolean movingNow = horizSpeed >= WALK_SPEED_THRESHOLD;
            boolean effectiveMoving;
            if (movingNow) {
                stillTicks = 0;
                effectiveMoving = true;
            } else {
                stillTicks++;
                effectiveMoving = stillTicks < STOP_HYSTERESIS_TICKS && (lastWalking || lastSprinting);
            }

            boolean isFlying = snap.flying();
            boolean isSwimming = !isFlying && (snap.swimming() || (snap.inWater() && !snap.onGround()));
            boolean isSprintingMove = !isFlying && !isSwimming && effectiveMoving
                    && (snap.sprinting() || horizSpeed >= SPRINT_SPEED_THRESHOLD);
            boolean isWalkingMove = !isFlying && !isSwimming && effectiveMoving && !isSprintingMove;
            boolean isSneaking = !isSwimming && snap.sneaking();
            boolean isAirborne = !isFlying && !isSwimming && !snap.onGround();
            boolean isSwinging = snap.handSwinging();
            boolean swingPulse = isSwinging && (!previous.handSwinging() || snap.handSwingTicks() < previous.handSwingTicks());

            // Ángulo de vista relativo al cuerpo (ignora giros absolutos de 180°/360° para no romper la orientación en Studio)
            float deltaYaw = wrapDegrees(snap.yaw() - previous.yaw());
            if (!Float.isFinite(deltaYaw) || Math.abs(deltaYaw) > 120f) {
                deltaYaw = 0f;
            }
            smoothedTurnYawDeg = smoothedTurnYawDeg * 0.72f + clampFloat(deltaYaw * 0.85f, -28f, 28f) * 0.28f;
            if (Math.abs(smoothedTurnYawDeg) < 0.25f) {
                smoothedTurnYawDeg = 0f;
            }
            float headRelBody = clampFloat(wrapDegrees(snap.headYaw() - snap.bodyYaw()), -45f, 45f);
            float lookYawDeg = clampFloat(headRelBody * 0.82f + smoothedTurnYawDeg * 0.45f, -38f, 38f);
            float lookPitchDeg = clampFloat(Float.isFinite(snap.pitch()) ? snap.pitch() : 0f, -45f, 45f);

            boolean lookChanged = Math.abs(lookPitchDeg - lastLookPitchDeg) >= LOOK_ANGLE_EMIT_THRESHOLD_DEG
                    || Math.abs(lookYawDeg - lastLookYawDeg) >= LOOK_ANGLE_EMIT_THRESHOLD_DEG;

            if (isWalkingMove != lastWalking
                    || isSprintingMove != lastSprinting
                    || isSneaking != lastSneaking
                    || isAirborne != lastAirborne
                    || isSwinging != lastSwinging
                    || isSwimming != lastSwimming
                    || isFlying != lastFlying
                    || swingPulse
                    || lookChanged) {
                lastWalking = isWalkingMove;
                lastSprinting = isSprintingMove;
                lastSneaking = isSneaking;
                lastAirborne = isAirborne;
                lastSwinging = isSwinging;
                lastSwimming = isSwimming;
                lastFlying = isFlying;
                lastLookPitchDeg = lookPitchDeg;
                lastLookYawDeg = lookYawDeg;

                JsonObject loco = new JsonObject();
                loco.addProperty("walking", isWalkingMove);
                loco.addProperty("sprinting", isSprintingMove);
                loco.addProperty("sneaking", isSneaking);
                loco.addProperty("airborne", isAirborne);
                loco.addProperty("swinging", isSwinging);
                loco.addProperty("swimming", isSwimming);
                loco.addProperty("flying", isFlying);
                loco.addProperty("swingPulse", swingPulse);
                loco.addProperty("speed", Math.round(horizSpeed * 1000.0) / 1000.0);
                loco.addProperty("lookPitch", Math.round(lookPitchDeg * 10.0) / 10.0);
                loco.addProperty("lookYaw", Math.round(lookYawDeg * 10.0) / 10.0);
                out.add(EmittedSignal.minecraftEvent("player.locomotion", playerName, loco));
            }
        }

        this.previous = snap;
        return out;
    }

    private void resetLocomotionFlags() {
        lastWalking = false;
        lastSprinting = false;
        lastSneaking = false;
        lastAirborne = false;
        lastSwinging = false;
        lastSwimming = false;
        lastFlying = false;
        stillTicks = 0;
        smoothedTurnYawDeg = 0f;
        lastLookPitchDeg = 0f;
        lastLookYawDeg = 0f;
    }

    public static float wrapDegrees(float degrees) {
        if (!Float.isFinite(degrees)) {
            return 0f;
        }
        float v = degrees % 360f;
        if (v >= 180f) {
            v -= 360f;
        }
        if (v < -180f) {
            v += 360f;
        }
        return v;
    }

    private static float clampFloat(float value, float min, float max) {
        if (!Float.isFinite(value)) return 0f;
        return Math.max(min, Math.min(max, value));
    }

    private static JsonObject buildHeldItemData(
            int slot,
            String normMain,
            String normOff,
            boolean mainHandGlint,
            boolean offHandGlint
    ) {
        String mainMat = toMaterialName(normMain);
        String offMat = toMaterialName(normOff);
        JsonObject heldData = new JsonObject();
        heldData.addProperty("slot", slot);
        heldData.addProperty("material", mainMat);
        heldData.addProperty("item", mainMat);
        heldData.addProperty("itemKey", normMain);
        heldData.addProperty("mainHandGlint", mainHandGlint);
        heldData.addProperty("offHandMaterial", offMat);
        heldData.addProperty("offHandItemKey", normOff);
        heldData.addProperty("offHandGlint", offHandGlint);
        return heldData;
    }

    private static JsonObject buildArmorData(
            String helmet,
            String chestplate,
            String leggings,
            String boots,
            boolean enchanted
    ) {
        JsonObject armor = new JsonObject();
        armor.addProperty("helmet", helmet);
        armor.addProperty("chestplate", chestplate);
        armor.addProperty("leggings", leggings);
        armor.addProperty("boots", boots);
        armor.addProperty("enchanted", enchanted);
        return armor;
    }

    public static String extractArmorMaterial(String rawItemKey) {
        String norm = normalizeItemKey(rawItemKey);
        if ("minecraft:air".equals(norm)) {
            return "none";
        }
        int colon = norm.indexOf(':');
        String path = colon >= 0 ? norm.substring(colon + 1) : norm;
        if (path.startsWith("netherite_")) return "netherite";
        if (path.startsWith("diamond_")) return "diamond";
        if (path.startsWith("golden_") || path.startsWith("gold_")) return "gold";
        if (path.startsWith("iron_")) return "iron";
        if (path.startsWith("chainmail_")) return "chainmail";
        if (path.startsWith("leather_") || path.startsWith("turtle_")) return "leather";
        return "none";
    }

    public static String normalizeItemKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "minecraft:air";
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
    }

    public static String toMaterialName(String itemKey) {
        String norm = normalizeItemKey(itemKey);
        int colon = norm.indexOf(':');
        String path = colon >= 0 ? norm.substring(colon + 1) : norm;
        return path.toUpperCase(Locale.ROOT);
    }

    public static String normalizeSkinModel(String raw) {
        if (raw == null) {
            return "classic";
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        return (lower.contains("slim") || lower.contains("alex")) ? "slim" : "classic";
    }

    public static String normalizeDimension(String raw) {
        if (raw == null || raw.isBlank()) {
            return "minecraft:overworld";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    public static String inferEnvironment(String dimensionId) {
        String lower = normalizeDimension(dimensionId);
        if (lower.contains("nether")) {
            return "NETHER";
        }
        if (lower.contains("end")) {
            return "THE_END";
        }
        return "NORMAL";
    }
}
