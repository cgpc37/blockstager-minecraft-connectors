package dev.blockstager.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Suite de pruebas autocontenida para el mod cliente Fabric 1.21+ de Blockstager Studio.
 *
 * <p>Ejecución:</p>
 * <pre>
 * java -cp "tools/fabric-build/build/selftest;tools/fabric-build/lib/gson-2.11.0.jar" \
 *      dev.blockstager.fabric.FabricSelfTest --self-contained
 * </pre>
 */
public final class FabricSelfTest {

    private static final Logger LOGGER = Logger.getLogger("BlockstagerFabricSelfTest");
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static void main(String[] args) throws Exception {
        boolean selfContained = false;
        for (String arg : args) {
            if ("--self-contained".equals(arg)) {
                selfContained = true;
            }
        }
        if (!selfContained) {
            System.out.println("Uso: FabricSelfTest --self-contained");
            System.exit(2);
            return;
        }

        int exitCode = 0;
        try {
            testConfigRoundtrip();
            testStateTrackerLifecycle();
            testClientActionPolicySecurity();
            testWebSocketRoundtripAndReconnect();
            System.out.println("[FabricSelfTest] ALL CHECKS PASSED");
        } catch (Throwable err) {
            err.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    private static void testConfigRoundtrip() throws IOException {
        Path tmpDir = Files.createTempDirectory("bs-fabric-cfg-test");
        Path cfgPath = tmpDir.resolve(FabricModConfig.FILE_NAME);
        try {
            FabricModConfig created = FabricModConfig.loadOrCreate(cfgPath);
            assertTrue(Files.isRegularFile(cfgPath), "Debe crear blockstager-fabric.json por defecto");
            assertEquals(FabricModConfig.DEFAULT_WS_URL, created.wsUrl, "wsUrl por defecto");
            assertTrue(created.allowRemoteActions, "allowRemoteActions por defecto true");
            assertTrue(created.allowClientCosmeticEffects, "allowClientCosmeticEffects por defecto true");
            assertTrue(!created.allowMultiplayerOpActions, "allowMultiplayerOpActions por defecto false (seguro en servidores públicos)");

            created.token = "bs_tok_fabric_test_1234";
            created.maxMobCount = 999; // debe acotarse a 50
            created.actionCooldownMs = -50; // debe acotarse a 0
            FabricModConfig.saveAtomic(cfgPath, created);

            FabricModConfig reloaded = FabricModConfig.loadOrCreate(cfgPath);
            assertEquals("bs_tok_fabric_test_1234", reloaded.token, "token persistido");
            assertEquals(50, reloaded.maxMobCount, "maxMobCount acotado a 50");
            assertEquals(0L, reloaded.actionCooldownMs, "actionCooldownMs acotado a 0");
            System.out.println("[FabricSelfTest] PASS: FabricModConfig persistencia atómica y límites");
        } finally {
            Files.deleteIfExists(cfgPath);
            Files.deleteIfExists(tmpDir);
        }
    }

    private static void testStateTrackerLifecycle() {
        ClientStateTracker tracker = new ClientStateTracker(true, true);

        // 1. Entrada al servidor público + skin sync + sincronización inicial (ítem, armadura, fuego, gameDir)
        ClientStateTracker.PlayerSnapshot s1 = new ClientStateTracker.PlayerSnapshot(
                true, false, 0, "ChrisVT",
                "https://textures.minecraft.net/texture/demo", "SLIM",
                "minecraft:overworld", 20f, 20f, false, 0,
                0, "minecraft:diamond_sword", "minecraft:totem_of_undying",
                true, false,
                false, "minecraft:air", 0, false, false, true,
                false, false, false, false, "minecraft:air", "minecraft:air", "minecraft:air", "minecraft:air", false,
                0.0, 0.0, false, 0,
                0f, 0f, 0f, 0f, "C:\\Games\\MinecraftInstance"
        );
        List<ClientStateTracker.EmittedSignal> ev1 = tracker.step(s1);
        assertEquals(5, ev1.size(), "Join debe emitir player.join, skin.set, held_item_change, armor_change y fire_state");
        assertEquals("player.join", ev1.get(0).eventName(), "primer evento player.join");
        assertEquals("multiplayer", ev1.get(0).data().get("mode").getAsString(), "modo multiplayer");
        assertEquals("C:\\Games\\MinecraftInstance", ev1.get(0).data().get("gameDir").getAsString(), "gameDir en player.join");
        assertEquals("skin.set", ev1.get(1).type(), "segundo evento skin.set");
        assertEquals("slim", ev1.get(1).skinModel(), "skinModel slim");
        assertHasEvent(ev1, "player.held_item_change");
        assertTrue(ev1.get(2).data().get("mainHandGlint").getAsBoolean(), "mainHandGlint en held_item_change");
        assertHasEvent(ev1, "player.armor_change");
        assertHasEvent(ev1, "player.fire_state");

        // 2. Cambio de ítem + daño + fuego + armadura encantada + caminar/correr + golpear/picar + ángulo de vista
        ClientStateTracker.PlayerSnapshot s2 = new ClientStateTracker.PlayerSnapshot(
                true, false, 0, "ChrisVT",
                "https://textures.minecraft.net/texture/demo", "SLIM",
                "minecraft:overworld", 14f, 20f, false, 10,
                1, "minecraft:golden_apple", "minecraft:totem_of_undying",
                false, false,
                true, "minecraft:golden_apple", 1, true, true, false,
                true, false, false, false, "minecraft:diamond_helmet", "minecraft:diamond_chestplate", "minecraft:iron_leggings", "minecraft:netherite_boots", true,
                0.32, 0.0, true, 1,
                -22f, 24f, 0f, 24f, "C:\\Games\\MinecraftInstance"
        );
        List<ClientStateTracker.EmittedSignal> ev2 = tracker.step(s2);
        assertHasEvent(ev2, "player.damage");
        assertHasEvent(ev2, "player.fire_state");
        assertHasEvent(ev2, "player.armor_change");
        assertHasEvent(ev2, "player.held_item_change");
        assertHasEvent(ev2, "player.sneak");
        assertHasEvent(ev2, "player.sprint");
        assertHasEvent(ev2, "player.jump");
        assertHasEvent(ev2, "player.locomotion");
        ClientStateTracker.EmittedSignal loco2 = ev2.stream()
                .filter(e -> "player.locomotion".equals(e.eventName())).findFirst().orElseThrow();
        assertTrue(loco2.data().get("lookPitch").getAsDouble() < -15.0, "lookPitch hacia arriba detectado");
        assertTrue(loco2.data().get("lookYaw").getAsDouble() > 15.0, "lookYaw relativo detectado");

        // 2b. Al quedar mirando recto hacia atrás (180° cuerpo y cabeza alineados), lookYaw regresa cerca de 0° sin voltear el avatar
        ClientStateTracker.PlayerSnapshot s2b = new ClientStateTracker.PlayerSnapshot(
                true, false, 0, "ChrisVT",
                "https://textures.minecraft.net/texture/demo", "SLIM",
                "minecraft:overworld", 14f, 20f, false, 9,
                1, "minecraft:golden_apple", "minecraft:totem_of_undying",
                false, false,
                true, "minecraft:golden_apple", 1, true, true, true,
                true, false, false, false, "minecraft:diamond_helmet", "minecraft:diamond_chestplate", "minecraft:iron_leggings", "minecraft:netherite_boots", true,
                0.40, 0.0, false, 0,
                0f, 180f, 180f, 180f, "C:\\Games\\MinecraftInstance"
        );
        List<ClientStateTracker.EmittedSignal> ev2b = tracker.step(s2b);
        assertHasEvent(ev2b, "player.locomotion");
        ClientStateTracker.EmittedSignal loco2b = ev2b.stream()
                .filter(e -> "player.locomotion".equals(e.eventName())).findFirst().orElseThrow();
        assertTrue(Math.abs(loco2b.data().get("lookYaw").getAsDouble()) < 5.0,
                "Mirar recto a 180° no debe voltear ni romper el ángulo del personaje");

        // 2c. Nado en agua y levitación en vuelo creativo
        ClientStateTracker.PlayerSnapshot s2Swim = new ClientStateTracker.PlayerSnapshot(
                true, false, 0, "ChrisVT",
                "https://textures.minecraft.net/texture/demo", "SLIM",
                "minecraft:overworld", 14f, 20f, false, 0,
                1, "minecraft:golden_apple", "minecraft:totem_of_undying",
                false, false,
                true, "minecraft:golden_apple", 1, false, false, false,
                false, true, true, false, "minecraft:diamond_helmet", "minecraft:diamond_chestplate", "minecraft:iron_leggings", "minecraft:netherite_boots", true,
                0.46, 0.0, false, 0,
                0f, 180f, 180f, 180f, "C:\\Games\\MinecraftInstance"
        );
        List<ClientStateTracker.EmittedSignal> ev2Swim = tracker.step(s2Swim);
        ClientStateTracker.EmittedSignal locoSwim = ev2Swim.stream()
                .filter(e -> "player.locomotion".equals(e.eventName())).findFirst().orElseThrow();
        assertTrue(locoSwim.data().get("swimming").getAsBoolean(), "swimming=true al nadar en agua");
        assertTrue(!locoSwim.data().get("flying").getAsBoolean(), "flying=false al nadar");

        ClientStateTracker.PlayerSnapshot s2Fly = new ClientStateTracker.PlayerSnapshot(
                true, true, 4, "ChrisVT",
                "https://textures.minecraft.net/texture/demo", "SLIM",
                "minecraft:overworld", 14f, 20f, false, 0,
                1, "minecraft:golden_apple", "minecraft:totem_of_undying",
                false, false,
                true, "minecraft:golden_apple", 1, false, false, false,
                true, false, false, true, "minecraft:diamond_helmet", "minecraft:diamond_chestplate", "minecraft:iron_leggings", "minecraft:netherite_boots", true,
                0.52, 0.0, false, 0,
                0f, 180f, 180f, 180f, "C:\\Games\\MinecraftInstance"
        );
        List<ClientStateTracker.EmittedSignal> ev2Fly = tracker.step(s2Fly);
        ClientStateTracker.EmittedSignal locoFly = ev2Fly.stream()
                .filter(e -> "player.locomotion".equals(e.eventName())).findFirst().orElseThrow();
        assertTrue(locoFly.data().get("flying").getAsBoolean(), "flying=true en vuelo creativo");
        assertTrue(!locoFly.data().get("swimming").getAsBoolean(), "swimming=false en vuelo creativo");

        // 3. Consumo de ítem + cambio a Nether + totem pop + apagar fuego
        ClientStateTracker.PlayerSnapshot s3 = new ClientStateTracker.PlayerSnapshot(
                true, false, 0, "ChrisVT",
                "https://textures.minecraft.net/texture/demo", "SLIM",
                "minecraft:the_nether", 4f, 20f, false, 9,
                1, "minecraft:golden_apple", "minecraft:air",
                false, false,
                false, "minecraft:air", 0, true, true, true,
                false, false, false, false, "minecraft:diamond_helmet", "minecraft:diamond_chestplate", "minecraft:iron_leggings", "minecraft:netherite_boots", true,
                0.46, 0.0, false, 0,
                0f, 180f, 180f, 180f, "C:\\Games\\MinecraftInstance"
        );
        List<ClientStateTracker.EmittedSignal> ev3 = tracker.step(s3);
        assertHasEvent(ev3, "player.item_consume");
        assertHasEvent(ev3, "player.dimension_change");
        assertHasEvent(ev3, "player.totem_pop");
        assertHasEvent(ev3, "player.fire_state");

        // 4. Muerte y posterior reaparición (respawn)
        ClientStateTracker.PlayerSnapshot s4 = new ClientStateTracker.PlayerSnapshot(
                true, false, 0, "ChrisVT",
                "https://textures.minecraft.net/texture/demo", "SLIM",
                "minecraft:the_nether", 0f, 20f, true, 10,
                1, "minecraft:air", "minecraft:air",
                false, false,
                false, "minecraft:air", 0, false, false, true,
                false, false, false, false, "minecraft:air", "minecraft:air", "minecraft:air", "minecraft:air", false,
                0.46, 0.0, false, 0,
                0f, 180f, 180f, 180f, "C:\\Games\\MinecraftInstance"
        );
        List<ClientStateTracker.EmittedSignal> ev4 = tracker.step(s4);
        assertHasEvent(ev4, "player.death");

        ClientStateTracker.PlayerSnapshot s5 = new ClientStateTracker.PlayerSnapshot(
                true, false, 0, "ChrisVT",
                "https://textures.minecraft.net/texture/demo", "SLIM",
                "minecraft:overworld", 20f, 20f, false, 0,
                0, "minecraft:air", "minecraft:air",
                false, false,
                false, "minecraft:air", 0, false, false, true,
                false, false, false, false, "minecraft:air", "minecraft:air", "minecraft:air", "minecraft:air", false,
                0.0, 0.0, false, 0,
                0f, 0f, 0f, 0f, "C:\\Games\\MinecraftInstance"
        );
        List<ClientStateTracker.EmittedSignal> ev5 = tracker.step(s5);
        assertHasEvent(ev5, "player.respawn");

        // 5. Desconexión reinicia la máquina
        assertTrue(tracker.step(ClientStateTracker.PlayerSnapshot.disconnected()).isEmpty(), "Sin eventos desconectado");
        List<ClientStateTracker.EmittedSignal> rejoin = tracker.step(s1);
        assertEquals("player.join", rejoin.get(0).eventName(), "Re-entrada emite player.join de nuevo");

        System.out.println("[FabricSelfTest] PASS: ClientStateTracker (join, skin, damage, death/respawn, fire, armor, locomotion, held, totem)");
    }

    private static void testClientActionPolicySecurity() {
        AtomicLong fakeClock = new AtomicLong(10_000L);
        ClientActionPolicy policy = new ClientActionPolicy(ClientActionPolicy.Settings.defaults(), fakeClock::get);

        // A. En servidor público: rechaza spawn_mob / give_item / potion_effect, permite title_overlay cosmético local
        JsonObject spawnMob = new JsonObject();
        spawnMob.addProperty("action", "spawn_mob");
        spawnMob.addProperty("entity", "pig");
        spawnMob.addProperty("count", 2);
        ClientActionPolicy.ValidationResult pubSpawn = policy.validate("e1", spawnMob, ClientActionPolicy.ExecutionMode.MULTIPLAYER_PUBLIC);
        assertEquals(ClientActionPolicy.Outcome.REJECTED, pubSpawn.outcome(), "spawn_mob rechazado en servidor público");

        JsonObject giveApple = new JsonObject();
        giveApple.addProperty("action", "give_item");
        giveApple.addProperty("item", "golden_apple");
        giveApple.addProperty("amount", 1);
        ClientActionPolicy.ValidationResult pubGive = policy.validate("e2", giveApple, ClientActionPolicy.ExecutionMode.MULTIPLAYER_PUBLIC);
        assertEquals(ClientActionPolicy.Outcome.REJECTED, pubGive.outcome(), "give_item rechazado en servidor público");

        JsonObject localTitle = new JsonObject();
        localTitle.addProperty("action", "world_effect");
        localTitle.addProperty("fx", "title_overlay");
        localTitle.addProperty("text", "¡Hola directo!");
        ClientActionPolicy.ValidationResult pubCosmetic = policy.validate("e3", localTitle, ClientActionPolicy.ExecutionMode.MULTIPLAYER_PUBLIC);
        assertTrue(pubCosmetic.isOk(), "title_overlay cosmético permitido localmente en servidor público");
        assertEquals(ClientActionPolicy.ExecutionRoute.CLIENT_COSMETIC, pubCosmetic.action().route(), "ruta cosmética local");

        // B. Cooldown anti-spam activo tras acción aceptada
        ClientActionPolicy.ValidationResult rateLimited = policy.validate("e4", localTitle, ClientActionPolicy.ExecutionMode.MULTIPLAYER_PUBLIC);
        assertEquals(ClientActionPolicy.Outcome.RATE_LIMITED, rateLimited.outcome(), "cooldown activo -> rate_limited");

        // C. En Un jugador (SINGLEPLAYER): acepta spawn_mob seguro, bloquea wither/bedrock, sanitiza comandos
        fakeClock.addAndGet(2_000L);
        ClientActionPolicy.ValidationResult spSpawn = policy.validate("e5", spawnMob, ClientActionPolicy.ExecutionMode.SINGLEPLAYER);
        assertTrue(spSpawn.isOk(), "spawn_mob permitido en Un jugador");
        assertEquals(ClientActionPolicy.ExecutionRoute.INTEGRATED_SERVER, spSpawn.action().route(), "ruta servidor integrado");
        List<String> cmds = ClientActionPolicy.buildVanillaCommands(spSpawn.action(), "ChrisVT");
        assertEquals(2, cmds.size(), "2 comandos summon");
        assertTrue(cmds.get(0).startsWith("execute at ChrisVT run summon minecraft:pig "), "comando summon sanitizado");

        fakeClock.addAndGet(2_000L);
        JsonObject badMob = new JsonObject();
        badMob.addProperty("action", "spawn_mob");
        badMob.addProperty("entity", "wither");
        ClientActionPolicy.ValidationResult blockedMob = policy.validate("e6", badMob, ClientActionPolicy.ExecutionMode.SINGLEPLAYER);
        assertEquals(ClientActionPolicy.Outcome.REJECTED, blockedMob.outcome(), "wither bloqueado");

        JsonObject badItem = new JsonObject();
        badItem.addProperty("action", "give_item");
        badItem.addProperty("item", "bedrock");
        ClientActionPolicy.ValidationResult blockedItem = policy.validate("e7", badItem, ClientActionPolicy.ExecutionMode.SINGLEPLAYER);
        assertEquals(ClientActionPolicy.Outcome.REJECTED, blockedItem.outcome(), "bedrock bloqueado");

        // D. Sanitización de inyección de jugador en comando
        JsonObject injGive = new JsonObject();
        injGive.addProperty("action", "give_item");
        injGive.addProperty("item", "diamond");
        injGive.addProperty("amount", 1);
        injGive.addProperty("targetPlayer", "Chris; op evil");
        ClientActionPolicy.ValidationResult injRes = policy.validate("e8", injGive, ClientActionPolicy.ExecutionMode.SINGLEPLAYER);
        assertTrue(injRes.isOk(), "give diamond válido");
        List<String> safeCmds = ClientActionPolicy.buildVanillaCommands(injRes.action(), "ChrisVT");
        assertEquals("give @s minecraft:diamond 1", safeCmds.get(0), "selector malicioso degradado a @s");

        // E. Interruptor maestro allowRemoteActions=false -> disabled
        policy.setAllowRemoteActions(false);
        fakeClock.addAndGet(2_000L);
        ClientActionPolicy.ValidationResult disabledRes = policy.validate("e9", localTitle, ClientActionPolicy.ExecutionMode.SINGLEPLAYER);
        assertEquals(ClientActionPolicy.Outcome.DISABLED, disabledRes.outcome(), "disabled cuando allowRemoteActions=false");

        // F. Resolución de modo de ejecución (OP multijugador exige opt-in explícito)
        policy.updateSettings(ClientActionPolicy.Settings.defaults());
        assertEquals(ClientActionPolicy.ExecutionMode.MULTIPLAYER_PUBLIC,
                policy.resolveExecutionMode(true, false, 4),
                "OP sin opt-in sigue en MULTIPLAYER_PUBLIC");
        policy.updateSettings(ClientActionPolicy.Settings.defaults().withAllowMultiplayerOpActions(true));
        assertEquals(ClientActionPolicy.ExecutionMode.MULTIPLAYER_OP,
                policy.resolveExecutionMode(true, false, 2),
                "OP >= 2 con opt-in entra en MULTIPLAYER_OP");
        assertEquals(ClientActionPolicy.ExecutionMode.DISCONNECTED,
                policy.resolveExecutionMode(false, false, 4),
                "fuera de mundo -> DISCONNECTED");

        System.out.println("[FabricSelfTest] PASS: ClientActionPolicy (seguridad público vs singleplayer, sanitización, cooldown, disabled)");
    }

    private static void testWebSocketRoundtripAndReconnect() throws Exception {
        String validToken = "bs_tok_fabric_selftest_secret";
        MockWsServer server = new MockWsServer(validToken);
        server.start();
        try {
            String wsUrl = "ws://127.0.0.1:" + server.getPort() + "/ws";
            FabricWsBridgeClient client = new FabricWsBridgeClient(wsUrl, validToken, "Fabric 1.21+", LOGGER);
            ClientActionPolicy policy = new ClientActionPolicy(ClientActionPolicy.Settings.defaults());

            client.setMinecraftActionHandler((envId, payload) -> {
                ClientActionPolicy.ValidationResult res = policy.validate(
                        envId, payload, ClientActionPolicy.ExecutionMode.MULTIPLAYER_PUBLIC
                );
                client.sendMinecraftActionResult(
                        res.actionId(), res.requestId(), res.wireAction(),
                        res.outcome().wireValue(), res.detail(), "ChrisVT"
                );
            });

            client.connect();
            waitUntil(5_000, client::isAuthenticated, "Cliente Fabric debe autenticarse");
            assertEquals("minecraft.fabric", server.lastClientSource.get(), "Header X-Client-Source debe ser minecraft.fabric");

            // Enviar minecraft.event y trigger.fire
            JsonObject evData = new JsonObject();
            evData.addProperty("damage", 4.0);
            client.sendMinecraftEvent("player.damage", "ChrisVT", evData);
            client.sendTriggerFire("saludo", Map.of("origen", "fabric"));

            waitUntil(3_000, () -> server.receivedTypes.contains("minecraft.event")
                    && server.receivedTypes.contains("trigger.fire"), "Debe recibir minecraft.event y trigger.fire");

            // Enviar minecraft.action hacia el mod cliente:
            // 1) spawn_mob rechazado en público (no arma cooldown)
            // 2) title_overlay cosmético aceptado (arma cooldown de 1 s)
            // 3) segundo title_overlay inmediato -> rate_limited
            server.sendMinecraftActionToLatest("act-rej", "spawn_mob", Map.of("entity", "zombie", "count", 2));
            server.sendMinecraftActionToLatest("act-ok", "world_effect", Map.of("fx", "title_overlay", "text", "Hola Fabric"));
            server.sendMinecraftActionToLatest("act-rl", "world_effect", Map.of("fx", "sound_bell"));

            waitUntil(4_000, () -> server.actionResults.size() >= 3, "Debe recibir 3 minecraft.action.result");
            assertEquals("rejected", server.actionResults.get(0).get("outcome").getAsString(), "spawn_mob rechazado en público");
            assertEquals("ok", server.actionResults.get(1).get("outcome").getAsString(), "cosmético local ok");
            assertEquals("rate_limited", server.actionResults.get(2).get("outcome").getAsString(), "enfriamiento activo rate_limited");

            // Forzar desconexión y verificar reconexión automática
            int beforeConnections = server.acceptedConnections.get();
            server.dropActiveClient();
            waitUntil(6_000, () -> server.acceptedConnections.get() > beforeConnections && client.isAuthenticated(),
                    "Cliente debe reconectarse automáticamente tras caída");

            client.shutdown();

            // Verificar que 4401 detiene reintentos
            FabricWsBridgeClient badClient = new FabricWsBridgeClient(wsUrl, "bs_tok_invalid", "FabricBad", LOGGER);
            badClient.connect();
            waitUntil(4_000, badClient::isAuthRejected, "Token inválido debe marcar authRejected (4401)");
            badClient.shutdown();

            // Verificar 0 hilos de scheduler/http vivos con prefijo blockstager-fabric-ws-
            Thread.sleep(200);
            long leakedThreads = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.isAlive() && t.getName().startsWith("blockstager-fabric-ws-"))
                    .count();
            assertEquals(0L, leakedThreads, "0 hilos huérfanos tras shutdown()");

            System.out.println("[FabricSelfTest] PASS: FabricWsBridgeClient roundtrip WS, reconexión, 4401 y 0 hilos huérfanos");
        } finally {
            server.close();
        }
    }

    private static void assertHasEvent(List<ClientStateTracker.EmittedSignal> list, String expectedEvent) {
        boolean found = list.stream().anyMatch(e -> expectedEvent.equals(e.eventName()));
        if (!found) {
            throw new AssertionError("Se esperaba el evento '" + expectedEvent + "', pero se emitieron: " + list);
        }
    }

    private static void assertTrue(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " (esperado=" + expected + ", obtenido=" + actual + ")");
        }
    }

    private static void waitUntil(long timeoutMs, java.util.function.BooleanSupplier cond, String desc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timeout esperando: " + desc);
    }

    private static final class MockWsServer implements AutoCloseable {
        private final String expectedToken;
        private final ServerSocket serverSocket;
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicReference<Socket> activeSocket = new AtomicReference<>();
        private final AtomicReference<OutputStream> activeOut = new AtomicReference<>();

        final AtomicInteger acceptedConnections = new AtomicInteger(0);
        final AtomicReference<String> lastClientSource = new AtomicReference<>("");
        final CopyOnWriteArrayList<String> receivedTypes = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<JsonObject> actionResults = new CopyOnWriteArrayList<>();

        MockWsServer(String expectedToken) throws IOException {
            this.expectedToken = expectedToken;
            this.serverSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        void start() {
            pool.submit(() -> {
                while (!closed.get()) {
                    try {
                        Socket s = serverSocket.accept();
                        acceptedConnections.incrementAndGet();
                        pool.submit(() -> handleClient(s));
                    } catch (IOException ignored) {
                        if (closed.get()) {
                            return;
                        }
                    }
                }
            });
        }

        void dropActiveClient() {
            Socket s = activeSocket.getAndSet(null);
            activeOut.set(null);
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }

        void sendMinecraftActionToLatest(String actionId, String action, Map<String, Object> extra) throws IOException {
            OutputStream out = activeOut.get();
            if (out == null) {
                throw new IllegalStateException("Sin cliente WS activo");
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("actionId", actionId);
            payload.addProperty("action", action);
            for (Map.Entry<String, Object> entry : extra.entrySet()) {
                Object v = entry.getValue();
                if (v instanceof Number n) {
                    payload.addProperty(entry.getKey(), n);
                } else if (v instanceof Boolean b) {
                    payload.addProperty(entry.getKey(), b);
                } else if (v != null) {
                    payload.addProperty(entry.getKey(), v.toString());
                }
            }
            JsonObject env = new JsonObject();
            env.addProperty("protocolVersion", FabricWsBridgeClient.PROTOCOL_VERSION);
            env.addProperty("id", actionId);
            env.addProperty("type", "minecraft.action");
            env.addProperty("source", "blockstager-server");
            env.addProperty("timestamp", Instant.now().toEpochMilli());
            env.add("payload", payload);
            writeTextFrame(out, env.toString());
        }

        private void handleClient(Socket socket) {
            try (Socket s = socket) {
                BufferedInputStream in = new BufferedInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();

                String headers = readHttpHeaders(in);
                String wsKey = extractHeader(headers, "Sec-WebSocket-Key");
                String authHeader = extractHeader(headers, "Authorization");
                String clientSource = extractHeader(headers, "X-Client-Source");
                if (clientSource != null) {
                    lastClientSource.set(clientSource);
                }
                if (wsKey == null) {
                    return;
                }

                String accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1")
                                .digest((wsKey + WS_GUID).getBytes(StandardCharsets.UTF_8))
                );
                String response = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();

                boolean valid = ("Bearer " + expectedToken).equals(authHeader);
                if (!valid) {
                    JsonObject err = new JsonObject();
                    err.addProperty("protocolVersion", FabricWsBridgeClient.PROTOCOL_VERSION);
                    err.addProperty("id", "err_1");
                    err.addProperty("type", "auth.error");
                    err.addProperty("source", "blockstager-server");
                    err.addProperty("timestamp", Instant.now().toEpochMilli());
                    JsonObject p = new JsonObject();
                    p.addProperty("code", "unauthorized");
                    p.addProperty("message", "Invalid token");
                    err.add("payload", p);
                    writeTextFrame(out, err.toString());
                    writeCloseFrame(out, 4401);
                    return;
                }

                activeSocket.set(s);
                activeOut.set(out);

                JsonObject okEnv = new JsonObject();
                okEnv.addProperty("protocolVersion", FabricWsBridgeClient.PROTOCOL_VERSION);
                okEnv.addProperty("id", "ok_1");
                okEnv.addProperty("type", "auth.ok");
                okEnv.addProperty("source", "blockstager-server");
                okEnv.addProperty("timestamp", Instant.now().toEpochMilli());
                JsonObject okPayload = new JsonObject();
                okPayload.addProperty("sessionId", "mock_sess_1");
                okEnv.add("payload", okPayload);
                writeTextFrame(out, okEnv.toString());

                while (!closed.get() && !s.isClosed()) {
                    String text = readClientTextFrame(in, out);
                    if (text == null) {
                        break;
                    }
                    JsonElement parsed = JsonParser.parseString(text);
                    if (!parsed.isJsonObject()) {
                        continue;
                    }
                    JsonObject obj = parsed.getAsJsonObject();
                    String type = obj.has("type") ? obj.get("type").getAsString() : "";
                    receivedTypes.add(type);
                    if ("minecraft.action.result".equals(type) && obj.has("payload") && obj.get("payload").isJsonObject()) {
                        actionResults.add(obj.getAsJsonObject("payload"));
                    }
                }
            } catch (Exception ignored) {
                // Socket cerrado en prueba
            }
        }

        private static String readHttpHeaders(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int prev3 = 0, prev2 = 0, prev1 = 0, b;
            while ((b = in.read()) != -1) {
                buf.write(b);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') {
                    break;
                }
                prev3 = prev2;
                prev2 = prev1;
                prev1 = b;
            }
            return buf.toString(StandardCharsets.UTF_8);
        }

        private static String extractHeader(String headers, String name) {
            String target = name.toLowerCase(java.util.Locale.ROOT) + ":";
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith(target)) {
                    return line.substring(target.length()).trim();
                }
            }
            return null;
        }

        private static String readClientTextFrame(InputStream in, OutputStream out) throws IOException {
            while (true) {
                int b1 = in.read();
                if (b1 == -1) return null;
                int b2 = in.read();
                if (b2 == -1) return null;
                int opcode = b1 & 0x0F;
                boolean masked = (b2 & 0x80) != 0;
                long len = b2 & 0x7F;
                if (len == 126) {
                    len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | (in.read() & 0xFF);
                    }
                }
                byte[] mask = new byte[4];
                if (masked) {
                    if (in.readNBytes(mask, 0, 4) < 4) return null;
                }
                byte[] payload = in.readNBytes((int) len);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }
                if (opcode == 0x8) {
                    return null;
                }
                if (opcode == 0x9) {
                    // Ping -> Pong
                    synchronized (out) {
                        out.write(0x8A);
                        out.write(payload.length);
                        out.write(payload);
                        out.flush();
                    }
                    continue;
                }
                if (opcode == 0x1) {
                    return new String(payload, StandardCharsets.UTF_8);
                }
            }
        }

        private static void writeTextFrame(OutputStream out, String text) throws IOException {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            synchronized (out) {
                out.write(0x81);
                if (data.length <= 125) {
                    out.write(data.length);
                } else if (data.length <= 65535) {
                    out.write(126);
                    out.write((data.length >> 8) & 0xFF);
                    out.write(data.length & 0xFF);
                } else {
                    out.write(127);
                    for (int i = 7; i >= 0; i--) {
                        out.write((int) ((data.length >> (8 * i)) & 0xFF));
                    }
                }
                out.write(data);
                out.flush();
            }
        }

        private static void writeCloseFrame(OutputStream out, int code) throws IOException {
            synchronized (out) {
                out.write(0x88);
                out.write(2);
                out.write((code >> 8) & 0xFF);
                out.write(code & 0xFF);
                out.flush();
            }
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            dropActiveClient();
            serverSocket.close();
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
