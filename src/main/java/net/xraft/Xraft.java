package net.xraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class Xraft extends JavaPlugin implements Listener {

    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        Commands cmds = new Commands(this);
        for (String name : getDescription().getCommands().keySet()) {
            if (getCommand(name) != null) {
                getCommand(name).setExecutor(cmds);
                getCommand(name).setTabCompleter(cmds);
            }
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new ShopListener(this), this);

        Bukkit.getScheduler().runTaskTimer(this, this::updateTab, 20L, 40L);
        getLogger().info("Xraft enabled.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ---------- helpers ----------
    public static Component c(String s) {
        return MiniMessage.miniMessage().deserialize(s);
    }

    public void msg(CommandSender s, String m) {
        s.sendMessage(c(getConfig().getString("prefix", "") + m));
    }

    public static String fmt(double v) {
        if (v >= 1_000_000_000) return String.format("%.2fB", v / 1_000_000_000);
        if (v >= 1_000_000) return String.format("%.2fM", v / 1_000_000);
        if (v >= 1_000) return String.format("%.2fK", v / 1_000);
        return String.format("%.2f", v);
    }

    public YamlConfiguration data() {
        return data;
    }

    public void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    // ---------- economy ----------
    public double bal(UUID id) {
        return data.getDouble("bal." + id, getConfig().getDouble("start-balance", 0));
    }

    public void setBal(UUID id, double v) {
        data.set("bal." + id, Math.max(0, v));
        saveData();
    }

    public void add(UUID id, double v) {
        setBal(id, bal(id) + v);
    }

    public boolean take(UUID id, double v) {
        if (bal(id) < v) return false;
        setBal(id, bal(id) - v);
        return true;
    }

    // ---------- tab ----------
    private void updateTab() {
        if (!getConfig().getBoolean("tab.enabled", true)) return;
        String h = getConfig().getString("tab.header", "");
        String f = getConfig().getString("tab.footer", "");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendPlayerListHeaderAndFooter(c(ph(h, p)), c(ph(f, p)));
        }
    }

    private String ph(String s, Player p) {
        return s.replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%player%", p.getName())
                .replace("%balance%", fmt(bal(p.getUniqueId())));
    }

    // ---------- events ----------
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPlayedBefore() && data.isSet("spawn.world")) {
            var loc = Commands.getSpawn(this);
            if (loc != null) p.teleportAsync(loc);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (e.isBedSpawn() || e.isAnchorSpawn()) return;
        var loc = Commands.getSpawn(this);
        if (loc != null) e.setRespawnLocation(loc);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        String key = "bounty." + victim.getUniqueId();
        double amount = data.getDouble(key, 0);
        if (amount <= 0 || killer == null || killer.equals(victim)) return;
        add(killer.getUniqueId(), amount);
        data.set(key, null);
        saveData();
        Bukkit.broadcast(c(getConfig().getString("prefix", "") + "<yellow>" + killer.getName()
                + "</yellow> <gray>claimed the bounty on</gray> <yellow>" + victim.getName()
                + "</yellow> <gray>worth</gray> <green>$" + fmt(amount) + "</green>"));
    }

    public OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        return off;
    }
}
