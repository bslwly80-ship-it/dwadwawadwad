package net.xraft;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class Commands implements CommandExecutor, TabCompleter {

    private final Xraft plugin;
    private final Map<UUID, Long> rtpCooldown = new HashMap<>();

    public Commands(Xraft plugin) {
        this.plugin = plugin;
    }

    public static Location getSpawn(Xraft plugin) {
        var d = plugin.data();
        if (!d.isSet("spawn.world")) return null;
        World w = Bukkit.getWorld(d.getString("spawn.world"));
        if (w == null) return null;
        return new Location(w, d.getDouble("spawn.x"), d.getDouble("spawn.y"), d.getDouble("spawn.z"),
                (float) d.getDouble("spawn.yaw"), (float) d.getDouble("spawn.pitch"));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        switch (cmd.getName().toLowerCase()) {
            case "balance" -> balance(s, a);
            case "pay" -> pay(s, a);
            case "eco" -> eco(s, a);
            case "spawn" -> {
                if (!(s instanceof Player p)) return true;
                Location loc = getSpawn(plugin);
                if (loc == null) {
                    plugin.msg(s, "<red>Spawn is not set.");
                } else {
                    p.teleportAsync(loc);
                    plugin.msg(s, "<green>Teleported to spawn.");
                }
            }
            case "setspawn" -> {
                if (!(s instanceof Player p)) return true;
                Location l = p.getLocation();
                var d = plugin.data();
                d.set("spawn.world", l.getWorld().getName());
                d.set("spawn.x", l.getX());
                d.set("spawn.y", l.getY());
                d.set("spawn.z", l.getZ());
                d.set("spawn.yaw", l.getYaw());
                d.set("spawn.pitch", l.getPitch());
                plugin.saveData();
                plugin.msg(s, "<green>Spawn set.");
            }
            case "rtp" -> {
                if (!(s instanceof Player p)) return true;
                long cd = plugin.getConfig().getLong("rtp.cooldown-seconds", 30) * 1000;
                long last = rtpCooldown.getOrDefault(p.getUniqueId(), 0L);
                long left = last + cd - System.currentTimeMillis();
                if (left > 0 && !p.hasPermission("xraft.admin")) {
                    plugin.msg(s, "<red>Wait " + (left / 1000 + 1) + "s before using /rtp again.");
                    return true;
                }
                if (p.getWorld().getEnvironment() != World.Environment.NORMAL) {
                    plugin.msg(s, "<red>/rtp only works in the overworld.");
                    return true;
                }
                rtpCooldown.put(p.getUniqueId(), System.currentTimeMillis());
                plugin.msg(s, "<gray>Searching for a safe spot...");
                rtp(p, 12);
            }
            case "sell" -> sell(s, a);
            case "shop" -> {
                if (s instanceof Player p) ShopListener.open(plugin, p);
            }
            case "bounty" -> bounty(s, a);
            case "xraft" -> {
                plugin.reloadConfig();
                plugin.msg(s, "<green>Xraft reloaded.");
            }
        }
        return true;
    }

    // ---------- economy ----------
    private void balance(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player p)) return;
            plugin.msg(s, "<gray>Balance: <green>$" + Xraft.fmt(plugin.bal(p.getUniqueId())));
            return;
        }
        OfflinePlayer t = plugin.findPlayer(a[0]);
        if (t == null) {
            plugin.msg(s, "<red>Player not found.");
            return;
        }
        plugin.msg(s, "<white>" + a[0] + "<gray>'s balance: <green>$" + Xraft.fmt(plugin.bal(t.getUniqueId())));
    }

    private Double parse(CommandSender s, String str) {
        try {
            double v = Double.parseDouble(str);
            if (v <= 0 || Double.isNaN(v) || Double.isInfinite(v)) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            plugin.msg(s, "<red>Invalid amount.");
            return null;
        }
    }

    private void pay(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) return;
        if (a.length < 2) {
            plugin.msg(s, "<red>Usage: /pay <player> <amount>");
            return;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || t.equals(p)) {
            plugin.msg(s, "<red>Player not found.");
            return;
        }
        Double amt = parse(s, a[1]);
        if (amt == null) return;
        if (!plugin.take(p.getUniqueId(), amt)) {
            plugin.msg(s, "<red>Not enough money.");
            return;
        }
        plugin.add(t.getUniqueId(), amt);
        plugin.msg(p, "<gray>You paid <white>" + t.getName() + " <green>$" + Xraft.fmt(amt));
        plugin.msg(t, "<white>" + p.getName() + " <gray>paid you <green>$" + Xraft.fmt(amt));
    }

    private void eco(CommandSender s, String[] a) {
        if (a.length < 3) {
            plugin.msg(s, "<red>Usage: /eco <give|take|set> <player> <amount>");
            return;
        }
        OfflinePlayer t = plugin.findPlayer(a[1]);
        if (t == null) {
            plugin.msg(s, "<red>Player not found.");
            return;
        }
        Double amt = parse(s, a[2]);
        if (amt == null) return;
        UUID id = t.getUniqueId();
        switch (a[0].toLowerCase()) {
            case "give" -> plugin.add(id, amt);
            case "take" -> plugin.setBal(id, plugin.bal(id) - amt);
            case "set" -> plugin.setBal(id, amt);
            default -> {
                plugin.msg(s, "<red>Usage: /eco <give|take|set> <player> <amount>");
                return;
            }
        }
        plugin.msg(s, "<green>Done. New balance: $" + Xraft.fmt(plugin.bal(id)));
    }

    // ---------- rtp ----------
    private void rtp(Player p, int tries) {
        if (!p.isOnline()) return;
        if (tries <= 0) {
            plugin.msg(p, "<red>Could not find a safe location, try again.");
            rtpCooldown.remove(p.getUniqueId());
            return;
        }
        World w = p.getWorld();
        int max = plugin.getConfig().getInt("rtp.radius", 5000);
        int min = Math.min(plugin.getConfig().getInt("rtp.min-radius", 200), max - 1);
        var r = ThreadLocalRandom.current();
        double ang = r.nextDouble() * Math.PI * 2;
        double dist = min + r.nextDouble() * (max - min);
        int x = (int) (Math.cos(ang) * dist);
        int z = (int) (Math.sin(ang) * dist);

        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin, () -> {
            int y = w.getHighestBlockYAt(x, z);
            Material ground = w.getBlockAt(x, y, z).getType();
            if (ground == Material.WATER || ground == Material.LAVA || ground == Material.AIR
                    || ground == Material.CACTUS || ground == Material.MAGMA_BLOCK
                    || ground == Material.FIRE || ground == Material.POWDER_SNOW) {
                rtp(p, tries - 1);
                return;
            }
            p.teleportAsync(new Location(w, x + 0.5, y + 1, z + 0.5));
            plugin.msg(p, "<green>Teleported to <white>" + x + ", " + (y + 1) + ", " + z);
        }));
    }

    // ---------- sell ----------
    private void sell(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) return;
        ConfigurationSection prices = plugin.getConfig().getConfigurationSection("sell-prices");
        if (prices == null || a.length == 0) {
            plugin.msg(s, "<red>Usage: /sell <hand|all>");
            return;
        }
        double total = 0;
        int count = 0;
        if (a[0].equalsIgnoreCase("hand")) {
            ItemStack it = p.getInventory().getItemInMainHand();
            if (it.getType() != Material.AIR && prices.isSet(it.getType().name())) {
                total = prices.getDouble(it.getType().name()) * it.getAmount();
                count = it.getAmount();
                p.getInventory().setItemInMainHand(null);
            }
        } else if (a[0].equalsIgnoreCase("all")) {
            ItemStack[] contents = p.getInventory().getStorageContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack it = contents[i];
                if (it == null || !prices.isSet(it.getType().name())) continue;
                total += prices.getDouble(it.getType().name()) * it.getAmount();
                count += it.getAmount();
                contents[i] = null;
            }
            p.getInventory().setStorageContents(contents);
        } else {
            plugin.msg(s, "<red>Usage: /sell <hand|all>");
            return;
        }
        if (count == 0) {
            plugin.msg(s, "<red>Nothing sellable.");
            return;
        }
        plugin.add(p.getUniqueId(), total);
        plugin.msg(s, "<gray>Sold <white>" + count + " <gray>items for <green>$" + Xraft.fmt(total));
    }

    // ---------- bounty ----------
    private void bounty(CommandSender s, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("list")) {
            var sec = plugin.data().getConfigurationSection("bounty");
            if (sec == null || sec.getKeys(false).isEmpty()) {
                plugin.msg(s, "<gray>No active bounties.");
                return;
            }
            plugin.msg(s, "<yellow>Active bounties:");
            for (String k : sec.getKeys(false)) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(k));
                String name = op.getName() == null ? k : op.getName();
                s.sendMessage(Xraft.c("<gray> - <white>" + name + " <gray>: <green>$" + Xraft.fmt(sec.getDouble(k))));
            }
            return;
        }
        if (!(s instanceof Player p) || a.length < 3 || !a[0].equalsIgnoreCase("set")) {
            plugin.msg(s, "<red>Usage: /bounty set <player> <amount> | /bounty list");
            return;
        }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null || t.equals(p)) {
            plugin.msg(s, "<red>Player not found.");
            return;
        }
        Double amt = parse(s, a[2]);
        if (amt == null) return;
        if (!plugin.take(p.getUniqueId(), amt)) {
            plugin.msg(s, "<red>Not enough money.");
            return;
        }
        String key = "bounty." + t.getUniqueId();
        plugin.data().set(key, plugin.data().getDouble(key, 0) + amt);
        plugin.saveData();
        Bukkit.broadcast(Xraft.c(plugin.getConfig().getString("prefix", "") + "<white>" + p.getName()
                + " <gray>placed a bounty of <green>$" + Xraft.fmt(amt) + " <gray>on <yellow>" + t.getName()));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase();
        if (name.equals("sell") && a.length == 1) out.addAll(List.of("hand", "all"));
        else if (name.equals("bounty") && a.length == 1) out.addAll(List.of("set", "list"));
        else if (name.equals("eco") && a.length == 1) out.addAll(List.of("give", "take", "set"));
        else if ((name.equals("pay") && a.length == 1) || (name.equals("balance") && a.length == 1)
                || (name.equals("bounty") && a.length == 2) || (name.equals("eco") && a.length == 2)) {
            Bukkit.getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }
}
