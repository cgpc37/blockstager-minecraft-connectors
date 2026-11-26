package dev.blockstager.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cliente WebSocket persistente para el protocolo local de Blockstager Studio
 * ({@code blockstager.proto/1.2}).
 *
 * <p>No depende de APIs de Bukkit/Paper para poder probarse de forma aislada
 * con {@link SelfTest} usando únicamente JDK 21 y Gson.</p>
 */
public final class WsBridgeClient {

    public static final String PROTOCOL_VERSION = "blockstager.proto/1.2";
    public static final String DEFAULT_SOURCE = "minecraft.paper";
    public static final int CLOSE_UNAUTHORIZED = 4401;
    public static final int CLOSE_BAD_PROTOCOL = 4406;

    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;
    private static final double BACKOFF_MULTIPLIER = 1.5;
    private static final long PING_INTERVAL_SECONDS = 15L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Gson GSON = new Gson();

    private final URI endpoint;
    private final String bearerToken;
    private final String clientName;
    private final Logger logger;

    private final ScheduledExecutorService scheduler;
    private final ExecutorService httpExecutor;
    private final HttpClient httpClient;

    private final AtomicReference<WebSocket> socketRef = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<WebSocket>> pendingConnectRef = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> reconnectTaskRef = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> pingTaskRef = new AtomicReference<>();

    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicBoolean authRejected = new AtomicBoolean(false);
    private final AtomicBoolean authenticated = new AtomicBoolean(false);

    private final AtomicInteger backoffAttempt = new AtomicInteger(0);
    private final AtomicInteger reconnectCount = new AtomicInteger(0);
    private final AtomicLong lastLatencyMs = new AtomicLong(-1L);
    private final AtomicLong authSentNanos = new AtomicLong(0L);
    private final AtomicLong lastPingSentNanos = new AtomicLong(0L);

    @FunctionalInterface
    public interface IncomingActionHandler {
        void onMinecraftAction(String envelopeId, JsonObject payload);
    }

    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicReference<JsonObject> lastState = new AtomicReference<>();
    private final AtomicReference<IncomingActionHandler> actionHandlerRef = new AtomicReference<>();

    private final Object sendLock = new Object();
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    public WsBridgeClient(String wsUrl, String bearerToken, String clientName, Logger logger) {
        this.endpoint = URI.create(Objects.requireNonNull(wsUrl, "wsUrl").trim());
        this.bearerToken = bearerToken != null ? bearerToken.trim() : "";
        this.clientName = (clientName != null && !clientName.isBlank()) ? clientName.trim() : "Paper";
        this.logger = logger != null ? logger : Logger.getLogger(WsBridgeClient.class.getName());

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "blockstager-ws-scheduler");
            t.setDaemon(true);
            return t;
        });

        AtomicInteger httpThreadSeq = new AtomicInteger(1);
        this.httpExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "blockstager-ws-http-" + httpThreadSeq.getAndIncrement());
            t.setDaemon(true);
            return t;
        });

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(this.httpExecutor)
                .build();
    }

    /**
     * Inicia una conexión WebSocket asíncrona si no existe una activa o en progreso.
     */
    public void connect() {
        if (shutdown.get() || authRejected.get()) {
            return;
        }
        if (socketRef.get() != null) {
            return;
        }

        cancelScheduledReconnect();

        CompletableFuture<WebSocket> existingAttempt = pendingConnectRef.get();
        if (existingAttempt != null && !existingAttempt.isDone()) {
            return;
        }

        WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .header("Authorization", "Bearer " + bearerToken)
                .header("X-Client-Name", clientName)
                .header("X-Client-Source", DEFAULT_SOURCE);

        ConnectionListener listener = new ConnectionListener();
        CompletableFuture<WebSocket> future;
        try {
            future = builder.buildAsync(endpoint, listener);
        } catch (RuntimeException ex) {
            lastError.set(ex.getMessage());
            logger.log(Level.WARNING, "[Blockstager] No se pudo iniciar la conexión WS: " + ex.getMessage());
            scheduleReconnect();
            return;
        }

        if (!pendingConnectRef.compareAndSet(existingAttempt, future)) {
            future.whenComplete((ws, err) -> {
                if (ws != null) {
                    ws.abort();
                }
            });
            return;
        }

        future.whenComplete((ws, err) -> {
            pendingConnectRef.compareAndSet(future, null);

            if (shutdown.get()) {
                if (ws != null) {
                    ws.abort();
                }
                return;
            }

            if (err != null) {
                String errText = formatThrowable(err);
                lastError.set(errText);
                logger.log(Level.WARNING, "[Blockstager] Error al conectar a " + endpoint + ": " + errText);
                scheduleReconnect();
                return;
            }

            listener.bind(ws);
            WebSocket previous = socketRef.getAndSet(ws);
            if (previous != null && previous != ws) {
                closeSocketQuietly(previous, "replaced");
            }

            backoffAttempt.set(0);
            lastError.set(null);
            logger.info("[Blockstager] Conectado a " + endpoint + "; enviando autenticación...");
            sendAuthFrame(ws);
            startPingLoop(ws);
        });
    }

    /**
     * Fuerza una reconexión inmediata, limpiando rechazos previos de autenticación y el backoff.
     */
    public void reconnectNow() {
        if (shutdown.get()) {
            return;
        }
        cancelScheduledReconnect();
        cancelPingLoop();

        CompletableFuture<WebSocket> pending = pendingConnectRef.getAndSet(null);
        if (pending != null) {
            pending.cancel(true);
        }

        WebSocket current = socketRef.getAndSet(null);
        authenticated.set(false);
        sessionId.set(null);
        if (current != null) {
            closeSocketQuietly(current, "reconnect");
        }

        authRejected.set(false);
        backoffAttempt.set(0);
        lastError.set(null);
        connect();
    }

    public boolean isConnected() {
        return !shutdown.get() && socketRef.get() != null;
    }

    public boolean isAuthenticated() {
        return isConnected() && authenticated.get();
    }

    public long getLastLatencyMs() {
        return lastLatencyMs.get();
    }

    public String getEndpointUrl() {
        return endpoint.toString();
    }

    public int getReconnectCount() {
        return reconnectCount.get();
    }

    public boolean isAuthRejected() {
        return authRejected.get();
    }

    public String getSessionId() {
        return sessionId.get();
    }

    public String getLastError() {
        return lastError.get();
    }

    public JsonObject getLastState() {
        JsonObject state = lastState.get();
        return state != null ? state.deepCopy() : null;
    }

    /**
     * Registra el manejador para mensajes entrantes {@code minecraft.action}.
     */
    public void setMinecraftActionHandler(IncomingActionHandler handler) {
        this.actionHandlerRef.set(handler);
    }

    /**
     * Envía confirmación {@code minecraft.action.result} de vuelta a Blockstager Studio.
     */
    public boolean sendMinecraftActionResult(
            String actionId,
            String requestId,
            String action,
            String outcome,
            String detail,
            String playerName
    ) {
        WebSocket ws = socketRef.get();
        if (ws == null || shutdown.get()) {
            return false;
        }
        JsonObject payload = new JsonObject();
        if (actionId != null && !actionId.isBlank()) {
            payload.addProperty("actionId", actionId.trim());
        }
        if (requestId != null && !requestId.isBlank()) {
            payload.addProperty("requestId", requestId.trim());
        }
        payload.addProperty("action", (action != null && !action.isBlank()) ? action.trim() : "unknown");
        payload.addProperty("outcome", (outcome != null && !outcome.isBlank()) ? outcome.trim() : "rejected");
        if (detail != null && !detail.isBlank()) {
            payload.addProperty("detail", detail.trim());
        }
        if (playerName != null && !playerName.isBlank()) {
            payload.addProperty("player", playerName.trim());
        }
        sendEnvelope(ws, "minecraft.action.result", payload);
        return true;
    }

    /**
     * Envía un evento de Minecraft con tipo {@code minecraft.event}.
     */
    public boolean sendMinecraftEvent(String eventName, String playerName, JsonObject data) {
        if (eventName == null || eventName.isBlank()) {
            return false;
        }
        WebSocket ws = socketRef.get();
        if (ws == null || shutdown.get()) {
            return false;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("event", eventName);
        if (playerName != null && !playerName.isBlank()) {
            payload.addProperty("player", playerName);
        }
        payload.add("data", data != null ? data.deepCopy() : new JsonObject());
        sendEnvelope(ws, "minecraft.event", payload);
        return true;
    }

    /**
     * Envía un disparo de trigger con tipo {@code trigger.fire}.
     */
    public boolean sendTriggerFire(String command, Map<String, Object> args) {
        JsonObject argsObj = new JsonObject();
        if (args != null) {
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                JsonElement value = GSON.toJsonTree(entry.getValue());
                argsObj.add(entry.getKey(), value);
            }
        }
        return sendTriggerFire(command, argsObj);
    }

    /**
     * Sobrecarga para enviar {@code trigger.fire} con un {@link JsonObject} de argumentos.
     */
    public boolean sendTriggerFire(String command, JsonObject args) {
        if (command == null || command.isBlank()) {
            return false;
        }
        WebSocket ws = socketRef.get();
        if (ws == null || shutdown.get()) {
            return false;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("command", command.trim());
        payload.add("args", args != null ? args.deepCopy() : new JsonObject());
        sendEnvelope(ws, "trigger.fire", payload);
        return true;
    }

    /**
     * Envía actualización de skin con tipo {@code skin.set}.
     */
    public boolean sendSkinSet(String username, String textureUrl, String variant) {
        WebSocket ws = socketRef.get();
        if (ws == null || shutdown.get()) {
            return false;
        }

        JsonObject payload = new JsonObject();
        if (username != null && !username.isBlank()) {
            payload.addProperty("username", username);
        }
        if (textureUrl != null && !textureUrl.isBlank()) {
            payload.addProperty("textureUrl", textureUrl);
        }
        if (variant != null && !variant.isBlank()) {
            String normalized = "slim".equalsIgnoreCase(variant) ? "slim" : "classic";
            payload.addProperty("variant", normalized);
        }
        sendEnvelope(ws, "skin.set", payload);
        return true;
    }

    /**
     * Envía acción canónica {@code item.equip} para sincronizar un objeto en un socket.
     */
    public boolean sendItemEquip(String socket, String itemId) {
        WebSocket ws = socketRef.get();
        if (ws == null || shutdown.get()) {
            return false;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("socket", (socket != null && !socket.isBlank()) ? socket : "hand_right_socket");
        if (itemId != null) {
            payload.addProperty("itemId", itemId);
        }
        sendEnvelope(ws, "item.equip", payload);
        return true;
    }

    /**
     * Cierra de forma determinista el socket activo, tareas programadas y ejecutores
     * para garantizar 0 sockets huérfanos y 0 hilos filtrados.
     */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }

        cancelScheduledReconnect();
        cancelPingLoop();

        CompletableFuture<WebSocket> pending = pendingConnectRef.getAndSet(null);
        if (pending != null) {
            pending.cancel(true);
            if (pending.isDone() && !pending.isCompletedExceptionally() && !pending.isCancelled()) {
                try {
                    WebSocket lateWs = pending.getNow(null);
                    if (lateWs != null) {
                        closeSocketQuietly(lateWs, "shutdown");
                    }
                } catch (RuntimeException ignored) {
                    // Ignorar durante cierre
                }
            }
        }

        WebSocket ws = socketRef.getAndSet(null);
        authenticated.set(false);
        sessionId.set(null);

        if (ws != null) {
            closeSocketQuietly(ws, "shutdown");
        }

        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            httpClient.shutdownNow();
            httpClient.awaitTermination(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // Continuar cierre limpio
        }

        try {
            httpClient.close();
        } catch (RuntimeException ignored) {
            // Continuar cierre limpio
        }

        httpExecutor.shutdownNow();
        try {
            httpExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendAuthFrame(WebSocket ws) {
        JsonObject payload = new JsonObject();
        payload.addProperty("token", bearerToken);
        payload.addProperty("clientName", clientName);
        authSentNanos.set(System.nanoTime());
        sendEnvelope(ws, "auth", payload);
    }

    private void sendEnvelope(WebSocket ws, String type, JsonObject payload) {
        JsonObject env = new JsonObject();
        env.addProperty("protocolVersion", PROTOCOL_VERSION);
        env.addProperty("id", "mc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        env.addProperty("type", type);
        env.addProperty("source", DEFAULT_SOURCE);
        env.addProperty("timestamp", Instant.now().toEpochMilli());
        env.add("payload", payload != null ? payload : new JsonObject());

        final String serialized = GSON.toJson(env);
        enqueueSend(ws, () -> ws.sendText(serialized, true));
    }

    private void enqueueSend(WebSocket ws, java.util.function.Supplier<CompletableFuture<?>> action) {
        synchronized (sendLock) {
            if (shutdown.get() || socketRef.get() != ws) {
                return;
            }
            sendChain = sendChain
                    .handle((ignored, err) -> null)
                    .thenCompose(ignored -> {
                        if (shutdown.get() || socketRef.get() != ws) {
                            return CompletableFuture.completedFuture(null);
                        }
                        try {
                            return action.get().handle((res, ex) -> {
                                if (ex != null && !shutdown.get()) {
                                    Throwable cause = unwrap(ex);
                                    logger.log(Level.WARNING, "[Blockstager] Error al enviar trama WS: " + cause.getMessage());
                                }
                                return null;
                            });
                        } catch (RuntimeException ex) {
                            if (!shutdown.get()) {
                                logger.log(Level.WARNING, "[Blockstager] Excepción enviando trama WS: " + ex.getMessage());
                            }
                            return CompletableFuture.completedFuture(null);
                        }
                    });
        }
    }

    private void startPingLoop(WebSocket ws) {
        cancelPingLoop();
        if (shutdown.get()) {
            return;
        }
        try {
            ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
                if (shutdown.get() || socketRef.get() != ws || !authenticated.get()) {
                    return;
                }
                lastPingSentNanos.set(System.nanoTime());
                ByteBuffer pingPayload = ByteBuffer.wrap("bs-ping".getBytes(StandardCharsets.UTF_8));
                enqueueSend(ws, () -> ws.sendPing(pingPayload));
            }, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
            pingTaskRef.set(task);
        } catch (RuntimeException ignored) {
            // Scheduler ya cerrado
        }
    }

    private void cancelPingLoop() {
        ScheduledFuture<?> pingTask = pingTaskRef.getAndSet(null);
        if (pingTask != null) {
            pingTask.cancel(false);
        }
    }

    private void cancelScheduledReconnect() {
        ScheduledFuture<?> task = reconnectTaskRef.getAndSet(null);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void scheduleReconnect() {
        if (shutdown.get() || authRejected.get()) {
            return;
        }
        ScheduledFuture<?> existing = reconnectTaskRef.get();
        if (existing != null && !existing.isDone()) {
            return;
        }

        int attempt = backoffAttempt.getAndIncrement();
        long delayMs = computeBackoffDelayMs(attempt);

        try {
            ScheduledFuture<?> scheduled = scheduler.schedule(() -> {
                reconnectTaskRef.set(null);
                if (!shutdown.get() && !authRejected.get()) {
                    reconnectCount.incrementAndGet();
                    connect();
                }
            }, delayMs, TimeUnit.MILLISECONDS);
            if (!reconnectTaskRef.compareAndSet(existing, scheduled)) {
                scheduled.cancel(false);
            } else {
                logger.info("[Blockstager] Reintentando conexión en " + delayMs + " ms (intento #" + (attempt + 1) + ")");
            }
        } catch (RuntimeException ignored) {
            // Scheduler ya cerrado durante shutdown
        }
    }

    static long computeBackoffDelayMs(int attempt) {
        int clampedAttempt = Math.max(0, Math.min(attempt, 20));
        double raw = INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, clampedAttempt);
        long base = Math.min(MAX_BACKOFF_MS, Math.round(raw));
        // Jitter acotado en [-12%, +8%] para evitar sincronización de reintentos sin exceder 30 s
        double jitterFactor = 0.88 + (ThreadLocalRandom.current().nextDouble() * 0.20);
        long withJitter = Math.round(base * jitterFactor);
        return Math.max(INITIAL_BACKOFF_MS, Math.min(MAX_BACKOFF_MS, withJitter));
    }

    private void closeSocketQuietly(WebSocket ws, String reason) {
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, reason)
                    .orTimeout(750, TimeUnit.MILLISECONDS)
                    .join();
        } catch (RuntimeException ignored) {
            // Si el peer no responde el cierre limpio a tiempo, abortamos abajo
        } finally {
            try {
                ws.abort();
            } catch (RuntimeException ignored) {
                // Socket ya cerrado
            }
        }
    }

    private void handleIncomingJson(String rawText) {
        JsonObject env;
        try {
            JsonElement parsed = JsonParser.parseString(rawText);
            if (!parsed.isJsonObject()) {
                return;
            }
            env = parsed.getAsJsonObject();
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "[Blockstager] Mensaje JSON inválido recibido: " + ex.getMessage());
            return;
        }

        String type = getString(env, "type");
        if (type == null) {
            return;
        }
        JsonObject payload = env.has("payload") && env.get("payload").isJsonObject()
                ? env.getAsJsonObject("payload")
                : new JsonObject();

        switch (type) {
            case "auth.ok" -> {
                authenticated.set(true);
                authRejected.set(false);
                lastError.set(null);
                backoffAttempt.set(0);
                String sid = getString(payload, "sessionId");
                sessionId.set(sid);
                long sentNanos = authSentNanos.get();
                if (sentNanos > 0) {
                    long rtt = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - sentNanos));
                    lastLatencyMs.set(rtt);
                }
                logger.info("[Blockstager] Sesión autenticada (" + PROTOCOL_VERSION + ")"
                        + (sid != null ? " sessionId=" + sid : ""));
            }
            case "auth.error" -> {
                authenticated.set(false);
                String code = getString(payload, "code");
                String message = getString(payload, "message");
                String detail = (code != null ? code : "auth_error")
                        + (message != null ? ": " + message : "");
                lastError.set(detail);
                if ("unauthorized".equalsIgnoreCase(code)) {
                    authRejected.set(true);
                    cancelScheduledReconnect();
                    logger.severe("[Blockstager] Autenticación rechazada (" + detail + "). Revisa el token en config.yml.");
                } else {
                    logger.warning("[Blockstager] Error de autenticación (" + detail + ").");
                }
            }
            case "state.changed" -> lastState.set(payload.deepCopy());
            case "minecraft.action" -> {
                IncomingActionHandler handler = actionHandlerRef.get();
                String envId = getString(env, "id");
                if (handler != null) {
                    try {
                        handler.onMinecraftAction(envId != null ? envId : "", payload.deepCopy());
                    } catch (RuntimeException ex) {
                        logger.log(Level.WARNING, "[Blockstager] Error despachando minecraft.action: " + ex.getMessage());
                    }
                } else {
                    String actionName = getString(payload, "action");
                    sendMinecraftActionResult(
                            envId,
                            envId,
                            actionName != null ? actionName : "unknown",
                            "disabled",
                            "El servidor no tiene activo el ejecutor de acciones remotas.",
                            null
                    );
                }
            }
            case "error" -> {
                String code = getString(payload, "code");
                String message = getString(payload, "message");
                String detail = (code != null ? code : "error")
                        + (message != null ? ": " + message : "");
                lastError.set(detail);
                logger.warning("[Blockstager] Error remoto del servidor: " + detail);
            }
            default -> {
                // Mensajes no manejados por el plugin se ignoran silenciosamente
            }
        }
    }

    private static String getString(JsonObject obj, String member) {
        if (obj == null || !obj.has(member)) {
            return null;
        }
        JsonElement el = obj.get(member);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        return el.getAsString();
    }

    private static Throwable unwrap(Throwable err) {
        Throwable current = err;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private static String formatThrowable(Throwable err) {
        Throwable cause = unwrap(err);
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return cause.getClass().getSimpleName() + ": " + msg;
    }

    private final class ConnectionListener implements WebSocket.Listener {

        private final StringBuilder textBuffer = new StringBuilder();
        private volatile WebSocket boundSocket;

        void bind(WebSocket ws) {
            this.boundSocket = ws;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.boundSocket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String fullMessage = textBuffer.toString();
                textBuffer.setLength(0);
                handleIncomingJson(fullMessage);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            long sent = lastPingSentNanos.get();
            if (sent > 0) {
                long rtt = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - sent));
                lastLatencyMs.set(rtt);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            boolean wasActive = socketRef.compareAndSet(webSocket, null);
            if (wasActive) {
                authenticated.set(false);
                sessionId.set(null);
                cancelPingLoop();
            }

            if (statusCode == CLOSE_UNAUTHORIZED) {
                authRejected.set(true);
                cancelScheduledReconnect();
                lastError.set("Token rechazado (4401)" + (reason != null && !reason.isBlank() ? ": " + reason : ""));
                logger.severe("[Blockstager] Conexión cerrada con código 4401 (no autorizado). Revisa config.yml y ejecuta /bs reload.");
            } else if (wasActive && !shutdown.get() && !authRejected.get()) {
                logger.warning("[Blockstager] Conexión WS cerrada (código=" + statusCode
                        + (reason != null && !reason.isBlank() ? ", motivo=" + reason : "") + ").");
                scheduleReconnect();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            boolean wasActive = socketRef.compareAndSet(webSocket, null) || boundSocket == webSocket;
            if (wasActive) {
                authenticated.set(false);
                sessionId.set(null);
                cancelPingLoop();
            }

            if (!shutdown.get()) {
                Throwable cause = unwrap(error);
                lastError.set(cause.getMessage());
                logger.log(Level.WARNING, "[Blockstager] Error en WebSocket: " + cause.getMessage());
                if (wasActive && !authRejected.get()) {
                    scheduleReconnect();
                }
            }
        }
    }
}
