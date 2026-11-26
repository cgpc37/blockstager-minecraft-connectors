package dev.blockstager.plugin;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;
import java.util.Objects;

/**
 * Plugin oficial de Paper/Spigot (Java 21) para Blockstager Studio.
 */
public final class BlockstagerPlugin extends JavaPlugin {

    private static final String DEFAULT_WS_URL = "ws://127.0.0.1:28490/ws";

    private volatile WsBridgeClient bridgeClient;
    private EventForwarder eventForwarder;
    private MinecraftActionExecutor actionExecutor;
    private String currentWsUrl = "";
    private String currentToken = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        FileConfiguration cfg = getConfig();
        String targetPlayer = cfg.getString("targetPlayer", "");
        boolean syncSkinOnJoin = cfg.getBoolean("syncSkinOnJoin", true);

        this.eventForwarder = new EventForwarder(this, targetPlayer, syncSkinOnJoin);
        this.actionExecutor = new MinecraftActionExecutor(this, cfg);
        getServer().getPluginManager().registerEvents(this.eventForwarder, this);

        PluginCommand bsCmd = getCommand("bs");
        if (bsCmd != null) {
            BsCommand executor = new BsCommand(this);
            bsCmd.setExecutor(executor);
            bsCmd.setTabCompleter(executor);
        } else {
            getLogger().warning("[Blockstager] No se encontró el comando /bs en plugin.yml.");
        }

        initializeBridgeFromConfig(cfg);
    }

    @Override
    public void onDisable() {
        WsBridgeClient client = this.bridgeClient;
        this.bridgeClient = null;
        if (client != null) {
            client.shutdown();
        }
    }

    public WsBridgeClient getBridgeClient() {
        return bridgeClient;
    }

    public MinecraftActionExecutor getActionExecutor() {
        return actionExecutor;
    }

    /**
     * Recarga {@code config.yml} y reinicia el puente WebSocket si cambió {@code wsUrl} o {@code token}.
     */
    public synchronized boolean reloadBridgeConfig() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        String targetPlayer = cfg.getString("targetPlayer", "");
        boolean syncSkinOnJoin = cfg.getBoolean("syncSkinOnJoin", true);
        if (eventForwarder != null) {
            eventForwarder.updateSettings(targetPlayer, syncSkinOnJoin);
        }
        if (actionExecutor != null) {
            actionExecutor.reloadFromConfig(cfg);
        }

        String nextWsUrl = cfg.getString("wsUrl", DEFAULT_WS_URL).trim();
        String nextToken = cfg.getString("token", "").trim();

        boolean endpointChanged = !Objects.equals(currentWsUrl, nextWsUrl)
                || !Objects.equals(currentToken, nextToken)
                || bridgeClient == null;

        if (!endpointChanged) {
            return false;
        }

        WsBridgeClient old = this.bridgeClient;
        this.bridgeClient = null;
        if (old != null) {
            old.shutdown();
        }

        initializeBridgeFromConfig(cfg);
        return true;
    }

    /**
     * Envía la textura actual del jugador usando exclusivamente la API pública {@link PlayerTextures}.
     */
    public boolean syncPlayerSkin(Player player) {
        WsBridgeClient client = this.bridgeClient;
        if (client == null || !client.isConnected() || player == null) {
            return false;
        }
        PlayerTextures textures = player.getPlayerProfile().getTextures();
        URL skinUrl = textures.getSkin();
        if (skinUrl == null) {
            return false;
        }
        String variant = textures.getSkinModel() == PlayerTextures.SkinModel.SLIM ? "slim" : "classic";
        return client.sendSkinSet(player.getName(), skinUrl.toString(), variant);
    }

    private void initializeBridgeFromConfig(FileConfiguration cfg) {
        String wsUrl = cfg.getString("wsUrl", DEFAULT_WS_URL).trim();
        if (wsUrl.isEmpty()) {
            wsUrl = DEFAULT_WS_URL;
        }
        String token = cfg.getString("token", "").trim();

        this.currentWsUrl = wsUrl;
        this.currentToken = token;

        String clientName = "Paper-" + getServer().getMinecraftVersion();
        try {
            WsBridgeClient client = new WsBridgeClient(wsUrl, token, clientName, getLogger());
            if (this.actionExecutor != null) {
                client.setMinecraftActionHandler(this.actionExecutor::handleIncomingAction);
            }
            this.bridgeClient = client;
            if (token.isEmpty()) {
                getLogger().warning("[Blockstager] 'token' está vacío en config.yml. "
                        + "Copia el token desde Blockstager Studio y ejecuta /bs reload.");
            } else {
                client.connect();
            }
        } catch (IllegalArgumentException ex) {
            getLogger().severe("[Blockstager] URL WebSocket inválida en config.yml (" + wsUrl + "): " + ex.getMessage());
        }
    }
}
