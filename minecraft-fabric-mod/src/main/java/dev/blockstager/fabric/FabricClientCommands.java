package dev.blockstager.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;

/**
 * Registra comandos locales del cliente ({@code /bs} y {@code /bsc}) mediante
 * Brigadier sin enviar nunca mensajes de chat al servidor multijugador.
 */
public final class FabricClientCommands {

    private final BlockstagerFabricClient mod;
    private final MinecraftReflectBridge reflectBridge;

    public FabricClientCommands(BlockstagerFabricClient mod, MinecraftReflectBridge reflectBridge) {
        this.mod = mod;
        this.reflectBridge = reflectBridge;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void register(CommandDispatcher dispatcher) {
        if (dispatcher == null) {
            return;
        }
        dispatcher.register(buildRoot("bs"));
        dispatcher.register(buildRoot("bsc"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LiteralArgumentBuilder buildRoot(String rootName) {
        LiteralArgumentBuilder root = LiteralArgumentBuilder.literal(rootName);

        root.executes(ctx -> {
            reply(ctx.getSource(), "[Blockstager] Usa /" + rootName
                    + " status | connect | reload | trigger <slug> | skin sync | actions status|on|off | test-action <tipo>", false);
            return 1;
        });

        root.then(LiteralArgumentBuilder.literal("status").executes(ctx -> {
            FabricWsBridgeClient ws = mod.getBridgeClient();
            ClientStateTracker.PlayerSnapshot snap = reflectBridge.captureSnapshot(reflectBridge.getClientInstance());
            String modeLabel = snap.singleplayer()
                    ? "Un jugador (servidor integrado)"
                    : (snap.inWorld() ? "Multijugador público" : "Menú principal");
            String conn = (ws != null && ws.isAuthenticated())
                    ? "Conectado y autenticado"
                    : (ws != null && ws.isConnected() ? "Conectado (autenticando…)" : "Desconectado");
            String actionsState = mod.getActionPolicy().isAllowRemoteActions() ? "activadas" : "desactivadas";
            long rtt = ws != null ? ws.getLastLatencyMs() : -1L;
            String rttText = rtt >= 0 ? String.format(Locale.US, " · latencia %.2f s", rtt / 1000.0) : "";
            String stateText = snap.inWorld()
                    ? String.format(Locale.US, " · Vida %.0f/%.0f · Fuego=%s · Mano=%s",
                            snap.health(), snap.maxHealth(), snap.onFire() ? "SÍ" : "NO",
                            ClientStateTracker.toMaterialName(snap.mainHandItemKey()))
                    : "";
            reply(ctx.getSource(), "[Blockstager Fabric] " + conn + " · Modo: " + modeLabel
                    + " · Acciones remotas: " + actionsState + rttText + stateText, false);
            return 1;
        }));

        root.then(LiteralArgumentBuilder.literal("connect").executes(ctx -> {
            mod.reconnectBridge();
            reply(ctx.getSource(), "[Blockstager Fabric] Reconectando con Blockstager Studio…", false);
            return 1;
        }));

        root.then(LiteralArgumentBuilder.literal("reload").executes(ctx -> {
            boolean ok = mod.reloadConfiguration();
            reply(ctx.getSource(), ok
                    ? "[Blockstager Fabric] Configuración recargada desde config/blockstager-fabric.json."
                    : "[Blockstager Fabric] Error al recargar la configuración.", !ok);
            return ok ? 1 : 0;
        }));

        LiteralArgumentBuilder triggerCmd = LiteralArgumentBuilder.literal("trigger");
        triggerCmd.then(RequiredArgumentBuilder.argument("slug", StringArgumentType.word()).executes(ctx -> {
            String slug = StringArgumentType.getString(ctx, "slug");
            FabricWsBridgeClient ws = mod.getBridgeClient();
            boolean sent = ws != null && ws.sendTriggerFire(slug, Collections.emptyMap());
            reply(ctx.getSource(), sent
                    ? "[Blockstager Fabric] Disparador enviado: " + slug
                    : "[Blockstager Fabric] No hay conexión activa con Blockstager Studio.", !sent);
            return sent ? 1 : 0;
        }));
        root.then(triggerCmd);

        LiteralArgumentBuilder skinCmd = LiteralArgumentBuilder.literal("skin");
        skinCmd.then(LiteralArgumentBuilder.literal("sync").executes(ctx -> {
            boolean sent = mod.syncCurrentPlayerSkin();
            reply(ctx.getSource(), sent
                    ? "[Blockstager Fabric] Skin enviada a Blockstager Studio."
                    : "[Blockstager Fabric] No se pudo obtener o enviar la skin actual.", !sent);
            return sent ? 1 : 0;
        }));
        root.then(skinCmd);

        LiteralArgumentBuilder actionsCmd = LiteralArgumentBuilder.literal("actions");
        actionsCmd.then(LiteralArgumentBuilder.literal("status").executes(ctx -> {
            ClientActionPolicy.Settings s = mod.getActionPolicy().getSettings();
            double cooldownSec = s.actionCooldownMs() / 1000.0;
            reply(ctx.getSource(), String.format(Locale.US,
                    "[Blockstager Fabric] Acciones=%s · Cosméticos locales=%s · OP multijugador=%s · Enfriamiento=%.1f s · Tope criaturas=%d",
                    s.allowRemoteActions() ? "ON" : "OFF",
                    s.allowClientCosmeticEffects() ? "ON" : "OFF",
                    s.allowMultiplayerOpActions() ? "ON" : "OFF",
                    cooldownSec,
                    s.maxMobCount()), false);
            return 1;
        }));
        actionsCmd.then(LiteralArgumentBuilder.literal("on").executes(ctx -> {
            mod.setAllowRemoteActions(true);
            reply(ctx.getSource(), "[Blockstager Fabric] Acciones remotas activadas y guardadas.", false);
            return 1;
        }));
        actionsCmd.then(LiteralArgumentBuilder.literal("off").executes(ctx -> {
            mod.setAllowRemoteActions(false);
            reply(ctx.getSource(), "[Blockstager Fabric] Acciones remotas desactivadas y guardadas.", false);
            return 1;
        }));
        root.then(actionsCmd);

        LiteralArgumentBuilder testActionCmd = LiteralArgumentBuilder.literal("test-action");
        RequiredArgumentBuilder typeArg = RequiredArgumentBuilder.argument("tipo", StringArgumentType.word());
        typeArg.executes(ctx -> {
            String tipo = StringArgumentType.getString(ctx, "tipo");
            ClientActionPolicy.ValidationResult res = mod.getActionExecutor().executeLocalTest(tipo, "");
            reply(ctx.getSource(), "[Blockstager Fabric] Prueba " + tipo + " (" + res.outcome().wireValue() + "): " + res.detail(), !res.isOk());
            return res.isOk() ? 1 : 0;
        });
        typeArg.then(RequiredArgumentBuilder.argument("valor", StringArgumentType.greedyString()).executes(ctx -> {
            String tipo = StringArgumentType.getString(ctx, "tipo");
            String valor = StringArgumentType.getString(ctx, "valor");
            ClientActionPolicy.ValidationResult res = mod.getActionExecutor().executeLocalTest(tipo, valor);
            reply(ctx.getSource(), "[Blockstager Fabric] Prueba " + tipo + " (" + res.outcome().wireValue() + "): " + res.detail(), !res.isOk());
            return res.isOk() ? 1 : 0;
        }));
        testActionCmd.then(typeArg);
        root.then(testActionCmd);

        return root;
    }

    private void reply(Object commandSource, String message, boolean error) {
        Object textObj = reflectBridge.createLiteralText(message);
        if (commandSource != null && textObj != null) {
            String methodName = error ? "sendError" : "sendFeedback";
            try {
                // Invocar siempre a través de la interfaz pública FabricClientCommandSource (evita IllegalAccessException en Java 21)
                Class<?> publicSourceIface = Class.forName(
                        "net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource",
                        false,
                        FabricClientCommands.class.getClassLoader()
                );
                if (publicSourceIface.isInstance(commandSource)) {
                    for (Method m : publicSourceIface.getMethods()) {
                        if (m.getName().equals(methodName) && m.getParameterCount() == 1) {
                            m.invoke(commandSource, textObj);
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Fallback a mensaje en chat local
            }
        }
        reflectBridge.sendLocalChatFeedback(message, false);
    }
}
