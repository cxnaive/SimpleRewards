package dev.user.rewards.listener;

import dev.user.rewards.gui.AbstractGUI;
import dev.user.rewards.gui.AbstractGUI;
import dev.user.rewards.gui.GUIManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

public class GUIListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        AbstractGUI gui = GUIManager.getOpenGUI(player.getUniqueId());
        if (gui == null) return;
        if (event.getInventory() != gui.getInventory()) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= gui.getInventory().getSize()) return;

        AbstractGUI.GUIAction action = gui.getAction(slot);
        if (action != null) {
            action.execute(player, event);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        // 只在关闭的是 SimpleRewards GUI 时注销，避免原版箱子/其他插件 GUI 误清除
        AbstractGUI gui = GUIManager.getOpenGUI(player.getUniqueId());
        if (gui == null) return;
        if (event.getInventory() != gui.getInventory()) return;
        GUIManager.unregisterGUI(player.getUniqueId());
    }
}
