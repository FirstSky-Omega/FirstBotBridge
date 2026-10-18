package net.firstsky.botbridge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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

        createTablesIfNeeded();

        long intervalMs = getConfig().getLong("poll-interval-ticks", 10) * 50L;
        getServer().getAsyncScheduler().runAtFixedRate(this, task -> pollCommands(), 0, intervalMs, TimeUnit.MILLISECONDS);

        getLogger().info("FirstBotBridge activé (poll toutes les " + intervalMs + "ms).");
    }

    @Override
    public void onDisable() {
        if (pool != null && !pool.isClosed()) pool.close();
    }

    // ── Commandes ─────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // /discordevent
        if (command.getName().equalsIgnoreCase("discordevent")) {
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

        // /soutiens
        if (command.getName().equalsIgnoreCase("soutiens")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCette commande est réservée aux joueurs.");
                return true;
            }

            String rewardCommand = getConfig().getString("soutien.reward-command", "");
            int    cooldownDays  = getConfig().getInt("soutien.cooldown-days", 30);

            if (rewardCommand.isEmpty()) {
                player.sendMessage("§cLes récompenses Soutien ne sont pas encore configurées.");
                return true;
            }

            String uuid = player.getUniqueId().toString();

            getServer().getAsyncScheduler().runNow(this, t -> {
                try (Connection c = pool.getConnection()) {

                    // 1. Vérifier l'éligibilité (rôle Soutien Discord)
                    boolean eligible = false;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT eligible FROM soutien_eligible WHERE mc_uuid = ?")) {
                        ps.setString(1, uuid);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) eligible = rs.getInt("eligible") == 1;
                    }

                    if (!eligible) {
                        player.sendMessage("§c❌ Tu n'as pas le rôle §fSoutien§c Discord.");
                        player.sendMessage("§7Ajoute §fdiscord.gg/firstsky§7 dans ton statut Discord pour l'obtenir automatiquement !");
                        return;
                    }

                    // 2. Vérifier le cooldown
                    long lastClaimed = 0;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT last_claimed FROM soutien_cooldowns WHERE mc_uuid = ?")) {
                        ps.setString(1, uuid);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) lastClaimed = rs.getLong("last_claimed");
                    }

                    long now         = System.currentTimeMillis();
                    long cooldownMs  = (long) cooldownDays * 24L * 60L * 60L * 1000L;
                    long nextClaim   = lastClaimed + cooldownMs;

                    if (now < nextClaim) {
                        long remaining = nextClaim - now;
                        long days      = remaining / (24L * 60 * 60 * 1000);
                        long hours     = (remaining % (24L * 60 * 60 * 1000)) / (60 * 60 * 1000);
                        player.sendMessage("§c⏳ Tu as déjà réclamé ta récompense ! Reviens dans §f"
                                + days + "j " + hours + "h§c.");
                        return;
                    }

                    // 3. Donner la récompense
                    String cmd = rewardCommand.replace("{player}", player.getName());
                    getServer().getGlobalRegionScheduler().run(this, task ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
                    );

                    // 4. Enregistrer le cooldown
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO soutien_cooldowns (mc_uuid, last_claimed) VALUES (?, ?) " +
                            "ON DUPLICATE KEY UPDATE last_claimed = VALUES(last_claimed)")) {
                        ps.setString(1, uuid);
                        ps.setLong(2, now);
                        ps.executeUpdate();
                    }

                    player.sendMessage("§a✅ Récompense Soutien reçue ! Merci de soutenir FirstSky §c❤");

                } catch (Exception e) {
                    getLogger().warning("[BotBridge] Erreur /soutiens : " + e.getMessage());
                    player.sendMessage("§cErreur interne, réessaie dans quelques secondes.");
                }
            });
            return true;
        }

        // /nitroboost
        if (command.getName().equalsIgnoreCase("nitroboost")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cCette commande est réservée aux joueurs.");
                return true;
            }

            String rewardCommand = getConfig().getString("nitroboost.reward-command", "");
            int    cooldownDays  = getConfig().getInt("nitroboost.cooldown-days", 30);

            if (rewardCommand.isEmpty()) {
                player.sendMessage("§cLes récompenses Nitro Boost ne sont pas encore configurées.");
                return true;
            }

            String uuid = player.getUniqueId().toString();

            getServer().getAsyncScheduler().runNow(this, t -> {
                try (Connection c = pool.getConnection()) {

                    // 1. Vérifier l'éligibilité (Nitro Booster Discord)
                    boolean eligible = false;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT eligible FROM boost_eligible WHERE mc_uuid = ?")) {
                        ps.setString(1, uuid);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) eligible = rs.getInt("eligible") == 1;
                    }

                    if (!eligible) {
                        player.sendMessage("§c❌ Tu ne boostes pas le serveur Discord FirstSky.");
                        player.sendMessage("§7Boostez le serveur Discord pour débloquer cette récompense !");
                        return;
                    }

                    // 2. Vérifier le cooldown
                    long lastClaimed = 0;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT last_claimed FROM boost_cooldowns WHERE mc_uuid = ?")) {
                        ps.setString(1, uuid);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) lastClaimed = rs.getLong("last_claimed");
                    }

                    long now        = System.currentTimeMillis();
                    long cooldownMs = (long) cooldownDays * 24L * 60L * 60L * 1000L;
                    long nextClaim  = lastClaimed + cooldownMs;

                    if (now < nextClaim) {
                        long remaining = nextClaim - now;
                        long days      = remaining / (24L * 60 * 60 * 1000);
                        long hours     = (remaining % (24L * 60 * 60 * 1000)) / (60 * 60 * 1000);
                        player.sendMessage("§c⏳ Tu as déjà réclamé ta récompense ! Reviens dans §f"
                                + days + "j " + hours + "h§c.");
                        return;
                    }

                    // 3. Donner la récompense
                    String cmd = rewardCommand.replace("{player}", player.getName());
                    getServer().getGlobalRegionScheduler().run(this, task ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
                    );

                    // 4. Enregistrer le cooldown
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO boost_cooldowns (mc_uuid, last_claimed) VALUES (?, ?) " +
                            "ON DUPLICATE KEY UPDATE last_claimed = VALUES(last_claimed)")) {
                        ps.setString(1, uuid);
                        ps.setLong(2, now);
                        ps.executeUpdate();
                    }

                    player.sendMessage("§a✅ Récompense Nitro Boost reçue ! Merci de booster FirstSky §c💜");

                } catch (Exception e) {
                    getLogger().warning("[BotBridge] Erreur /nitroboost : " + e.getMessage());
                    player.sendMessage("§cErreur interne, réessaie dans quelques secondes.");
                }
            });
            return true;
        }

        return false;
    }

    // ── Webhook Discord ───────────────────────────────────────────────────────

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

    // ── Database ──────────────────────────────────────────────────────────────

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

    private void createTablesIfNeeded() {
        String[] tables = {
            "CREATE TABLE IF NOT EXISTS bot_console_queue (" +
            "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
            "  command VARCHAR(512) NOT NULL," +
            "  status ENUM('pending','done','error') NOT NULL DEFAULT 'pending'," +
            "  created_at BIGINT NOT NULL" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            "CREATE TABLE IF NOT EXISTS soutien_eligible (" +
            "  mc_uuid VARCHAR(36) PRIMARY KEY," +
            "  eligible TINYINT(1) NOT NULL DEFAULT 1," +
            "  updated_at BIGINT NOT NULL" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            "CREATE TABLE IF NOT EXISTS soutien_cooldowns (" +
            "  mc_uuid VARCHAR(36) PRIMARY KEY," +
            "  last_claimed BIGINT NOT NULL" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            "CREATE TABLE IF NOT EXISTS boost_eligible (" +
            "  mc_uuid VARCHAR(36) PRIMARY KEY," +
            "  eligible TINYINT(1) NOT NULL DEFAULT 1," +
            "  updated_at BIGINT NOT NULL" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

            "CREATE TABLE IF NOT EXISTS boost_cooldowns (" +
            "  mc_uuid VARCHAR(36) PRIMARY KEY," +
            "  last_claimed BIGINT NOT NULL" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
        };

        try (Connection c = pool.getConnection()) {
            for (String sql : tables) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.execute();
                }
            }
        } catch (Exception e) {
            getLogger().warning("Impossible de créer les tables: " + e.getMessage());
        }
    }

    // ── Bridge console (Bot → Minecraft) ─────────────────────────────────────

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
