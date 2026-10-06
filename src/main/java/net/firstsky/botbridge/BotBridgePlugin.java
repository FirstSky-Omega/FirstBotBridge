package net.firstsky.botbridge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class BotBridgePlugin extends JavaPlugin {

    private HikariDataSource pool;
    private HttpClient httpClient;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        httpClient = HttpClient.newHttpClient();

        if (!setupDatabase()) {
            getLogger().severe("Impossible de se connecter à la BDD. Plugin désactivé.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        createTableIfNeeded();

        long intervalMs = getConfig().getLong("poll-interval-ticks", 10) * 50L;
        getServer().getAsyncScheduler().runAtFixedRate(this, task -> pollCommands(), 0, intervalMs, TimeUnit.MILLISECONDS);

        getLogger().info("FirstBotBridge activé (poll toutes les " + intervalMs + "ms).");
    }

    @Override
    public void onDisable() {
        if (pool != null && !pool.isClosed()) pool.close();
    }

    // ── Commande /discordevent ────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("discordevent")) return false;

        if (args.length < 1) {
            sender.sendMessage("[BotBridge] Usage: /discordevent <type>");
            return true;
        }

        String type       = args[0].toUpperCase();
        String webhookUrl = getConfig().getString("events.webhook-url", "");

        if (webhookUrl.isEmpty()) {
            getLogger().warning("[BotBridge] events.webhook-url non configuré dans config.yml.");
            return true;
        }

        String title       = getConfig().getString("events.types." + type + ".title",       "Event sur FirstSky !");
        String description = getConfig().getString("events.types." + type + ".description", "");
        int    color       = getConfig().getInt(   "events.types." + type + ".color",        16766720);
        String imageUrl    = getConfig().getString("events.types." + type + ".image-url",   "");

        // Remplacement des arguments dynamiques {1}, {2}, ... dans titre et description
        for (int i = 1; i < args.length; i++) {
            String placeholder = "{" + i + "}";
            title       = title.replace(placeholder, args[i]);
            description = description.replace(placeholder, args[i]);
        }

        final String finalTitle = title, finalDesc = description, finalImg = imageUrl;
        getServer().getAsyncScheduler().runNow(this, t -> sendWebhook(webhookUrl, finalTitle, finalDesc, color, finalImg));
        getLogger().info("[BotBridge] Notification Discord envoyée : " + type);
        return true;
    }

    private void sendWebhook(String url, String title, String description, int color, String imageUrl) {
        try {
            String imageJson = (imageUrl != null && !imageUrl.isEmpty())
                ? ",\"image\":{\"url\":\"" + escapeJson(imageUrl) + "\"}"
                : "";

            String json = "{\"embeds\":[{\"title\":\"" + escapeJson(title) + "\","
                + "\"description\":\"" + escapeJson(description) + "\","
                + "\"color\":" + color
                + imageJson
                + "}]}";

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                getLogger().warning("[BotBridge] Webhook HTTP " + resp.statusCode() + " : " + resp.body());
            }
        } catch (Exception e) {
            getLogger().warning("[BotBridge] Erreur envoi webhook : " + e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    // ── Bridge console (sens Bot → Minecraft) ────────────────────────────────

    private boolean setupDatabase() {
        try {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl("jdbc:mysql://"
                    + getConfig().getString("database.host") + ":"
                    + getConfig().getInt("database.port")
                    + "/" + getConfig().getString("database.name")
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            cfg.setUsername(getConfig().getString("database.user"));
            cfg.setPassword(getConfig().getString("database.password"));
            cfg.setMaximumPoolSize(3);
            cfg.setConnectionTimeout(10_000);
            cfg.setPoolName("BotBridge");
            pool = new HikariDataSource(cfg);
            return true;
        } catch (Exception e) {
            getLogger().severe("Erreur DB: " + e.getMessage());
            return false;
        }
    }

    private void createTableIfNeeded() {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS bot_console_queue (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  command VARCHAR(512) NOT NULL," +
                "  status ENUM('pending','done','error') NOT NULL DEFAULT 'pending'," +
                "  created_at BIGINT NOT NULL" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            ps.execute();
        } catch (Exception e) {
            getLogger().warning("Impossible de créer la table: " + e.getMessage());
        }
    }

    private void pollCommands() {
        try (Connection c = pool.getConnection()) {
            List<Long>   ids      = new ArrayList<>();
            List<String> commands = new ArrayList<>();

            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT id, command FROM bot_console_queue WHERE status='pending' ORDER BY id LIMIT 10")) {
                ResultSet rs = sel.executeQuery();
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                    commands.add(rs.getString("command"));
                }
            }

            for (int i = 0; i < ids.size(); i++) {
                long   id  = ids.get(i);
                String cmd = commands.get(i);

                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE bot_console_queue SET status='done' WHERE id=? AND status='pending'")) {
                    upd.setLong(1, id);
                    if (upd.executeUpdate() == 0) continue;
                }

                getServer().getGlobalRegionScheduler().run(this, t ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
                );
                getLogger().info("[BotBridge] " + cmd);
            }
        } catch (Exception e) {
            getLogger().warning("[BotBridge] Erreur poll: " + e.getMessage());
        }
    }
}
