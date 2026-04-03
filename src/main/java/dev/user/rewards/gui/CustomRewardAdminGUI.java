package dev.user.rewards.gui;

import dev.user.rewards.SimpleRewardsPlugin;
import dev.user.rewards.manager.CustomRewardManager;
import dev.user.rewards.model.CustomReward;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理员自定义奖励管理 GUI
 * 显示所有奖励，支持创建/编辑/删除
 */
public class CustomRewardAdminGUI extends AbstractGUI {

    private static final int SIZE = 54; // 6行

    public CustomRewardAdminGUI(SimpleRewardsPlugin plugin, Player player) {
        super(plugin, player, plugin.getConfigManager().getMessage("custom-reward-gui-admin-title"), SIZE);
    }

    public static void open(SimpleRewardsPlugin plugin, Player player) {
        new CustomRewardAdminGUI(plugin, player).open();
    }

    @Override
    public void initialize() {
        ItemStack border = createBorderItem();
        fillBorder(border);

        // Row 5 分隔线
        for (int col = 0; col < 9; col++) {
            setItem(45 + col, border);
        }

        CustomRewardManager manager = plugin.getCustomRewardManager();
        if (manager == null) {
            setItem(22, createErrorItem("自定义奖励系统未启用"));
            setItem(49, createCloseItem(), (p, e) -> close());
            return;
        }

        List<CustomReward> rewards = manager.getAllRewards();

        // 奖励列表槽位 (Row 1-4, 避开边框)
        int[] slots = {10, 11, 12, 13, 14, 15, 16,
                       19, 20, 21, 22, 23, 24, 25,
                       28, 29, 30, 31, 32, 33, 34,
                       37, 38, 39, 40, 41, 42, 43};

        int i = 0;
        for (CustomReward reward : rewards) {
            if (i >= slots.length) break;
            setItem(slots[i], createRewardItem(reward), (p, e) -> openEditGUI(p, reward));
            i++;
        }

        // 创建新奖励按钮
        setItem(49, createNewItem(), (p, e) -> openCreateGUI(p));

        // 关闭按钮
        setItem(53, createCloseItem(), (p, e) -> close());
    }

    private ItemStack createRewardItem(CustomReward reward) {
        Material material = reward.isEnabled() && !reward.isExpired() ? Material.GOLD_BLOCK : Material.REDSTONE_BLOCK;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text(reward.getDisplayName())
                .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("ID: " + reward.getRewardId())
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (!reward.getDescription().isEmpty()) {
            lore.add(Component.text(reward.getDescription())
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("金币: " + String.format("%.0f", reward.getMoney()))
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("点券: " + reward.getPoints())
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("领取限制: " + (reward.getMaxClaimCount() < 0 ? "无限" : reward.getMaxClaimCount() + "次"))
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (reward.getExpireAt() > 0) {
            lore.add(Component.text("过期时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(reward.getExpireAt()))
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("过期时间: 永不过期")
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text(reward.isEnabled() ? "已启用" : "已禁用")
                .color(reward.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        if (reward.isExpired()) {
            lore.add(Component.text("已过期")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("点击编辑")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);

        return item;
    }

    private ItemStack createNewItem() {
        ItemStack item = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("创建新奖励")
                .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("点击创建一个新的自定义奖励")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCloseItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("关闭")
                .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createErrorItem(String message) {
        ItemStack item = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text(message)
                .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBorderItem() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    private void openEditGUI(Player p, CustomReward reward) {
        close();
        CustomRewardEditGUI.open(plugin, p, reward);
    }

    private void openCreateGUI(Player p) {
        close();
        CustomRewardEditGUI.openCreate(plugin, p);
    }
}
