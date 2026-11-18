package dev.dani.consolebridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ConsoleBridge extends JavaPlugin {

    private HttpServer server;
    private String token;
    private List<String> allowedPrefixes;
    private List<String> blockedPrefixes;
    private List<Path> allowedFolders;
    private Path backupFolder;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        token = getConfig().getString("token", "");
        if (token.length() < 24 || token.startsWith("CHANGE_ME")) {
            getLogger().severe("Set a long random token in config.yml. Bridge disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        allowedPrefixes = getConfig().getStringList("allowed-command-prefixes");
        blockedPrefixes = getConfig().getStringList("blocked-command-prefixes");
        Path root = Paths.get(".").toAbsolutePath().normalize();
        allowedFolders = getConfig().getStringList("allowed-folders").stream()
                .map(f -> root.resolve(f).normalize()).toList();
        backupFolder = root.resolve(getConfig().getString("backup-folder", "bridge-backups"));

        try {
            server = HttpServer.create(new InetSocketAddress(getConfig().getInt("port", 8765)), 0);
            server.createContext("/command", this::handleCommand);
            server.createContext("/read", this::handleRead);
            server.createContext("/write", this::handleWrite);
            server.start();
            getLogger().info("ConsoleBridge listening.");
        } catch (IOException e) {
            getLogger().severe("Could not start bridge: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (server != null) server.stop(0);
    }

    private boolean authorized(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("X-Bridge-Token");
        return header != null && java.security.MessageDigest.isEqual(
                header.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private boolean commandAllowed(String cmd) {
        String c = cmd.trim().toLowerCase();
        for (String b : blockedPrefixes) if (c.startsWith(b.toLowerCase())) return false;
        for (String a : allowedPrefixes) if (c.startsWith(a.toLowerCase())) return true;
        return false;
    }

    private void handleCommand(HttpExchange ex) throws IOException {
        if (!authorized(ex)) { reply(ex, 401, "unauthorized"); return; }
        String cmd = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!commandAllowed(cmd)) { reply(ex, 403, "command not allowed"); return; }
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () ->
                done.complete(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)));
        try {
            reply(ex, 200, done.get() ? "ok" : "command returned false");
        } catch (Exception e) {
            reply(ex, 500, "error");
        }
    }

    private Path safePath(String relative) {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        Path p = root.resolve(relative).normalize();
        try {
            if (Files.exists(p)) p = p.toRealPath();
        } catch (IOException e) { return null; }
        for (Path allowed : allowedFolders) if (p.startsWith(allowed)) return p;
        return null;
    }

    private void handleRead(HttpExchange ex) throws IOException {
        if (!authorized(ex)) { reply(ex, 401, "unauthorized"); return; }
        String rel = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        Path p = safePath(rel);
        if (p == null || !Files.isRegularFile(p)) { reply(ex, 403, "path not allowed or missing"); return; }
        reply(ex, 200, Files.readString(p));
    }

    private void handleWrite(HttpExchange ex) throws IOException {
        if (!authorized(ex)) { reply(ex, 401, "unauthorized"); return; }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int nl = body.indexOf('\n');
        if (nl < 0) { reply(ex, 400, "first line must be the path"); return; }
        Path p = safePath(body.substring(0, nl).trim());
        if (p == null) { reply(ex, 403, "path not allowed"); return; }
        if (Files.exists(p)) {
            Files.createDirectories(backupFolder);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Files.copy(p, backupFolder.resolve(stamp + "-" + p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(p, body.substring(nl + 1));
        reply(ex, 200, "written, backup saved");
    }
}
