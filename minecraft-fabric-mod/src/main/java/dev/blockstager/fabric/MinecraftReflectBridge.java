package dev.blockstager.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Puente ligero con resolución dinámica de nombres mediante
 * {@link MappingResolver} de Fabric Loader.
 *
 * <p>Compatible con toda la serie Minecraft 1.21–1.21.4+ (Intermediary / Yarn / Mojang)
 * sin enlazar ni empaquetar clases de Mojang en el JAR del mod. Captura vida,
 * daño, muerte, fuego, armadura (4 piezas + encantamiento), ítems en ambas manos,
 * posición (caminar/correr) y animación de golpeo/minado ({@code handSwinging}).</p>
 */
public final class MinecraftReflectBridge {

    private static final String INTERMEDIARY = "intermediary";

    private final Logger logger;
    private final MappingResolver resolver;
    private final String gameDirPath;
    private volatile boolean initialized = false;
    private volatile boolean available = false;
    private long lastSnapshotWarnMs = 0L;

    // MinecraftClient (class_310)
    private Class<?> clientClass;
    private Method clientGetInstance;
    private Field clientPlayerField;
    private Field clientWorldField;
    private Field clientInGameHudField;
    private Method clientGetServer;
    private Method clientIsInSingleplayer;
    private Method clientGetNetworkHandler;

    // Player / Living / Entity (class_1657 / class_1309 / class_1297 / class_742)
    private Method playerGetGameProfile;
    private Method playerGetInventory;
    private Method playerGetAbilities;
    private Field abilitiesFlyingField;
    private Method playerGetPermissionLevel;
    private Method playerSendMessage;
    private Method abstractPlayerGetSkinTextures;
    private Method skinTexturesUrl;
    private Method skinTexturesModel;

    private Method livingGetHealth;
    private Method livingGetMaxHealth;
    private Method livingIsDead;
    private Method livingGetMainHandStack;
    private Method livingGetOffHandStack;
    private Method livingGetEquippedStack;
    private Method livingIsUsingItem;
    private Method livingGetActiveItem;
    private Method livingGetItemUseTimeLeft;
    private Field livingHurtTimeField;
    private Field livingHandSwingingField;
    private Field livingHandSwingTicksField;
    private Field livingBodyYawField;
    private Field livingHeadYawField;

    // EquipmentSlot (class_1304)
    private Object slotHead;
    private Object slotChest;
    private Object slotLegs;
    private Object slotFeet;

    private Method entityIsSneaking;
    private Method entityIsSprinting;
    private Method entityIsOnGround;
    private Method entityIsOnFire;
    private Method entityIsSwimming;
    private Method entityIsTouchingWater;
    private Method entityGetX;
    private Method entityGetZ;
    private Method entityGetYaw;
    private Method entityGetPitch;
    private Method entityPlaySound;

    // Inventory & Items (class_1661 / class_1799 / class_7923 / class_2378)
    private Field inventorySelectedSlotField;
    private Field inventoryArmorField;
    private Method itemStackIsEmpty;
    private Method itemStackGetItem;
    private Method itemStackHasGlint;
    private Object itemRegistry;
    private Method registryGetId;

    // World & Dimension (class_1937 / class_5321)
    private Method worldGetRegistryKey;
    private Method registryKeyGetValue;

    // Text & HUD (class_2561 / class_329)
    private Method textLiteral;
    private Method hudSetTitleTicks;
    private Method hudSetTitle;
    private Method hudSetSubtitle;

    // SoundEvents (class_3417)
    private Object soundBell;
    private Object soundLevelUp;

    // IntegratedServer & Commands (MinecraftServer / class_2170 / class_634)
    private Method serverGetCommandManager;
    private Method serverGetCommandSource;
    private Method commandManagerExecuteWithPrefix;
    private Method networkHandlerSendChatCommand;

    public MinecraftReflectBridge(Logger logger) {
        this.logger = logger != null ? logger : Logger.getLogger(MinecraftReflectBridge.class.getName());
        this.resolver = FabricLoader.getInstance().getMappingResolver();
        String detectedGameDir = "";
        try {
            if (FabricLoader.getInstance().getGameDir() != null) {
                detectedGameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().toString();
            }
        } catch (Throwable ignored) {
            // Fallback vacío
        }
        this.gameDirPath = detectedGameDir;
    }

    public synchronized boolean ensureInitialized() {
        if (initialized) {
            return available;
        }
        initialized = true;
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = MinecraftReflectBridge.class.getClassLoader();
            }

            clientClass = loadMappedClass(cl, "net.minecraft.class_310");
            clientGetInstance = findMappedMethod(clientClass, "net.minecraft.class_310", "method_1551", "()Lnet/minecraft/class_310;");
            clientPlayerField = findMappedField(clientClass, "net.minecraft.class_310", "field_1724", "Lnet/minecraft/class_746;");
            clientWorldField = findMappedField(clientClass, "net.minecraft.class_310", "field_1687", "Lnet/minecraft/class_638;");
            clientInGameHudField = findMappedFieldOptional(clientClass, "net.minecraft.class_310", "field_1705", "Lnet/minecraft/class_329;");
            clientGetServer = findMappedMethodOptional(clientClass, "net.minecraft.class_310", "method_1576", "()Lnet/minecraft/class_1132;");
            clientIsInSingleplayer = findMappedMethodOptional(clientClass, "net.minecraft.class_310", "method_1542", "()Z");
            clientGetNetworkHandler = findMappedMethodOptional(clientClass, "net.minecraft.class_310", "method_1562", "()Lnet/minecraft/class_634;");

            Class<?> entityClass = loadMappedClass(cl, "net.minecraft.class_1297");
            Class<?> livingClass = loadMappedClass(cl, "net.minecraft.class_1309");
            Class<?> playerClass = loadMappedClass(cl, "net.minecraft.class_1657");
            Class<?> abstractClientPlayerClass = loadMappedClassOptional(cl, "net.minecraft.class_742");
            Class<?> skinTexturesClass = loadMappedClassOptional(cl, "net.minecraft.class_8685");
            Class<?> equipmentSlotClass = loadMappedClassOptional(cl, "net.minecraft.class_1304");
            Class<?> playerAbilitiesClass = loadMappedClassOptional(cl, "net.minecraft.class_1656");
            Class<?> inventoryClass = loadMappedClassOptional(cl, "net.minecraft.class_1661");
            Class<?> itemStackClass = loadMappedClassOptional(cl, "net.minecraft.class_1799");
            Class<?> registriesClass = loadMappedClassOptional(cl, "net.minecraft.class_7923");
            Class<?> registryClass = loadMappedClassOptional(cl, "net.minecraft.class_2378");
            Class<?> worldClass = loadMappedClassOptional(cl, "net.minecraft.class_1937");
            Class<?> registryKeyClass = loadMappedClassOptional(cl, "net.minecraft.class_5321");
            Class<?> textClass = loadMappedClassOptional(cl, "net.minecraft.class_2561");
            Class<?> hudClass = loadMappedClassOptional(cl, "net.minecraft.class_329");
            Class<?> soundEventClass = loadMappedClassOptional(cl, "net.minecraft.class_3414");
            Class<?> soundEventsClass = loadMappedClassOptional(cl, "net.minecraft.class_3417");
            Class<?> serverClass = loadMappedClassOptional(cl, "net.minecraft.server.MinecraftServer");
            Class<?> commandManagerClass = loadMappedClassOptional(cl, "net.minecraft.class_2170");
            Class<?> serverCommandSourceClass = loadMappedClassOptional(cl, "net.minecraft.class_2168");
            Class<?> networkHandlerClass = loadMappedClassOptional(cl, "net.minecraft.class_634");

            playerGetGameProfile = findMappedMethodOptional(playerClass, "net.minecraft.class_1657", "method_7334", "()Lcom/mojang/authlib/GameProfile;");
            playerGetInventory = findMappedMethodOptional(playerClass, "net.minecraft.class_1657", "method_31548", "()Lnet/minecraft/class_1661;");
            playerGetAbilities = findMappedMethodOptional(playerClass, "net.minecraft.class_1657", "method_31549", "()Lnet/minecraft/class_1656;");
            abilitiesFlyingField = findMappedFieldOptional(playerAbilitiesClass, "net.minecraft.class_1656", "field_7479", "Z");
            playerGetPermissionLevel = findMappedMethodOptional(playerClass, "net.minecraft.class_1657", "method_5691", "()I");
            playerSendMessage = findMappedMethodOptional(playerClass, "net.minecraft.class_1657", "method_7353", "(Lnet/minecraft/class_2561;Z)V", textClass, boolean.class);

            abstractPlayerGetSkinTextures = findMappedMethodOptional(abstractClientPlayerClass, "net.minecraft.class_742", "method_52814", "()Lnet/minecraft/class_8685;");
            if (skinTexturesClass != null) {
                skinTexturesUrl = findMappedMethodOptional(skinTexturesClass, "net.minecraft.class_8685", "comp_1911", "()Ljava/lang/String;");
                skinTexturesModel = findMappedMethodOptional(skinTexturesClass, "net.minecraft.class_8685", "comp_1629", "()Lnet/minecraft/class_8685$class_7920;");
            }

            livingGetHealth = findMappedMethodOptional(livingClass, "net.minecraft.class_1309", "method_6032", "()F");
            livingGetMaxHealth = findMappedMethodOptional(livingClass, "net.minecraft.class_1309", "method_6063", "()F");
            livingIsDead = findMappedMethodOptional(livingClass, "net.minecraft.class_1309", "method_29504", "()Z");
            livingGetMainHandStack = findMappedMethodOptional(livingClass, "net.minecraft.class_1309", "method_6047", "()Lnet/minecraft/class_1799;");
            livingGetOffHandStack = findMappedMethodOptional(livingClass, "net.minecraft.class_1309", "method_6079", "()Lnet/minecraft/class_1799;");
            livingGetEquippedStack = findMappedMethodOptional(livingClass, "net.minecraft.class_1309", "method_6118", "(Lnet/minecraft/class_1304;)Lnet/minecraft/class_1799;", equipmentSlotClass);
            livingIsUsingItem = findMappedMethodOptional(livingClass, "net.minecraft.class_1309", "method_6115", "()Z");
            livingGetActiveItem = findMappedMethodOptional(livingClass, "net.minecraft.class_1309", "method_6030", "()Lnet/minecraft/class_1799;");
            livingGetItemUseTimeLeft = findMappedMethodOptional(livingClass, "net.minecraft.class_1309", "method_6014", "()I");
            livingHurtTimeField = findMappedFieldOptional(livingClass, "net.minecraft.class_1309", "field_6235", "I");
            livingHandSwingingField = findMappedFieldOptional(livingClass, "net.minecraft.class_1309", "field_6252", "Z");
            livingHandSwingTicksField = findMappedFieldOptional(livingClass, "net.minecraft.class_1309", "field_6279", "I");
            livingBodyYawField = findMappedFieldOptional(livingClass, "net.minecraft.class_1309", "field_6283", "F");
            livingHeadYawField = findMappedFieldOptional(livingClass, "net.minecraft.class_1309", "field_6241", "F");

            resolveEquipmentSlots(equipmentSlotClass);

            entityIsSneaking = findMappedMethodOptional(entityClass, "net.minecraft.class_1297", "method_5715", "()Z");
            entityIsSprinting = findMappedMethodOptional(entityClass, "net.minecraft.class_1297", "method_5624", "()Z");
            entityIsOnGround = findMappedMethodOptional(entityClass, "net.minecraft.class_1297", "method_24828", "()Z");
            entityIsOnFire = findMappedMethodOptional(entityClass, "net.minecraft.class_1297", "method_5809", "()Z");
            entityIsSwimming = findMappedMethodOptional(entityClass, "net.minecraft.class_1297", "method_5681", "()Z");
            entityIsTouchingWater = findMappedMethodOptional(entityClass, "net.minecraft.class_1297", "method_5799", "()Z");
            entityGetX = findMappedMethodOptional(entityClass, "net.minecraft.class_1297", "method_23317", "()D");
            entityGetZ = findMappedMethodOptional(entityClass, "net.minecraft.class_1297", "method_23321", "()D");
            entityGetYaw = findMappedMethodOptional(entityClass, "net.minecraft.class_1297", "method_36454", "()F");
            entityGetPitch = findMappedMethodOptional(entityClass, "net.minecraft.class_1297", "method_36455", "()F");
            entityPlaySound = findMappedMethodOptional(entityClass, "net.minecraft.class_1297", "method_5783", "(Lnet/minecraft/class_3414;FF)V", soundEventClass, float.class, float.class);

            inventorySelectedSlotField = findMappedFieldOptional(inventoryClass, "net.minecraft.class_1661", "field_7545", "I");
            inventoryArmorField = findMappedFieldOptional(inventoryClass, "net.minecraft.class_1661", "field_7548", "Lnet/minecraft/class_2371;");
            itemStackIsEmpty = findMappedMethodOptional(itemStackClass, "net.minecraft.class_1799", "method_7960", "()Z");
            itemStackGetItem = findMappedMethodOptional(itemStackClass, "net.minecraft.class_1799", "method_7909", "()Lnet/minecraft/class_1792;");
            itemStackHasGlint = findMappedMethodOptional(itemStackClass, "net.minecraft.class_1799", "method_7958", "()Z");

            Field itemRegField = findMappedFieldOptional(registriesClass, "net.minecraft.class_7923", "field_41178", "Lnet/minecraft/class_7922;");
            if (itemRegField != null) {
                itemRegistry = itemRegField.get(null);
            }
            registryGetId = findMappedMethodOptional(registryClass, "net.minecraft.class_2378", "method_10221", "(Ljava/lang/Object;)Lnet/minecraft/class_2960;", Object.class);

            worldGetRegistryKey = findMappedMethodOptional(worldClass, "net.minecraft.class_1937", "method_27983", "()Lnet/minecraft/class_5321;");
            registryKeyGetValue = findMappedMethodOptional(registryKeyClass, "net.minecraft.class_5321", "method_29177", "()Lnet/minecraft/class_2960;");

            textLiteral = findMappedMethodOptional(textClass, "net.minecraft.class_2561", "method_43470", "(Ljava/lang/String;)Lnet/minecraft/class_5250;", String.class);
            hudSetTitleTicks = findMappedMethodOptional(hudClass, "net.minecraft.class_329", "method_34001", "(III)V", int.class, int.class, int.class);
            hudSetTitle = findMappedMethodOptional(hudClass, "net.minecraft.class_329", "method_34004", "(Lnet/minecraft/class_2561;)V", textClass);
            hudSetSubtitle = findMappedMethodOptional(hudClass, "net.minecraft.class_329", "method_34002", "(Lnet/minecraft/class_2561;)V", textClass);

            Field bellField = findMappedFieldOptional(soundEventsClass, "net.minecraft.class_3417", "field_17265", "Lnet/minecraft/class_3414;");
            if (bellField != null) {
                soundBell = bellField.get(null);
            }
            Field levelField = findMappedFieldOptional(soundEventsClass, "net.minecraft.class_3417", "field_14709", "Lnet/minecraft/class_3414;");
            if (levelField != null) {
                soundLevelUp = levelField.get(null);
            }

            serverGetCommandManager = findMappedMethodOptional(serverClass, "net.minecraft.server.MinecraftServer", "method_3734", "()Lnet/minecraft/class_2170;");
            serverGetCommandSource = findMappedMethodOptional(serverClass, "net.minecraft.server.MinecraftServer", "method_3739", "()Lnet/minecraft/class_2168;");
            commandManagerExecuteWithPrefix = findMappedMethodOptional(commandManagerClass, "net.minecraft.class_2170", "method_44252", "(Lnet/minecraft/class_2168;Ljava/lang/String;)V", serverCommandSourceClass, String.class);
            networkHandlerSendChatCommand = findMappedMethodOptional(networkHandlerClass, "net.minecraft.class_634", "method_45730", "(Ljava/lang/String;)V", String.class);

            available = (clientClass != null && clientGetInstance != null && clientPlayerField != null && clientWorldField != null);
            logger.info("[Blockstager Fabric] Puente de cliente inicializado (namespace="
                    + resolver.getCurrentRuntimeNamespace()
                    + ", disponible=" + available
                    + ", armor=" + (livingGetEquippedStack != null || inventoryArmorField != null)
                    + ", fire=" + (entityIsOnFire != null)
                    + ", swing=" + (livingHandSwingingField != null) + ").");
        } catch (Throwable ex) {
            available = false;
            logger.log(Level.WARNING, "[Blockstager Fabric] No se pudo inicializar el puente de Minecraft: " + ex.getMessage());
        }
        return available;
    }

    private void resolveEquipmentSlots(Class<?> equipmentSlotClass) {
        if (equipmentSlotClass == null || !equipmentSlotClass.isEnum()) {
            return;
        }
        Object[] constants = equipmentSlotClass.getEnumConstants();
        if (constants == null) {
            return;
        }
        Method getNameMethod = findMappedMethodOptional(equipmentSlotClass, "net.minecraft.class_1304", "method_5923", "()Ljava/lang/String;");
        for (Object c : constants) {
            String key = "";
            if (getNameMethod != null) {
                try {
                    Object res = getNameMethod.invoke(c);
                    if (res != null) {
                        key = res.toString().toLowerCase(Locale.ROOT);
                    }
                } catch (Throwable ignored) {
                    // Fallback al nombre enum
                }
            }
            if (key.isEmpty()) {
                key = c.toString().toLowerCase(Locale.ROOT);
            }
            switch (key) {
                case "head" -> slotHead = c;
                case "chest" -> slotChest = c;
                case "legs" -> slotLegs = c;
                case "feet" -> slotFeet = c;
                default -> {
                }
            }
        }
        // Fallback por orden estándar Minecraft [MAINHAND, OFFHAND, FEET, LEGS, CHEST, HEAD]
        if (slotFeet == null && constants.length >= 6) {
            slotFeet = constants[2];
            slotLegs = constants[3];
            slotChest = constants[4];
            slotHead = constants[5];
        }
    }

    public Object getClientInstance() {
        if (!ensureInitialized()) {
            return null;
        }
        try {
            return clientGetInstance.invoke(null);
        } catch (Throwable ex) {
            return null;
        }
    }

    public void executeOnClientThread(Runnable task) {
        if (task == null) {
            return;
        }
        Object client = getClientInstance();
        if (client instanceof Executor executor) {
            executor.execute(task);
        }
    }

    public ClientStateTracker.PlayerSnapshot captureSnapshot(Object clientObj) {
        if (!ensureInitialized() || clientObj == null) {
            return ClientStateTracker.PlayerSnapshot.disconnected();
        }
        try {
            Object player = clientPlayerField.get(clientObj);
            Object world = clientWorldField.get(clientObj);
            if (player == null || world == null) {
                return ClientStateTracker.PlayerSnapshot.disconnected();
            }

            Object integratedServer = clientGetServer != null ? clientGetServer.invoke(clientObj) : null;
            boolean inSingleplayer = integratedServer != null
                    || (clientIsInSingleplayer != null && Boolean.TRUE.equals(clientIsInSingleplayer.invoke(clientObj)));

            int permLevel = 0;
            if (playerGetPermissionLevel != null) {
                Object val = safeInvoke(playerGetPermissionLevel, player);
                if (val instanceof Number n) {
                    permLevel = n.intValue();
                }
            }

            String playerName = extractPlayerName(player);
            String skinUrl = null;
            String skinModel = "classic";
            if (abstractPlayerGetSkinTextures != null) {
                Object skinTex = safeInvoke(abstractPlayerGetSkinTextures, player);
                if (skinTex != null) {
                    if (skinTexturesUrl != null) {
                        Object urlVal = safeInvoke(skinTexturesUrl, skinTex);
                        if (urlVal != null) {
                            skinUrl = urlVal.toString();
                        }
                    }
                    if (skinTexturesModel != null) {
                        Object modelVal = safeInvoke(skinTexturesModel, skinTex);
                        if (modelVal != null) {
                            skinModel = ClientStateTracker.normalizeSkinModel(modelVal.toString());
                        }
                    }
                }
            }

            String dimensionId = extractDimensionId(world);
            float health = safeFloat(safeInvoke(livingGetHealth, player), 20f);
            float maxHealth = safeFloat(safeInvoke(livingGetMaxHealth, player), 20f);
            boolean dead = Boolean.TRUE.equals(safeInvoke(livingIsDead, player)) || health <= 0f;
            int hurtTime = safeIntField(livingHurtTimeField, player, 0);

            int selectedSlot = 0;
            Object inventory = safeInvoke(playerGetInventory, player);
            if (inventory != null && inventorySelectedSlotField != null) {
                selectedSlot = safeIntField(inventorySelectedSlotField, inventory, 0);
            }

            Object mainHandStack = safeInvoke(livingGetMainHandStack, player);
            Object offHandStack = safeInvoke(livingGetOffHandStack, player);
            String mainHandKey = extractItemStackId(mainHandStack);
            String offHandKey = extractItemStackId(offHandStack);
            boolean mainHandGlint = hasStackGlint(mainHandStack);
            boolean offHandGlint = hasStackGlint(offHandStack);

            boolean usingItem = Boolean.TRUE.equals(safeInvoke(livingIsUsingItem, player));
            String activeUseKey = usingItem
                    ? extractItemStackId(safeInvoke(livingGetActiveItem, player))
                    : "minecraft:air";
            int useTimeLeft = usingItem ? safeInt(safeInvoke(livingGetItemUseTimeLeft, player), 0) : 0;

            boolean sneaking = Boolean.TRUE.equals(safeInvoke(entityIsSneaking, player));
            boolean sprinting = Boolean.TRUE.equals(safeInvoke(entityIsSprinting, player));
            boolean onGround = !Boolean.FALSE.equals(safeInvoke(entityIsOnGround, player));
            boolean onFire = Boolean.TRUE.equals(safeInvoke(entityIsOnFire, player));
            boolean swimming = Boolean.TRUE.equals(safeInvoke(entityIsSwimming, player));
            boolean inWater = Boolean.TRUE.equals(safeInvoke(entityIsTouchingWater, player));
            Object abilities = safeInvoke(playerGetAbilities, player);
            boolean flying = safeBoolField(abilitiesFlyingField, abilities, false);

            double posX = safeDouble(safeInvoke(entityGetX, player), 0.0);
            double posZ = safeDouble(safeInvoke(entityGetZ, player), 0.0);
            float yaw = safeFloat(safeInvoke(entityGetYaw, player), 0f);
            float pitch = safeFloat(safeInvoke(entityGetPitch, player), 0f);
            float bodyYaw = safeFloatField(livingBodyYawField, player, yaw);
            float headYaw = safeFloatField(livingHeadYawField, player, yaw);
            boolean handSwinging = safeBoolField(livingHandSwingingField, player, false);
            int handSwingTicks = safeIntField(livingHandSwingTicksField, player, 0);

            // Armadura (4 piezas + glint de encantamiento) con soporte 1.21–1.21.4+
            Object helmetStack = extractArmorStack(player, inventory, slotHead, 3);
            Object chestStack = extractArmorStack(player, inventory, slotChest, 2);
            Object legsStack = extractArmorStack(player, inventory, slotLegs, 1);
            Object bootsStack = extractArmorStack(player, inventory, slotFeet, 0);

            String helmetKey = extractItemStackId(helmetStack);
            String chestKey = extractItemStackId(chestStack);
            String legsKey = extractItemStackId(legsStack);
            String bootsKey = extractItemStackId(bootsStack);
            boolean armorEnchanted = hasStackGlint(helmetStack)
                    || hasStackGlint(chestStack)
                    || hasStackGlint(legsStack)
                    || hasStackGlint(bootsStack);

            return new ClientStateTracker.PlayerSnapshot(
                    true,
                    inSingleplayer,
                    permLevel,
                    playerName,
                    skinUrl,
                    skinModel,
                    dimensionId,
                    health,
                    maxHealth,
                    dead,
                    hurtTime,
                    selectedSlot,
                    mainHandKey,
                    offHandKey,
                    mainHandGlint,
                    offHandGlint,
                    usingItem,
                    activeUseKey,
                    useTimeLeft,
                    sneaking,
                    sprinting,
                    onGround,
                    onFire,
                    swimming,
                    inWater,
                    flying,
                    helmetKey,
                    chestKey,
                    legsKey,
                    bootsKey,
                    armorEnchanted,
                    posX,
                    posZ,
                    handSwinging,
                    handSwingTicks,
                    pitch,
                    yaw,
                    bodyYaw,
                    headYaw,
                    gameDirPath
            );
        } catch (Throwable ex) {
            long now = System.currentTimeMillis();
            if (now - lastSnapshotWarnMs > 10_000L) {
                lastSnapshotWarnMs = now;
                logger.log(Level.WARNING, "[Blockstager Fabric] Error capturando estado del jugador: " + ex.getMessage());
            }
            return ClientStateTracker.PlayerSnapshot.disconnected();
        }
    }

    private Object extractArmorStack(Object player, Object inventory, Object eqSlot, int invArmorIdx) {
        if (livingGetEquippedStack != null && eqSlot != null) {
            try {
                Object stack = livingGetEquippedStack.invoke(player, eqSlot);
                if (stack != null) {
                    return stack;
                }
            } catch (Throwable ignored) {
                // Fallback a PlayerInventory.armor
            }
        }
        if (inventory != null && inventoryArmorField != null) {
            try {
                Object listObj = inventoryArmorField.get(inventory);
                if (listObj instanceof List<?> armorList && invArmorIdx >= 0 && invArmorIdx < armorList.size()) {
                    return armorList.get(invArmorIdx);
                }
            } catch (Throwable ignored) {
                // Ignorar
            }
        }
        return null;
    }

    private boolean hasStackGlint(Object stack) {
        if (stack == null || itemStackHasGlint == null) {
            return false;
        }
        try {
            if (itemStackIsEmpty != null && Boolean.TRUE.equals(itemStackIsEmpty.invoke(stack))) {
                return false;
            }
            return Boolean.TRUE.equals(itemStackHasGlint.invoke(stack));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object safeInvoke(Method m, Object target) {
        if (m == null || target == null) return null;
        try {
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float safeFloat(Object val, float def) {
        return val instanceof Number n ? n.floatValue() : def;
    }

    private static double safeDouble(Object val, double def) {
        return val instanceof Number n ? n.doubleValue() : def;
    }

    private static int safeInt(Object val, int def) {
        return val instanceof Number n ? n.intValue() : def;
    }

    private static int safeIntField(Field f, Object target, int def) {
        if (f == null || target == null) return def;
        try {
            return f.getInt(target);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static boolean safeBoolField(Field f, Object target, boolean def) {
        if (f == null || target == null) return def;
        try {
            return f.getBoolean(target);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static float safeFloatField(Field f, Object target, float def) {
        if (f == null || target == null) return def;
        try {
            return f.getFloat(target);
        } catch (Throwable ignored) {
            return def;
        }
    }

    public Object createLiteralText(String message) {
        if (!ensureInitialized() || textLiteral == null) {
            return null;
        }
        try {
            return textLiteral.invoke(null, message != null ? message : "");
        } catch (Throwable ex) {
            return null;
        }
    }

    public void sendLocalChatFeedback(String message, boolean actionBar) {
        Object client = getClientInstance();
        if (client == null || playerSendMessage == null) {
            return;
        }
        try {
            Object player = clientPlayerField.get(client);
            Object text = createLiteralText(message);
            if (player != null && text != null) {
                playerSendMessage.invoke(player, text, actionBar);
            }
        } catch (Throwable ignored) {
            // Ignorar si no hay jugador activo
        }
    }

    public boolean showLocalTitle(String titleText, String subtitleText) {
        Object client = getClientInstance();
        if (client == null || clientInGameHudField == null || hudSetTitle == null) {
            return false;
        }
        try {
            Object hud = clientInGameHudField.get(client);
            if (hud == null) {
                return false;
            }
            if (hudSetTitleTicks != null) {
                hudSetTitleTicks.invoke(hud, 10, 45, 15);
            }
            Object titleObj = createLiteralText(titleText != null && !titleText.isBlank() ? titleText : "Blockstager");
            if (titleObj != null) {
                hudSetTitle.invoke(hud, titleObj);
            }
            if (hudSetSubtitle != null && subtitleText != null && !subtitleText.isBlank()) {
                Object subObj = createLiteralText(subtitleText);
                if (subObj != null) {
                    hudSetSubtitle.invoke(hud, subObj);
                }
            }
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    public boolean playLocalSound(String fxToken) {
        Object client = getClientInstance();
        if (client == null || entityPlaySound == null) {
            return false;
        }
        try {
            Object player = clientPlayerField.get(client);
            if (player == null) {
                return false;
            }
            Object sound = "sound_bell".equalsIgnoreCase(fxToken)
                    ? (soundBell != null ? soundBell : soundLevelUp)
                    : (soundLevelUp != null ? soundLevelUp : soundBell);
            if (sound == null) {
                return false;
            }
            entityPlaySound.invoke(player, sound, 1.0f, 1.0f);
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    public boolean executeOnIntegratedServer(List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return false;
        }
        Object client = getClientInstance();
        if (client == null || clientGetServer == null
                || serverGetCommandManager == null
                || serverGetCommandSource == null
                || commandManagerExecuteWithPrefix == null) {
            return false;
        }
        try {
            Object server = clientGetServer.invoke(client);
            if (!(server instanceof Executor serverExecutor)) {
                return false;
            }
            serverExecutor.execute(() -> {
                try {
                    Object cmdMgr = serverGetCommandManager.invoke(server);
                    Object source = serverGetCommandSource.invoke(server);
                    if (cmdMgr == null || source == null) {
                        return;
                    }
                    for (String cmd : commands) {
                        commandManagerExecuteWithPrefix.invoke(cmdMgr, source, cmd);
                    }
                } catch (Throwable ex) {
                    logger.log(Level.WARNING, "[Blockstager Fabric] Error ejecutando comando en servidor integrado: " + ex.getMessage());
                }
            });
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    public boolean sendMultiplayerOpCommands(List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return false;
        }
        Object client = getClientInstance();
        if (client == null || clientGetNetworkHandler == null || networkHandlerSendChatCommand == null) {
            return false;
        }
        try {
            Object handler = clientGetNetworkHandler.invoke(client);
            if (handler == null) {
                return false;
            }
            for (String cmd : commands) {
                String clean = cmd.startsWith("/") ? cmd.substring(1) : cmd;
                networkHandlerSendChatCommand.invoke(handler, clean);
            }
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    private String extractPlayerName(Object player) {
        try {
            Object profile = safeInvoke(playerGetGameProfile, player);
            if (profile != null) {
                Method getName = profile.getClass().getMethod("getName");
                Object name = getName.invoke(profile);
                if (name != null) {
                    return name.toString();
                }
            }
        } catch (Throwable ignored) {
            // Fallback vacío
        }
        return "";
    }

    private String extractDimensionId(Object world) {
        try {
            if (worldGetRegistryKey != null && registryKeyGetValue != null) {
                Object regKey = worldGetRegistryKey.invoke(world);
                if (regKey != null) {
                    Object id = registryKeyGetValue.invoke(regKey);
                    if (id != null) {
                        return id.toString().toLowerCase(Locale.ROOT);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fallback overworld
        }
        return "minecraft:overworld";
    }

    private String extractItemStackId(Object stack) {
        if (stack == null || itemStackIsEmpty == null || itemStackGetItem == null) {
            return "minecraft:air";
        }
        try {
            if (Boolean.TRUE.equals(itemStackIsEmpty.invoke(stack))) {
                return "minecraft:air";
            }
            Object item = itemStackGetItem.invoke(stack);
            if (item != null && itemRegistry != null && registryGetId != null) {
                Object id = registryGetId.invoke(itemRegistry, item);
                if (id != null) {
                    return id.toString().toLowerCase(Locale.ROOT);
                }
            }
        } catch (Throwable ignored) {
            // Fallback air
        }
        return "minecraft:air";
    }

    private Class<?> loadMappedClass(ClassLoader cl, String intermediaryName) throws ClassNotFoundException {
        String mapped = resolver.mapClassName(INTERMEDIARY, intermediaryName);
        return Class.forName(mapped, false, cl);
    }

    private Class<?> loadMappedClassOptional(ClassLoader cl, String intermediaryName) {
        try {
            return loadMappedClass(cl, intermediaryName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Field findMappedField(Class<?> owner, String intermediaryOwner, String intermediaryField, String descriptor)
            throws NoSuchFieldException {
        Field f = findMappedFieldOptional(owner, intermediaryOwner, intermediaryField, descriptor);
        if (f == null) {
            throw new NoSuchFieldException(intermediaryOwner + "." + intermediaryField);
        }
        return f;
    }

    private Field findMappedFieldOptional(Class<?> owner, String intermediaryOwner, String intermediaryField, String descriptor) {
        if (owner == null) {
            return null;
        }
        String mappedName = resolver.mapFieldName(INTERMEDIARY, intermediaryOwner, intermediaryField, descriptor);
        Class<?> cursor = owner;
        while (cursor != null) {
            try {
                Field f = cursor.getDeclaredField(mappedName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    private Method findMappedMethod(Class<?> owner, String intermediaryOwner, String intermediaryMethod, String descriptor, Class<?>... params)
            throws NoSuchMethodException {
        Method m = findMappedMethodOptional(owner, intermediaryOwner, intermediaryMethod, descriptor, params);
        if (m == null) {
            throw new NoSuchMethodException(intermediaryOwner + "." + intermediaryMethod + descriptor);
        }
        return m;
    }

    private Method findMappedMethodOptional(Class<?> owner, String intermediaryOwner, String intermediaryMethod, String descriptor, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        for (Class<?> p : params) {
            if (p == null) {
                return null;
            }
        }
        String mappedName = resolver.mapMethodName(INTERMEDIARY, intermediaryOwner, intermediaryMethod, descriptor);
        try {
            Method m = owner.getMethod(mappedName, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
            // Intentar en jerarquía declarada
        }
        Class<?> cursor = owner;
        while (cursor != null) {
            try {
                Method m = cursor.getDeclaredMethod(mappedName, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }
}
