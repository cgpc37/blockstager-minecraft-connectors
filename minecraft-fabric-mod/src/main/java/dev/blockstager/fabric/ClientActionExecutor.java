package dev.blockstager.fabric;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.logging.Logger;

/**
 * Ejecuta acciones remotas {@code minecraft.action} en el hilo principal del
 * cliente (y en el servidor integrado local cuando se juega en un jugador),
 * aplicando siempre {@link ClientActionPolicy} y enviando la confirmación
 * {@code minecraft.action.result} a Blockstager Studio.
 */
public final class ClientActionExecutor {

    private final BlockstagerFabricClient mod;
    private final MinecraftReflectBridge reflectBridge;
    private final ClientActionPolicy policy;
    private final Logger logger;

    public ClientActionExecutor(
            BlockstagerFabricClient mod,
            MinecraftReflectBridge reflectBridge,
            ClientActionPolicy policy,
            Logger logger
    ) {
        this.mod = mod;
        this.reflectBridge = reflectBridge;
        this.policy = policy;
        this.logger = logger != null ? logger : Logger.getLogger(ClientActionExecutor.class.getName());
    }

    public ClientActionPolicy getPolicy() {
        return policy;
    }

    public void handleIncomingAction(String envelopeId, JsonObject payload) {
        reflectBridge.executeOnClientThread(() -> executeOnClientMainThread(envelopeId, payload, true));
    }

    public ClientActionPolicy.ValidationResult executeLocalTest(String kindRaw, String valueRaw) {
        JsonObject payload = buildTestPayload(kindRaw, valueRaw);
        return executeOnClientMainThread("local-test-" + System.currentTimeMillis(), payload, true);
    }

    private ClientActionPolicy.ValidationResult executeOnClientMainThread(
            String envelopeId,
            JsonObject payload,
            boolean sendResultOverWs
    ) {
        Object clientObj = reflectBridge.getClientInstance();
        ClientStateTracker.PlayerSnapshot snap = reflectBridge.captureSnapshot(clientObj);
        ClientActionPolicy.ExecutionMode mode = policy.resolveExecutionMode(
                snap.inWorld(),
                snap.singleplayer(),
                snap.permissionLevel()
        );

        ClientActionPolicy.ValidationResult validation = policy.validate(envelopeId, payload, mode);
        if (!validation.isOk()) {
            if (sendResultOverWs) {
                sendResult(validation.actionId(), validation.requestId(), validation.wireAction(),
                        validation.outcome().wireValue(), validation.detail(), snap.playerName());
            }
            return validation;
        }

        ClientActionPolicy.ValidatedAction action = validation.action();
        boolean executed;
        String detail;

        switch (action.route()) {
            case CLIENT_COSMETIC -> {
                executed = executeClientCosmetic(action);
                detail = executed
                        ? buildCosmeticSuccessDetail(action)
                        : "No se pudo aplicar el efecto cosmético local en el cliente.";
            }
            case INTEGRATED_SERVER -> {
                List<String> commands = ClientActionPolicy.buildVanillaCommands(action, snap.playerName());
                executed = !commands.isEmpty() && reflectBridge.executeOnIntegratedServer(commands);
                detail = executed
                        ? validation.detail() + " (Un jugador)"
                        : "No se pudo despachar la acción al servidor integrado local.";
            }
            case MULTIPLAYER_OP_COMMAND -> {
                List<String> commands = ClientActionPolicy.buildVanillaCommands(action, snap.playerName());
                executed = !commands.isEmpty() && reflectBridge.sendMultiplayerOpCommands(commands);
                detail = executed
                        ? validation.detail() + " (Operador)"
                        : "No se pudo enviar el comando autorizado al servidor.";
            }
            default -> {
                executed = false;
                detail = "Ruta de ejecución no soportada.";
            }
        }

        ClientActionPolicy.Outcome finalOutcome = executed
                ? ClientActionPolicy.Outcome.OK
                : ClientActionPolicy.Outcome.REJECTED;

        if (sendResultOverWs) {
            sendResult(action.actionId(), action.requestId(), action.wireAction(),
                    finalOutcome.wireValue(), detail, snap.playerName());
        }
        logger.info("[Blockstager Fabric] Acción " + action.wireAction() + " -> "
                + finalOutcome.wireValue() + ": " + detail);
        return new ClientActionPolicy.ValidationResult(
                finalOutcome,
                action.actionId(),
                action.requestId(),
                action.wireAction(),
                detail,
                executed ? action : null
        );
    }

    private boolean executeClientCosmetic(ClientActionPolicy.ValidatedAction action) {
        return switch (action.primaryToken()) {
            case "title_overlay" -> {
                String title = action.textParam().isEmpty() ? "Blockstager" : action.textParam();
                String sub = action.triggeredBy().isEmpty() ? "" : ("Activado por " + action.triggeredBy());
                yield reflectBridge.showLocalTitle(title, sub);
            }
            case "sound_bell", "sound_levelup", "play_sound" ->
                    reflectBridge.playLocalSound(action.primaryToken());
            default -> false;
        };
    }

    private static String buildCosmeticSuccessDetail(ClientActionPolicy.ValidatedAction action) {
        return switch (action.primaryToken()) {
            case "title_overlay" -> "Título local mostrado en pantalla.";
            case "sound_bell" -> "Sonido local de campana reproducido.";
            case "sound_levelup", "play_sound" -> "Sonido local reproducido en el cliente.";
            default -> "Efecto cosmético local aplicado.";
        };
    }

    private void sendResult(
            String actionId,
            String requestId,
            String action,
            String outcome,
            String detail,
            String playerName
    ) {
        FabricWsBridgeClient ws = mod.getBridgeClient();
        if (ws != null) {
            ws.sendMinecraftActionResult(actionId, requestId, action, outcome, detail, playerName);
        }
    }

    private static JsonObject buildTestPayload(String kindRaw, String valueRaw) {
        String kind = kindRaw != null ? kindRaw.trim().toLowerCase(java.util.Locale.ROOT) : "world_effect";
        String val = valueRaw != null ? valueRaw.trim() : "";
        JsonObject payload = new JsonObject();
        payload.addProperty("triggeredBy", "Prueba local (/bs test-action)");
        switch (kind) {
            case "spawn_mob" -> {
                payload.addProperty("action", "spawn_mob");
                payload.addProperty("entity", val.isEmpty() ? "pig" : val);
                payload.addProperty("count", 1);
                payload.addProperty("radiusBlocks", 3);
            }
            case "give_item" -> {
                payload.addProperty("action", "give_item");
                payload.addProperty("item", val.isEmpty() ? "golden_apple" : val);
                payload.addProperty("amount", 1);
            }
            case "potion_effect", "apply_effect" -> {
                payload.addProperty("action", "potion_effect");
                payload.addProperty("effect", val.isEmpty() ? "speed" : val);
                payload.addProperty("durationSec", 10);
                payload.addProperty("amplifier", 0);
            }
            default -> {
                payload.addProperty("action", "world_effect");
                payload.addProperty("fx", val.isEmpty() ? "title_overlay" : val);
                payload.addProperty("text", "Blockstager Fabric listo");
            }
        }
        return payload;
    }
}
