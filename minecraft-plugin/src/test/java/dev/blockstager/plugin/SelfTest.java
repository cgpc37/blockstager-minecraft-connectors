package dev.blockstager.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Verificación local sin servidor Paper para {@link WsBridgeClient}.
 *
 * <p>Modos soportados:</p>
 * <ul>
 *   <li>{@code --self-contained}: levanta un servidor WebSocket RFC 6455 mínimo en proceso y valida
 *       autenticación, rechazo 4401 sin reconexión, {@code trigger.fire}, {@code minecraft.event},
 *       {@code skin.set}, desconexión simulada de 10 s con reconexión automática en &lt;= 15 s y
 *       0 sockets/hilos huérfanos tras {@code shutdown()}.</li>
 *   <li>{@code --url ws://127.0.0.1:28490/ws --token <tok>}: valida contra un servidor real de
 *       Blockstager Studio.</li>
 * </ul>
 */
public final class SelfTest {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static void main(String[] args) {
        try {
            boolean selfContained = false;
            String url = null;
            String token = null;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--self-contained" -> selfContained = true;
                    case "--url" -> {
                        if (i + 1 < args.length) {
                            url = args[++i];
                        }
                    }
                    case "--token" -> {
                        if (i + 1 < args.length) {
                            token = args[++i];
                        }
                    }
                    default -> {
                        System.err.println("Argumento desconocido: " + args[i]);
                        printUsage();
                        System.exit(2);
                    }
                }
            }

            if (selfContained) {
                runSelfContained();
            } else if (url != null && !url.isBlank() && token != null && !token.isBlank()) {
                runAgainstLiveServer(url, token);
            } else {
                printUsage();
                System.exit(2);
            }
        } catch (Throwable t) {
            System.err.println("[FAIL] Excepción no controlada: " + t);
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Uso:");
        System.out.println("  SelfTest --self-contained");
        System.out.println("  SelfTest --url ws://127.0.0.1:28490/ws --token <token>");
    }

    private static Logger createQuietLogger(String name) {
        Logger logger = Logger.getLogger(name);
        logger.setUseParentHandlers(false);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.WARNING);
        handler.setFormatter(new SimpleFormatter());
        logger.addHandler(handler);
        logger.setLevel(Level.WARNING);
        return logger;
    }

    private static void runAgainstLiveServer(String url, String token) throws Exception {
        System.out.println("=== SelfTest (live mode) ===");
        System.out.println("Endpoint: " + url);

        WsBridgeClient client = new WsBridgeClient(url, token, "SelfTest-Live", createQuietLogger("selftest-live"));
        try {
            client.connect();
            assertCondition(waitUntil(client::isAuthenticated, 5_000),
                    "El cliente no se autenticó en <= 5 s (lastError=" + client.getLastError() + ")");
            System.out.println("[PASS] Autenticado (sessionId=" + client.getSessionId()
                    + ", latencyMs=" + client.getLastLatencyMs() + ")");

            Map<String, Object> triggerArgs = new LinkedHashMap<>();
            triggerArgs.put("source", "selftest");
            triggerArgs.put("attempt", 1);
            triggerArgs.put("ok", true);
            assertCondition(client.sendTriggerFire("selftest", triggerArgs), "sendTriggerFire devolvió false");

            JsonObject mcData = new JsonObject();
            mcData.addProperty("skinModel", "classic");
            assertCondition(client.sendMinecraftEvent("player.join", "SelfTestPlayer", mcData),
                    "sendMinecraftEvent devolvió false");
            Thread.sleep(250);
            System.out.println("[PASS] Enviados trigger.fire y minecraft.event");

            client.reconnectNow();
            assertCondition(waitUntil(client::isAuthenticated, 5_000),
                    "reconnectNow no re-autenticó en <= 5 s");
            System.out.println("[PASS] reconnectNow exitoso");
        } finally {
            client.shutdown();
        }

        Thread.sleep(300);
        int leaked = countBlockstagerThreads();
        assertCondition(leaked == 0, "Quedaron hilos huérfanos tras shutdown: " + leaked);
        System.out.println("[PASS] shutdown limpio (0 hilos huérfanos)");
    }

    private static void runSelfContained() throws Exception {
        System.out.println("=== Blockstager Studio Plugin SelfTest (--self-contained) ===");
        final String validToken = "bs_tok_selftest_9f8e7d6c5b4a3210";

        MockWsServer server = new MockWsServer(validToken);
        server.start(0);
        int port = server.getPort();
        String wsUrl = "ws://127.0.0.1:" + port + "/ws";
        System.out.println("[INFO] Mock RFC 6455 WS server escuchando en " + wsUrl);

        try {
            // 1. Verificar rechazo 4401 y que NO reconecta
            System.out.println("[TEST 1] Rechazo 4401 (token inválido) sin reconexión...");
            WsBridgeClient badClient = new WsBridgeClient(wsUrl, "bs_tok_invalid", "SelfTest-Bad",
                    createQuietLogger("selftest-bad"));
            try {
                badClient.connect();
                assertCondition(waitUntil(badClient::isAuthRejected, 4_000),
                        "El cliente no detectó rechazo 4401");
                int connectsAtReject = server.getTotalConnections();
                Thread.sleep(2_200);
                int connectsAfterWait = server.getTotalConnections();
                assertCondition(!badClient.isAuthenticated(), "No debía autenticarse con token inválido");
                assertCondition(connectsAfterWait == connectsAtReject,
                        "El cliente reintentó tras 4401 (" + connectsAtReject + " -> " + connectsAfterWait + ")");
                System.out.println("[PASS] Test 1: 4401 recibido, 0 reconexiones tras 2.2 s");
            } finally {
                badClient.shutdown();
            }

            server.clearReceived();

            // 2. Autenticación, trigger.fire, minecraft.event, skin.set
            System.out.println("[TEST 2] Autenticación, trigger.fire, minecraft.event y skin.set...");
            WsBridgeClient client = new WsBridgeClient(wsUrl, validToken, "Paper-1.21.4-SelfTest",
                    createQuietLogger("selftest-main"));
            try {
                client.connect();
                assertCondition(waitUntil(client::isAuthenticated, 4_000),
                        "No se autenticó con token válido (lastError=" + client.getLastError() + ")");

                Map<String, Object> argsMap = new LinkedHashMap<>();
                argsMap.put("intensity", 2);
                argsMap.put("dramatic", true);
                argsMap.put("triggeredBy", "SelfTest");
                assertCondition(client.sendTriggerFire("backrooms", argsMap), "sendTriggerFire falló");

                JsonObject damageData = new JsonObject();
                damageData.addProperty("damage", 4.5);
                damageData.addProperty("finalDamage", 3.0);
                damageData.addProperty("cause", "fall");
                damageData.addProperty("finalHealth", 17.0);
                assertCondition(client.sendMinecraftEvent("player.damage", "Alex", damageData),
                        "sendMinecraftEvent falló");

                assertCondition(client.sendSkinSet("Alex", "https://textures.minecraft.net/texture/demo", "slim"),
                        "sendSkinSet falló");

                assertCondition(waitUntil(() -> server.countByType("trigger.fire") >= 1
                        && server.countByType("minecraft.event") >= 1
                        && server.countByType("skin.set") >= 1, 4_000),
                        "El servidor mock no recibió todos los sobres esperados");

                JsonObject authMsg = server.firstByType("auth");
                JsonObject triggerMsg = server.firstByType("trigger.fire");
                JsonObject eventMsg = server.firstByType("minecraft.event");
                JsonObject skinMsg = server.firstByType("skin.set");

                assertCondition(WsBridgeClient.PROTOCOL_VERSION.equals(authMsg.get("protocolVersion").getAsString()),
                        "protocolVersion incorrecto en auth");
                assertCondition("backrooms".equals(triggerMsg.getAsJsonObject("payload").get("command").getAsString()),
                        "command incorrecto en trigger.fire");
                assertCondition(2 == triggerMsg.getAsJsonObject("payload").getAsJsonObject("args").get("intensity").getAsInt(),
                        "arg numérico incorrecto en trigger.fire");
                assertCondition("player.damage".equals(eventMsg.getAsJsonObject("payload").get("event").getAsString()),
                        "event incorrecto en minecraft.event");
                assertCondition("slim".equals(skinMsg.getAsJsonObject("payload").get("variant").getAsString()),
                        "variant incorrecto en skin.set");

                System.out.println("[PASS] Test 2: auth (" + WsBridgeClient.PROTOCOL_VERSION
                        + "), trigger.fire, minecraft.event y skin.set verificados (latencyMs="
                        + client.getLastLatencyMs() + ")");

                // 2B. Puente bidireccional minecraft.action <-> minecraft.action.result (ejecución, allowlist, límites, cooldown, disabled)
                System.out.println("[TEST 2B] Puente bidireccional minecraft.action <-> minecraft.action.result...");
                verifyBidirectionalMinecraftActions(server, client);
                System.out.println("[PASS] Test 2B: ejecución, allowlist/blocklist, límites, cooldown y disabled verificados");

                // 3. Desconexión simulada de 10 s + reconexión automática por backoff en <= 15 s
                System.out.println("[TEST 3] Simulando caída del servidor durante 10 s y verificando reconexión automática...");
                long outageStartMs = System.currentTimeMillis();
                server.stop();

                assertCondition(waitUntil(() -> !client.isConnected(), 3_000),
                        "El cliente no detectó la desconexión del servidor");

                long remainingOutageMs = 10_000L - (System.currentTimeMillis() - outageStartMs);
                if (remainingOutageMs > 0) {
                    Thread.sleep(remainingOutageMs);
                }

                long serverRestartMs = System.currentTimeMillis();
                server.clearReceived();
                server.start(port);
                System.out.println("[INFO] Servidor mock reiniciado tras "
                        + (serverRestartMs - outageStartMs) + " ms en puerto " + port);

                assertCondition(waitUntil(client::isAuthenticated, 15_000),
                        "El cliente no se reconectó automáticamente tras reiniciar el servidor");

                long reconnectedMs = System.currentTimeMillis();
                long totalFromOutageMs = reconnectedMs - outageStartMs;
                long sinceRestartMs = reconnectedMs - serverRestartMs;

                assertCondition(sinceRestartMs <= 15_000L,
                        "Reconexión tardó más de 15 s tras volver el servidor: " + sinceRestartMs + " ms");
                assertCondition(totalFromOutageMs <= 15_000L,
                        "Reconexión tardó más de 15 s totales desde el corte: " + totalFromOutageMs + " ms");

                assertCondition(client.sendTriggerFire("after_reconnect", Map.of("recovered", true)),
                        "No pudo enviar trigger tras reconectar");
                assertCondition(waitUntil(() -> server.countByType("trigger.fire") >= 1, 3_000),
                        "El servidor no recibió trigger tras reconectar");

                System.out.println("[PASS] Test 3: Reconectado automáticamente en "
                        + sinceRestartMs + " ms tras reinicio (" + totalFromOutageMs
                        + " ms totales desde el corte, reconnectCount=" + client.getReconnectCount() + ")");
            } finally {
                // 4. Cierre limpio: 0 sockets huérfanos y 0 hilos filtrados
                System.out.println("[TEST 4] Verificando shutdown limpio (0 sockets y 0 hilos huérfanos)...");
                client.shutdown();
            }

            assertCondition(waitUntil(() -> server.getActiveSockets() == 0, 3_000),
                    "Quedaron sockets abiertos en el servidor mock: " + server.getActiveSockets());
            Thread.sleep(300);
            int leakedThreads = countBlockstagerThreads();
            assertCondition(leakedThreads == 0,
                    "Quedaron hilos blockstager-ws-* activos tras shutdown: " + leakedThreads);
            System.out.println("[PASS] Test 4: 0 sockets huérfanos, 0 hilos blockstager-ws-* filtrados");
            System.out.println("=== ALL SELFTEST CHECKS PASSED ===");
        } finally {
            server.stop();
        }
    }

    private static void verifyBidirectionalMinecraftActions(MockWsServer server, WsBridgeClient client) throws Exception {
        java.util.concurrent.atomic.AtomicLong fakeClock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        RemoteActionPolicy policy = new RemoteActionPolicy(
                RemoteActionPolicy.Settings.defaults().withActionCooldownMs(1_500L),
                fakeClock::get
        );
        CopyOnWriteArrayList<RemoteActionPolicy.ValidatedAction> executed = new CopyOnWriteArrayList<>();

        client.setMinecraftActionHandler((envId, payload) -> {
            RemoteActionPolicy.ValidationResult res = policy.validate(envId, payload);
            if (res.isOk()) {
                executed.add(res.action());
                client.sendMinecraftActionResult(
                        res.actionId(),
                        res.requestId(),
                        res.wireAction(),
                        RemoteActionPolicy.Outcome.OK.wireValue(),
                        res.detail(),
                        "StreamerMC"
                );
            } else {
                client.sendMinecraftActionResult(
                        res.actionId(),
                        res.requestId(),
                        res.wireAction(),
                        res.outcome().wireValue(),
                        res.detail(),
                        null
                );
            }
        });

        // 1) Las 4 acciones válidas (incluyendo alias de Studio y del handoff)
        server.clearReceived();
        JsonObject spawnPayload = new JsonObject();
        spawnPayload.addProperty("action", "spawn_mob");
        spawnPayload.addProperty("entity", "allay");
        spawnPayload.addProperty("count", 2);
        spawnPayload.addProperty("radiusBlocks", 4);
        spawnPayload.addProperty("triggeredBy", "Viewer1");
        String spawnId = server.sendToAllClients("minecraft.action", spawnPayload);

        assertCondition(waitUntil(() -> server.findActionResultById(spawnId) != null, 3_000),
                "No llegó minecraft.action.result para spawn_mob");
        JsonObject spawnRes = server.findActionResultById(spawnId).getAsJsonObject("payload");
        assertCondition("ok".equals(spawnRes.get("outcome").getAsString()), "spawn_mob debía devolver ok: " + spawnRes);
        assertCondition("spawn_mob".equals(spawnRes.get("action").getAsString()), "action incorrecto en spawn_mob");
        assertCondition("StreamerMC".equals(spawnRes.get("player").getAsString()), "player faltante en spawn_mob");

        // 2) Cooldown inmediato -> rate_limited (mismo reloj)
        JsonObject rapidGive = new JsonObject();
        rapidGive.addProperty("action", "give_item");
        rapidGive.addProperty("item", "golden_apple");
        rapidGive.addProperty("amount", 1);
        String rapidId = server.sendToAllClients("minecraft.action", rapidGive);

        assertCondition(waitUntil(() -> server.findActionResultById(rapidId) != null, 3_000),
                "No llegó minecraft.action.result para prueba de cooldown");
        JsonObject rapidRes = server.findActionResultById(rapidId).getAsJsonObject("payload");
        assertCondition("rate_limited".equals(rapidRes.get("outcome").getAsString()),
                "Se esperaba rate_limited durante el enfriamiento, llegó: " + rapidRes);
        assertCondition(!rapidRes.get("detail").getAsString().toLowerCase(Locale.ROOT).contains("ms"),
                "El detalle de enfriamiento no debe exponer 'ms' crudos: " + rapidRes);

        // 3) Avanzar reloj más allá del cooldown -> give_item, apply_effect (alias potion_effect), world_fx (alias world_effect)
        fakeClock.addAndGet(2_000L);
        String giveId = server.sendToAllClients("minecraft.action", rapidGive);
        assertCondition(waitUntil(() -> server.findActionResultById(giveId) != null, 3_000),
                "No llegó resultado para give_item tras enfriamiento");
        assertCondition("ok".equals(server.findActionResultById(giveId).getAsJsonObject("payload").get("outcome").getAsString()),
                "give_item tras enfriamiento debía ser ok");

        fakeClock.addAndGet(2_000L);
        JsonObject effectPayload = new JsonObject();
        effectPayload.addProperty("action", "apply_effect");
        effectPayload.addProperty("effect", "levitation");
        effectPayload.addProperty("durationSec", 6);
        effectPayload.addProperty("amplifier", 1);
        String effectId = server.sendToAllClients("minecraft.action", effectPayload);
        assertCondition(waitUntil(() -> server.findActionResultById(effectId) != null, 3_000),
                "No llegó resultado para apply_effect");
        assertCondition("ok".equals(server.findActionResultById(effectId).getAsJsonObject("payload").get("outcome").getAsString()),
                "apply_effect debía ser ok");

        fakeClock.addAndGet(2_000L);
        JsonObject worldPayload = new JsonObject();
        worldPayload.addProperty("action", "world_fx");
        worldPayload.addProperty("fx", "lightning_effect");
        worldPayload.addProperty("text", "¡Alerta!");
        String worldId = server.sendToAllClients("minecraft.action", worldPayload);
        assertCondition(waitUntil(() -> server.findActionResultById(worldId) != null, 3_000),
                "No llegó resultado para world_fx");
        assertCondition("ok".equals(server.findActionResultById(worldId).getAsJsonObject("payload").get("outcome").getAsString()),
                "world_fx debía ser ok");
        assertCondition(executed.size() == 4, "Debían haberse ejecutado 4 acciones válidas, fueron: " + executed.size());

        // 4) Seguridad: entidad bloqueada, ítem bloqueado y topes excedidos -> rejected
        fakeClock.addAndGet(2_000L);
        JsonObject blockedMob = new JsonObject();
        blockedMob.addProperty("action", "spawn_mob");
        blockedMob.addProperty("entity", "wither");
        blockedMob.addProperty("count", 1);
        String blockedMobId = server.sendToAllClients("minecraft.action", blockedMob);
        assertCondition(waitUntil(() -> server.findActionResultById(blockedMobId) != null, 3_000),
                "No llegó rechazo para wither");
        assertCondition("rejected".equals(server.findActionResultById(blockedMobId).getAsJsonObject("payload").get("outcome").getAsString()),
                "wither debía ser rejected");

        JsonObject blockedItem = new JsonObject();
        blockedItem.addProperty("action", "give_item");
        blockedItem.addProperty("item", "minecraft:bedrock");
        blockedItem.addProperty("amount", 1);
        String blockedItemId = server.sendToAllClients("minecraft.action", blockedItem);
        assertCondition(waitUntil(() -> server.findActionResultById(blockedItemId) != null, 3_000),
                "No llegó rechazo para bedrock");
        assertCondition("rejected".equals(server.findActionResultById(blockedItemId).getAsJsonObject("payload").get("outcome").getAsString()),
                "bedrock debía ser rejected");

        JsonObject overLimitMob = new JsonObject();
        overLimitMob.addProperty("action", "spawn_mob");
        overLimitMob.addProperty("entity", "pig");
        overLimitMob.addProperty("count", 25);
        String overMobId = server.sendToAllClients("minecraft.action", overLimitMob);
        assertCondition(waitUntil(() -> server.findActionResultById(overMobId) != null, 3_000),
                "No llegó rechazo para count fuera de límite");
        assertCondition("rejected".equals(server.findActionResultById(overMobId).getAsJsonObject("payload").get("outcome").getAsString()),
                "count=25 debía ser rejected");

        JsonObject overEffect = new JsonObject();
        overEffect.addProperty("action", "potion_effect");
        overEffect.addProperty("effect", "speed");
        overEffect.addProperty("durationSec", 600);
        overEffect.addProperty("amplifier", 9);
        String overEffectId = server.sendToAllClients("minecraft.action", overEffect);
        assertCondition(waitUntil(() -> server.findActionResultById(overEffectId) != null, 3_000),
                "No llegó rechazo para duración/amplificador fuera de límite");
        assertCondition("rejected".equals(server.findActionResultById(overEffectId).getAsJsonObject("payload").get("outcome").getAsString()),
                "potion_effect fuera de límite debía ser rejected");

        // 5) Interruptor maestro allowRemoteActions=false -> disabled
        policy.setAllowRemoteActions(false);
        fakeClock.addAndGet(2_000L);
        String disabledId = server.sendToAllClients("minecraft.action", worldPayload);
        assertCondition(waitUntil(() -> server.findActionResultById(disabledId) != null, 3_000),
                "No llegó resultado disabled cuando allowRemoteActions=false");
        assertCondition("disabled".equals(server.findActionResultById(disabledId).getAsJsonObject("payload").get("outcome").getAsString()),
                "Se esperaba outcome=disabled cuando allowRemoteActions=false");

        assertCondition(executed.size() == 4, "Ninguna acción rechazada/limitada/desactivada debía ejecutarse");
    }

    private static int countBlockstagerThreads() {
        int count = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("blockstager-ws-")) {
                count++;
            }
        }
        return count;
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(40);
        }
        return condition.getAsBoolean();
    }

    private static void assertCondition(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * Servidor RFC 6455 mínimo en Java puro para probar el handshake, frames de texto,
     * ping/pong, cierre 4401 y reinicios en el mismo puerto.
     */
    private static final class MockWsServer {

        private final String expectedToken;
        private final CopyOnWriteArrayList<JsonObject> received = new CopyOnWriteArrayList<>();
        private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<Socket, OutputStream> activeOutputs = new ConcurrentHashMap<>();
        private final AtomicInteger totalConnections = new AtomicInteger(0);
        private final AtomicInteger sessionSeq = new AtomicInteger(0);
        private final AtomicBoolean running = new AtomicBoolean(false);

        private volatile ServerSocket serverSocket;
        private volatile ExecutorService pool;
        private volatile int boundPort;

        MockWsServer(String expectedToken) {
            this.expectedToken = expectedToken;
        }

        void start(int port) throws IOException {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            this.serverSocket = ss;
            this.boundPort = ss.getLocalPort();
            this.running.set(true);
            this.pool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "mock-ws-server-worker");
                t.setDaemon(true);
                return t;
            });

            Thread acceptThread = new Thread(this::acceptLoop, "mock-ws-server-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int getPort() {
            return boundPort;
        }

        int getTotalConnections() {
            return totalConnections.get();
        }

        int getActiveSockets() {
            return activeSockets.size();
        }

        void clearReceived() {
            received.clear();
        }

        long countByType(String type) {
            return received.stream()
                    .filter(obj -> obj.has("type") && type.equals(obj.get("type").getAsString()))
                    .count();
        }

        JsonObject firstByType(String type) {
            for (JsonObject obj : received) {
                if (obj.has("type") && type.equals(obj.get("type").getAsString())) {
                    return obj;
                }
            }
            throw new AssertionError("No se encontró mensaje de tipo: " + type);
        }

        JsonObject findActionResultById(String expectedId) {
            for (JsonObject obj : received) {
                if (!obj.has("type") || !"minecraft.action.result".equals(obj.get("type").getAsString())) {
                    continue;
                }
                if (!obj.has("payload") || !obj.get("payload").isJsonObject()) {
                    continue;
                }
                JsonObject p = obj.getAsJsonObject("payload");
                String aid = p.has("actionId") && p.get("actionId").isJsonPrimitive() ? p.get("actionId").getAsString() : "";
                String rid = p.has("requestId") && p.get("requestId").isJsonPrimitive() ? p.get("requestId").getAsString() : "";
                if (expectedId.equals(aid) || expectedId.equals(rid)) {
                    return obj;
                }
            }
            return null;
        }

        String sendToAllClients(String type, JsonObject payload) throws IOException {
            String envId = "srv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            JsonObject env = new JsonObject();
            env.addProperty("protocolVersion", WsBridgeClient.PROTOCOL_VERSION);
            env.addProperty("id", envId);
            env.addProperty("type", type);
            env.addProperty("source", "blockstager.server");
            env.addProperty("timestamp", Instant.now().toEpochMilli());
            env.add("payload", payload != null ? payload.deepCopy() : new JsonObject());
            byte[] bytes = env.toString().getBytes(StandardCharsets.UTF_8);
            for (OutputStream out : activeOutputs.values()) {
                sendFrame(out, 0x1, bytes);
            }
            return envId;
        }

        void stop() {
            running.set(false);
            ServerSocket ss = this.serverSocket;
            this.serverSocket = null;
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException ignored) {
                }
            }
            for (Socket s : activeSockets) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            activeSockets.clear();
            activeOutputs.clear();
            ExecutorService exec = this.pool;
            this.pool = null;
            if (exec != null) {
                exec.shutdownNow();
                try {
                    exec.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void acceptLoop() {
            while (running.get()) {
                ServerSocket ss = this.serverSocket;
                if (ss == null || ss.isClosed()) {
                    break;
                }
                try {
                    Socket socket = ss.accept();
                    totalConnections.incrementAndGet();
                    activeSockets.add(socket);
                    ExecutorService exec = this.pool;
                    if (exec != null && !exec.isShutdown()) {
                        exec.submit(() -> handleClient(socket));
                    } else {
                        socket.close();
                        activeSockets.remove(socket);
                    }
                } catch (SocketException closed) {
                    break;
                } catch (IOException ignored) {
                    if (!running.get()) {
                        break;
                    }
                }
            }
        }

        private void handleClient(Socket socket) {
            try (Socket s = socket) {
                s.setTcpNoDelay(true);
                InputStream in = new BufferedInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();

                Map<String, String> headers = readHttpHeaders(in);
                String wsKey = headers.get("sec-websocket-key");
                if (wsKey == null || wsKey.isBlank()) {
                    return;
                }

                String accept = computeAcceptKey(wsKey.trim());
                String response = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                activeOutputs.put(s, out);

                while (running.get() && !s.isClosed()) {
                    WsFrame frame = readFrame(in);
                    if (frame == null) {
                        break;
                    }
                    switch (frame.opcode) {
                        case 0x1 -> {
                            String text = new String(frame.payload, StandardCharsets.UTF_8);
                            boolean keepOpen = onTextMessage(text, out);
                            if (!keepOpen) {
                                return;
                            }
                        }
                        case 0x8 -> {
                            // Close handshake reply
                            sendCloseFrame(out, 1000, "bye");
                            return;
                        }
                        case 0x9 -> {
                            // Ping -> Pong
                            sendFrame(out, 0xA, frame.payload);
                        }
                        default -> {
                            // Ignorar otros opcodes en el mock
                        }
                    }
                }
            } catch (IOException ignored) {
                // Desconexión esperada durante shutdown o simulación de corte
            } finally {
                activeOutputs.remove(socket);
                activeSockets.remove(socket);
            }
        }

        private boolean onTextMessage(String text, OutputStream out) throws IOException {
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) {
                return true;
            }
            JsonObject env = el.getAsJsonObject();
            received.add(env);

            String type = env.has("type") ? env.get("type").getAsString() : "";
            if ("auth".equals(type)) {
                String proto = env.has("protocolVersion") ? env.get("protocolVersion").getAsString() : "";
                JsonObject payload = env.getAsJsonObject("payload");
                String tok = payload != null && payload.has("token") ? payload.get("token").getAsString() : "";

                if (!WsBridgeClient.PROTOCOL_VERSION.equals(proto)) {
                    JsonObject errPayload = new JsonObject();
                    errPayload.addProperty("code", "bad_protocol");
                    errPayload.addProperty("message", "Expected " + WsBridgeClient.PROTOCOL_VERSION);
                    sendTextEnvelope(out, "auth.error", errPayload);
                    sendCloseFrame(out, WsBridgeClient.CLOSE_BAD_PROTOCOL, "bad_protocol");
                    return false;
                }
                if (!expectedToken.equals(tok)) {
                    JsonObject errPayload = new JsonObject();
                    errPayload.addProperty("code", "unauthorized");
                    errPayload.addProperty("message", "Invalid bearer token");
                    sendTextEnvelope(out, "auth.error", errPayload);
                    sendCloseFrame(out, WsBridgeClient.CLOSE_UNAUTHORIZED, "unauthorized");
                    return false;
                }

                JsonObject okPayload = new JsonObject();
                okPayload.addProperty("sessionId", "mock_sess_" + sessionSeq.incrementAndGet());
                sendTextEnvelope(out, "auth.ok", okPayload);
            }
            return true;
        }

        private void sendTextEnvelope(OutputStream out, String type, JsonObject payload) throws IOException {
            JsonObject env = new JsonObject();
            env.addProperty("protocolVersion", WsBridgeClient.PROTOCOL_VERSION);
            env.addProperty("id", "srv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            env.addProperty("type", type);
            env.addProperty("source", "blockstager.server");
            env.addProperty("timestamp", Instant.now().toEpochMilli());
            env.add("payload", payload);
            sendFrame(out, 0x1, env.toString().getBytes(StandardCharsets.UTF_8));
        }

        private static void sendCloseFrame(OutputStream out, int statusCode, String reason) throws IOException {
            byte[] reasonBytes = reason != null ? reason.getBytes(StandardCharsets.UTF_8) : new byte[0];
            byte[] payload = new byte[2 + reasonBytes.length];
            payload[0] = (byte) ((statusCode >> 8) & 0xFF);
            payload[1] = (byte) (statusCode & 0xFF);
            System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
            sendFrame(out, 0x8, payload);
        }

        private static void sendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(payload.length + 10);
            buf.write(0x80 | (opcode & 0x0F));
            int len = payload.length;
            if (len < 126) {
                buf.write(len);
            } else if (len <= 65535) {
                buf.write(126);
                buf.write((len >> 8) & 0xFF);
                buf.write(len & 0xFF);
            } else {
                buf.write(127);
                for (int i = 7; i >= 0; i--) {
                    buf.write((int) ((len >>> (8 * i)) & 0xFF));
                }
            }
            buf.write(payload);
            synchronized (out) {
                out.write(buf.toByteArray());
                out.flush();
            }
        }

        private static WsFrame readFrame(InputStream in) throws IOException {
            int b0 = in.read();
            if (b0 < 0) {
                return null;
            }
            int b1 = in.read();
            if (b1 < 0) {
                return null;
            }
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((readByte(in) & 0xFF) << 8) | (readByte(in) & 0xFF);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | (readByte(in) & 0xFF);
                }
            }
            byte[] mask = new byte[4];
            if (masked) {
                readFully(in, mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            return new WsFrame(opcode, payload);
        }

        private static int readByte(InputStream in) throws IOException {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("Unexpected EOF");
            }
            return b;
        }

        private static void readFully(InputStream in, byte[] dst) throws IOException {
            int offset = 0;
            while (offset < dst.length) {
                int read = in.read(dst, offset, dst.length - offset);
                if (read < 0) {
                    throw new EOFException("Unexpected EOF");
                }
                offset += read;
            }
        }

        private static Map<String, String> readHttpHeaders(InputStream in) throws IOException {
            Map<String, String> headers = new LinkedHashMap<>();
            String requestLine = readAsciiLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return headers;
            }
            while (true) {
                String line = readAsciiLine(in);
                if (line == null || line.isEmpty()) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String val = line.substring(colon + 1).trim();
                    headers.put(name, val);
                }
            }
            return headers;
        }

        private static String readAsciiLine(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
            while (true) {
                int b = in.read();
                if (b < 0) {
                    return buf.size() == 0 ? null : buf.toString(StandardCharsets.US_ASCII);
                }
                if (b == '\n') {
                    byte[] arr = buf.toByteArray();
                    int len = arr.length;
                    if (len > 0 && arr[len - 1] == '\r') {
                        len--;
                    }
                    return new String(arr, 0, len, StandardCharsets.US_ASCII);
                }
                buf.write(b);
            }
        }

        private static String computeAcceptKey(String key) {
            try {
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                byte[] digest = sha1.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
                return Base64.getEncoder().encodeToString(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        private record WsFrame(int opcode, byte[] payload) {
        }
    }
}
