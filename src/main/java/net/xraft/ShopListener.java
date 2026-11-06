package net.xraft;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;

public class ShopListener implements Listener {

    public static class ShopHolder implements InventoryHolder {
        private Inventory inv;
        @Override
        public @NotNull Inventory getInventory() { return inv; }
        public void set(Inventory i) { this.inv = i; }
    }

    private final Xraft plugin;

    public ShopListener(Xraft plugin) {
        this.plugin = plugin;
    }

    public static void open(Xraft plugin, Player p) {
        ShopHolder holder = new ShopHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Xraft.c("<gradient:#00c6ff:#0072ff><b>Xraft Shop</b></gradient>"));
        holder.set(inv);
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("shop");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m == null || !m.isItem()) continue;
                ItemStack it = new ItemStack(m);
                ItemMeta meta = it.getItemMeta();
                meta.lore(List.of(
                        Xraft.c("<!italic><gray>Price: <green>$" + Xraft.fmt(sec.getDouble(key)) + "</green> each"),
                        Xraft.c("<!italic><yellow>Click: x1 | Shift-click: x64")));
                it.setItemMeta(meta);
                if (inv.firstEmpty() == -1) break;
                inv.addItem(it);
            }
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof ShopHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof ShopHolder)) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        double price = plugin.getConfig().getDouble("shop." + clicked.getType().name(), -1);
        if (price < 0) return;
        int amount = e.isShiftClick() ? 64 : 1;
        double cost = price * amount;

        if (!plugin.take(p.getUniqueId(), cost)) {
            plugin.msg(p, "<red>You need <white>$" + Xraft.fmt(cost) + "</white> to buy this.");
            return;
        }
        HashMap<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(clicked.getType(), amount));
        left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
        plugin.msg(p, "<gray>Bought <white>" + amount + "x " + clicked.getType().name().toLowerCase()
                + "</white> for <green>$" + Xraft.fmt(cost) + "</green>");
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof ShopHolder) e.setCancelled(true);
    }
}
