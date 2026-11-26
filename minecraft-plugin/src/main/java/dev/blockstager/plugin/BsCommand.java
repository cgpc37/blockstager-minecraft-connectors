package dev.blockstager.plugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Comando administrativo {@code /bs} para controlar el puente de Blockstager Studio.
 */
public final class BsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT_SUBCOMMANDS = List.of(
            "trigger", "status", "connect", "skin", "actions", "test-action", "reload"
    );

    private static final List<String> ACTIONS_SUBCOMMANDS = List.of(
            "status", "on", "off"
    );

    private static final List<String> TEST_ACTION_TYPES = List.of(
            "spawn_mob", "give_item", "potion_effect", "world_effect"
    );

    private final BlockstagerPlugin plugin;

    public BsCommand(BlockstagerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blockstager.use")) {
            sender.sendMessage("[Blockstager] No tienes permiso para usar este comando (blockstager.use).");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "trigger" -> handleTrigger(sender, args);
            case "status" -> handleStatus(sender);
            case "connect" -> handleConnect(sender);
            case "skin" -> handleSkin(sender, args);
            case "actions" -> handleActions(sender, args);
            case "test-action" -> handleTestAction(sender, args);
            case "reload" -> handleReload(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleTrigger(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].isBlank()) {
            sender.sendMessage("[Blockstager] Uso: /bs trigger <slug> [clave=valor ...]");
            return;
        }

        WsBridgeClient bridge = plugin.getBridgeClient();
        if (bridge == null || !bridge.isConnected()) {
            sender.sendMessage("[Blockstager] No hay conexión activa con Blockstager Studio.");
            return;
        }

        String slug = args[1].trim();
        Map<String, Object> parsedArgs = parseKeyValueArgs(args, 2);
        boolean sent = bridge.sendTriggerFire(slug, parsedArgs);
        if (sent) {
            sender.sendMessage("[Blockstager] Trigger enviado: '" + slug + "'"
                    + (parsedArgs.isEmpty() ? "" : " con args=" + parsedArgs));
        } else {
            sender.sendMessage("[Blockstager] No se pudo enviar el trigger '" + slug + "'.");
        }
    }

    private void handleStatus(CommandSender sender) {
        WsBridgeClient bridge = plugin.getBridgeClient();
        if (bridge == null) {
            sender.sendMessage("[Blockstager] Puente no inicializado. Revisa config.yml.");
            return;
        }

        long latency = bridge.getLastLatencyMs();
        String latencyText = latency >= 0 ? (latency + " ms") : "N/D";
        sender.sendMessage("[Blockstager] Estado del puente (" + WsBridgeClient.PROTOCOL_VERSION + "):");
        sender.sendMessage(" - Endpoint: " + bridge.getEndpointUrl());
        sender.sendMessage(" - Conectado: " + (bridge.isConnected() ? "sí" : "no"));
        sender.sendMessage(" - Autenticado: " + (bridge.isAuthenticated() ? "sí" : "no"));
        sender.sendMessage(" - Reconexiones: " + bridge.getReconnectCount());
        sender.sendMessage(" - Latencia: " + latencyText);
        MinecraftActionExecutor exec = plugin.getActionExecutor();
        if (exec != null) {
            RemoteActionPolicy.Settings settings = exec.getPolicy().getSettings();
            sender.sendMessage(" - Acciones remotas: " + (settings.allowRemoteActions() ? "activadas" : "desactivadas"));
            sender.sendMessage(" - Enfriamiento: " + formatSeconds(settings.actionCooldownMs()));
        }
        if (bridge.getLastError() != null && !bridge.getLastError().isBlank()) {
            sender.sendMessage(" - Último error: " + bridge.getLastError());
        }
    }

    private void handleConnect(CommandSender sender) {
        WsBridgeClient bridge = plugin.getBridgeClient();
        if (bridge == null) {
            sender.sendMessage("[Blockstager] Puente no inicializado. Ejecuta /bs reload tras configurar config.yml.");
            return;
        }
        bridge.reconnectNow();
        sender.sendMessage("[Blockstager] Reconexión inmediata solicitada hacia " + bridge.getEndpointUrl());
    }

    private void handleSkin(CommandSender sender, String[] args) {
        if (args.length < 2 || !"sync".equalsIgnoreCase(args[1])) {
            sender.sendMessage("[Blockstager] Uso: /bs skin sync [jugador]");
            return;
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage("[Blockstager] Jugador no conectado: " + args[2]);
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage("[Blockstager] Desde consola indica el jugador: /bs skin sync <jugador>");
            return;
        }

        boolean sent = plugin.syncPlayerSkin(target);
        if (sent) {
            sender.sendMessage("[Blockstager] Skin sincronizada para " + target.getName() + ".");
        } else {
            sender.sendMessage("[Blockstager] No se pudo sincronizar la skin de " + target.getName()
                    + " (verifica conexión WS y textura disponible).");
        }
    }

    private void handleActions(CommandSender sender, String[] args) {
        MinecraftActionExecutor exec = plugin.getActionExecutor();
        if (exec == null) {
            sender.sendMessage("[Blockstager] Ejecutor de acciones no inicializado.");
            return;
        }
        String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (mode) {
            case "on" -> {
                exec.setAllowRemoteActions(true);
                sender.sendMessage("[Blockstager] Acciones remotas ACTIVADAS desde Blockstager Studio.");
            }
            case "off" -> {
                exec.setAllowRemoteActions(false);
                sender.sendMessage("[Blockstager] Acciones remotas DESACTIVADAS por seguridad.");
            }
            case "status" -> {
                RemoteActionPolicy.Settings s = exec.getPolicy().getSettings();
                sender.sendMessage("[Blockstager] Estado de acciones remotas:");
                sender.sendMessage(" - Permitidas: " + (s.allowRemoteActions() ? "sí" : "no"));
                sender.sendMessage(" - Máx. criaturas: " + s.maxMobCount() + " (radio máx. " + (int) Math.round(s.maxSpawnRadius()) + " bloques)");
                sender.sendMessage(" - Máx. ítems: " + s.maxItemAmount());
                sender.sendMessage(" - Máx. efecto: " + s.maxEffectDurationSec() + " s (amplificador máx. " + s.maxEffectAmplifier() + ")");
                sender.sendMessage(" - Enfriamiento: " + formatSeconds(s.actionCooldownMs()));
                sender.sendMessage(" - Criaturas permitidas: " + s.allowedEntities().size() + " | bloqueadas: " + s.blockedEntities().size());
                sender.sendMessage(" - Ítems bloqueados: " + s.blockedItems().size()
                        + (s.allowedItems().isEmpty() ? " (sin allowlist restrictiva)" : " | allowlist: " + s.allowedItems().size()));
            }
            default -> sender.sendMessage("[Blockstager] Uso: /bs actions <status|on|off>");
        }
    }

    private void handleTestAction(CommandSender sender, String[] args) {
        MinecraftActionExecutor exec = plugin.getActionExecutor();
        if (exec == null) {
            sender.sendMessage("[Blockstager] Ejecutor de acciones no inicializado.");
            return;
        }
        if (args.length < 2 || args[1].isBlank()) {
            sender.sendMessage("[Blockstager] Uso: /bs test-action <spawn_mob|give_item|potion_effect|world_effect> [valor]");
            return;
        }
        String extra = args.length >= 3 ? args[2].trim() : null;
        exec.executeTestAction(sender, args[1], extra);
    }

    private static String formatSeconds(long millis) {
        if (millis <= 0) {
            return "0 s";
        }
        double sec = millis / 1000.0;
        if (Math.abs(sec - Math.rint(sec)) < 0.01) {
            return ((long) Math.rint(sec)) + " s";
        }
        return String.format(Locale.US, "%.1f s", sec);
    }

    private void handleReload(CommandSender sender) {
        boolean reconnected = plugin.reloadBridgeConfig();
        if (reconnected) {
            sender.sendMessage("[Blockstager] Configuración recargada y conexión WS reiniciada.");
        } else {
            sender.sendMessage("[Blockstager] Configuración recargada sin cambios en la conexión WS.");
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("[Blockstager] Comandos disponibles:");
        sender.sendMessage(" - /bs status");
        sender.sendMessage(" - /bs connect");
        sender.sendMessage(" - /bs trigger <slug> [clave=valor ...]");
        sender.sendMessage(" - /bs skin sync [jugador]");
        sender.sendMessage(" - /bs actions <status|on|off>");
        sender.sendMessage(" - /bs test-action <spawn_mob|give_item|potion_effect|world_effect> [valor]");
        sender.sendMessage(" - /bs reload");
    }

    static Map<String, Object> parseKeyValueArgs(String[] args, int startIndex) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = startIndex; i < args.length; i++) {
            String token = args[i];
            if (token == null || token.isBlank()) {
                continue;
            }
            int eq = token.indexOf('=');
            if (eq <= 0) {
                result.put(token.trim(), Boolean.TRUE);
                continue;
            }
            String key = token.substring(0, eq).trim();
            String rawValue = token.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                result.put(key, parseTypedValue(rawValue));
            }
        }
        return result;
    }

    static Object parseTypedValue(String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        if (raw.matches("-?\\d+")) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                return raw;
            }
        }
        if (raw.matches("-?\\d+\\.\\d+")) {
            try {
                double parsed = Double.parseDouble(raw);
                if (Double.isFinite(parsed)) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                return raw;
            }
        }
        return raw;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("blockstager.use")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterPrefix(ROOT_SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && "skin".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("sync"), args[1]);
        }
        if (args.length == 2 && "actions".equalsIgnoreCase(args[0])) {
            return filterPrefix(ACTIONS_SUBCOMMANDS, args[1]);
        }
        if (args.length == 2 && "test-action".equalsIgnoreCase(args[0])) {
            return filterPrefix(TEST_ACTION_TYPES, args[1]);
        }
        if (args.length == 3 && "skin".equalsIgnoreCase(args[0]) && "sync".equalsIgnoreCase(args[1])) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return filterPrefix(names, args[2]);
        }
        return Collections.emptyList();
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(opt);
            }
        }
        return out;
    }
}
