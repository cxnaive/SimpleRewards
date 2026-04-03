package dev.user.rewards.gui;

import dev.user.rewards.SimpleRewardsPlugin;
import dev.user.rewards.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

public abstract class AbstractGUI implements InventoryHolder {

    protected final SimpleRewardsPlugin plugin;
    protected final Player player;
    protected final Component title;
    protected final int size;
    protected Inventory inventory;
    protected final Map<Integer, GUIAction> actions;

    @FunctionalInterface
    public interface GUIAction {
        void execute(Player player, InventoryClickEvent event);
    }

    public AbstractGUI(SimpleRewardsPlugin plugin, Player player, String title, int size) {
        this.plugin = plugin;
        this.player = player;
        this.title = MessageUtil.toComponent(title);
        this.size = size;
        this.actions = new HashMap<>();
    }

    public abstract void initialize();

    public void open() {
        inventory = plugin.getServer().createInventory(this, size, title);
        initialize();
        player.openInventory(inventory);
        GUIManager.registerGUI(player.getUniqueId(), this);
    }

    protected void setItem(int slot, ItemStack item) {
        inventory.setItem(slot, item);
    }

    protected void setItem(int slot, ItemStack item, GUIAction action) {
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    protected void fillBorder(ItemStack item) {
        int rows = size / 9;
        for (int i = 0; i < size; i++) {
            int row = i / 9;
            int col = i % 9;
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                if (inventory.getItem(i) == null) {
                    inventory.setItem(i, item);
                }
            }
        }
    }

    protected void fillEmpty(ItemStack item) {
        for (int i = 0; i < size; i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, item);
            }
        }
    }

    public GUIAction getAction(int slot) {
        return actions.get(slot);
    }

    public void close() {
        player.closeInventory();
        GUIManager.unregisterGUI(player.getUniqueId());
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player getPlayer() {
        return player;
    }

    public SimpleRewardsPlugin getPlugin() {
        return plugin;
    }
}
