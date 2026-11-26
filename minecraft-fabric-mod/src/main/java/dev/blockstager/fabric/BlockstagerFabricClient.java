package dev.blockstager.fabric;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Punto de entrada oficial del mod cliente Fabric 1.21+ de Blockstager Studio.
 *
 * <p>Conecta el cliente Minecraft con el servidor local de Blockstager Studio
 * ({@code blockstager.proto/1.2}) en cualquier servidor público (eventos locales
 * de lectura) y ejecuta acciones de mundo seguras únicamente en Un jugador
 * (servidor integrado) o cuando el usuario tiene permiso de operador y activó
 * el opt-in explícito.</p>
 */
public final class BlockstagerFabricClient implements ClientModInitializer {

    private static final Logger LOGGER = Logger.getLogger("BlockstagerFabric");

    private Path configPath;
    private volatile FabricModConfig config;
    private MinecraftReflectBridge reflectBridge;
    private ClientStateTracker stateTracker;
    private ClientActionPolicy actionPolicy;
    private ClientActionExecutor actionExecutor;
    private FabricClientCommands clientCommands;
    private volatile FabricWsBridgeClient bridgeClient;
    private volatile boolean wasWsAuthenticated = false;

    @Override
    public void onInitializeClient() {
        this.configPath = FabricLoader.getInstance().getConfigDir().resolve(FabricModConfig.FILE_NAME);
        try {
            this.config = FabricModConfig.loadOrCreate(configPath);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "[Blockstager Fabric] No se pudo cargar config; usando valores por defecto: " + ex.getMessage());
            this.config = FabricModConfig.defaults();
        }

        this.reflectBridge = new MinecraftReflectBridge(LOGGER);
        this.stateTracker = new ClientStateTracker(config.syncSkinOnJoin, config.sendMovementEvents);
        this.actionPolicy = new ClientActionPolicy(config.toPolicySettings());
        this.actionExecutor = new ClientActionExecutor(this, reflectBridge, actionPolicy, LOGGER);
        this.clientCommands = new FabricClientCommands(this, reflectBridge);

        registerFabricEvents();
        startOrRestartBridge();

        LOGGER.info("[Blockstager Fabric] Mod cliente iniciado (config=" + configPath + ").");
    }

    public FabricWsBridgeClient getBridgeClient() {
        return bridgeClient;
    }

    public ClientActionPolicy getActionPolicy() {
        return actionPolicy;
    }

    public ClientActionExecutor getActionExecutor() {
        return actionExecutor;
    }

    public synchronized boolean reloadConfiguration() {
        try {
            FabricModConfig loaded = FabricModConfig.loadOrCreate(configPath);
            this.config = loaded;
            this.stateTracker.updateSettings(loaded.syncSkinOnJoin, loaded.sendMovementEvents);
            this.actionPolicy.updateSettings(loaded.toPolicySettings());
            startOrRestartBridge();
            return true;
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "[Blockstager Fabric] Error recargando configuración: " + ex.getMessage());
            return false;
        }
    }

    public synchronized void setAllowRemoteActions(boolean enabled) {
        if (config == null) {
            config = FabricModConfig.defaults();
        }
        config.allowRemoteActions = enabled;
        actionPolicy.setAllowRemoteActions(enabled);
        try {
            FabricModConfig.saveAtomic(configPath, config);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "[Blockstager Fabric] No se pudo guardar config: " + ex.getMessage());
        }
    }

    public synchronized void reconnectBridge() {
        FabricWsBridgeClient ws = this.bridgeClient;
        if (ws != null) {
            ws.reconnectNow();
        } else {
            startOrRestartBridge();
        }
    }

    public boolean syncCurrentPlayerSkin() {
        FabricWsBridgeClient ws = this.bridgeClient;
        if (ws == null) {
            return false;
        }
        ClientStateTracker.PlayerSnapshot snap = reflectBridge.captureSnapshot(reflectBridge.getClientInstance());
        if (!snap.inWorld() || snap.skinUrl() == null || snap.skinUrl().isBlank()) {
            return false;
        }
        return ws.sendSkinSet(snap.playerName(), snap.skinUrl(), snap.skinModel());
    }

    private synchronized void startOrRestartBridge() {
        FabricWsBridgeClient prev = this.bridgeClient;
        if (prev != null) {
            prev.shutdown();
            this.bridgeClient = null;
        }
        FabricModConfig cfg = this.config;
        if (cfg == null || cfg.token == null || cfg.token.isBlank()) {
            LOGGER.info("[Blockstager Fabric] Token vacío en " + configPath
                    + ". Copia tu token desde Blockstager Studio → Integraciones y ejecuta /bs reload.");
            return;
        }
        FabricWsBridgeClient client = new FabricWsBridgeClient(
                cfg.wsUrl,
                cfg.token,
                FabricWsBridgeClient.DEFAULT_CLIENT_NAME,
                LOGGER
        );
        client.setMinecraftActionHandler((envId, payload) -> actionExecutor.handleIncomingAction(envId, payload));
        this.bridgeClient = client;
        if (cfg.autoConnect) {
            client.connect();
        }
    }

    private synchronized void shutdownBridge() {
        FabricWsBridgeClient prev = this.bridgeClient;
        this.bridgeClient = null;
        if (prev != null) {
            prev.shutdown();
        }
    }

    private void onClientEndTick(Object clientObj) {
        FabricWsBridgeClient ws = this.bridgeClient;
        boolean authenticatedNow = ws != null && ws.isAuthenticated();
        if (authenticatedNow && !wasWsAuthenticated) {
            // Al conectar o reconectar (o tras /bs reload), reenvía snapshot completo (skin, ítem, armadura, fuego)
            stateTracker.reset();
        }
        wasWsAuthenticated = authenticatedNow;

        ClientStateTracker.PlayerSnapshot snap = reflectBridge.captureSnapshot(clientObj);
        if (!authenticatedNow) {
            return;
        }
        List<ClientStateTracker.EmittedSignal> signals = stateTracker.step(snap);
        if (signals.isEmpty()) {
            return;
        }
        for (ClientStateTracker.EmittedSignal sig : signals) {
            if ("skin.set".equals(sig.type())) {
                ws.sendSkinSet(sig.playerName(), sig.skinUrl(), sig.skinModel());
            } else {
                ws.sendMinecraftEvent(sig.eventName(), sig.playerName(), sig.data());
            }
        }
    }

    private void registerFabricEvents() {
        // 1. Tick de cliente (fabric-lifecycle-events-v1)
        registerEventProxy(
                "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents",
                "END_CLIENT_TICK",
                "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents$EndTick",
                args -> {
                    if (args != null && args.length >= 1) {
                        onClientEndTick(args[0]);
                    }
                }
        );

        // 2. Cierre limpio del cliente (0 sockets/hilos huérfanos)
        registerEventProxy(
                "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents",
                "CLIENT_STOPPING",
                "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents$ClientStopping",
                args -> shutdownBridge()
        );

        // 3. Desconexión de mundo / servidor (reinicia máquina de estados)
        registerEventProxy(
                "net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents",
                "DISCONNECT",
                "net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents$Disconnect",
                args -> stateTracker.reset()
        );

        // 4. Comandos cliente-side /bs y /bsc (fabric-command-api-v2, opcional)
        registerEventProxy(
                "net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback",
                "EVENT",
                "net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback",
                args -> {
                    if (args != null && args.length >= 1 && args[0] instanceof CommandDispatcher<?> dispatcher) {
                        clientCommands.register(dispatcher);
                    }
                }
        );
    }

    @FunctionalInterface
    private interface CallbackInvoker {
        void invoke(Object[] args);
    }

    private void registerEventProxy(
            String holderClassName,
            String eventFieldName,
            String listenerInterfaceName,
            CallbackInvoker invoker
    ) {
        try {
            ClassLoader cl = BlockstagerFabricClient.class.getClassLoader();
            Class<?> holder = Class.forName(holderClassName, true, cl);
            Class<?> listenerInterface = Class.forName(listenerInterfaceName, true, cl);
            Field eventField = holder.getField(eventFieldName);
            Object eventObj = eventField.get(null);
            if (eventObj == null) {
                return;
            }

            Object proxy = Proxy.newProxyInstance(
                    cl,
                    new Class<?>[]{listenerInterface},
                    (p, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "BlockstagerFabricProxy(" + listenerInterfaceName + ")";
                                case "hashCode" -> System.identityHashCode(p);
                                case "equals" -> p == (args != null && args.length > 0 ? args[0] : null);
                                default -> null;
                            };
                        }
                        try {
                            invoker.invoke(args);
                        } catch (Throwable ex) {
                            LOGGER.log(Level.WARNING, "[Blockstager Fabric] Error en callback " + eventFieldName + ": " + ex.getMessage());
                        }
                        return null;
                    }
            );

            // Usar la clase pública Event de Fabric API (no ArrayBackedEvent, que es package-private en Java 21)
            Class<?> eventBaseClass = Class.forName("net.fabricmc.fabric.api.event.Event", true, cl);
            Method registerMethod = eventBaseClass.getMethod("register", Object.class);
            registerMethod.invoke(eventObj, proxy);
            LOGGER.info("[Blockstager Fabric] Evento registrado correctamente: " + holderClassName + "." + eventFieldName);
        } catch (ClassNotFoundException ex) {
            LOGGER.info("[Blockstager Fabric] Módulo opcional de Fabric API no instalado (" + holderClassName + "); omitiendo registro.");
        } catch (Throwable ex) {
            LOGGER.log(Level.WARNING, "[Blockstager Fabric] No se pudo registrar evento " + holderClassName + "." + eventFieldName + ": " + ex.getMessage());
        }
    }
}
