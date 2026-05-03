package dev.user.rewards.gui;

import dev.user.rewards.SimpleRewardsPlugin;
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
 * 主菜单 GUI - 所有奖励功能的统一入口
 *
 * 布局 (3行 27格):
 * Row 0: 边框
 * Row 1: [每周在线] [自定义奖励] [龙击杀挑战]
 * Row 2: 边框 + [关闭]
 */
public class MainGUI extends AbstractGUI {

    public MainGUI(SimpleRewardsPlugin plugin, Player player) {
        super(plugin, player, "§8§l奖励中心", 27);
    }

    public static void open(SimpleRewardsPlugin plugin, Player player) {
        new MainGUI(plugin, player).open();
    }

    @Override
    public void initialize() {
        actions.clear();

        ItemStack border = createBorderItem();
        fillBorder(border);

        // 每周在线奖励 (slot 11)
        boolean weeklyEnabled = plugin.getWeeklyOnlineManager() != null;
        setItem(11, createWeeklyOnlineItem(weeklyEnabled), (p, e) -> {
            if (weeklyEnabled) {
                close();
                WeeklyOnlineGUI.open(plugin, p);
            }
        });

        // 自定义奖励 (slot 13)
        boolean customEnabled = plugin.getCustomRewardManager() != null;
        setItem(13, createCustomRewardItem(customEnabled), (p, e) -> {
            if (customEnabled) {
                close();
                CustomRewardPlayerGUI.open(plugin, p);
            }
        });

        // 玩法奖励 (slot 15) → 子菜单
        setItem(15, createGameplayRewardItem(), (p, e) -> {
            close();
            GameplayRewardGUI.open(plugin, p);
        });

        // 管理员入口 (slot 22, 仅管理员可见)
        if (player.hasPermission("simplerewards.admin")) {
            setItem(22, createAdminItem(), (p, e) -> {
                if (customEnabled) {
                    close();
                    CustomRewardAdminGUI.open(plugin, p);
                }
            });
        }

        // 关闭按钮 (slot 26)
        setItem(26, createCloseItem(), (p, e) -> close());
    }

    private ItemStack createWeeklyOnlineItem(boolean enabled) {
        Material material = enabled ? Material.CLOCK : Material.COAL;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("每周在线奖励")
                .color(enabled ? NamedTextColor.GOLD : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        if (enabled) {
            int weeklyMinutes = plugin.getWeeklyOnlineManager().getWeeklyMinutes(player.getUniqueId());
            int hours = weeklyMinutes / 60;
            int mins = weeklyMinutes % 60;
            lore.add(Component.text("  本周在线: " + hours + "h " + mins + "m")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("  点击查看里程碑奖励")
                    .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("  未启用")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCustomRewardItem(boolean enabled) {
        Material material = enabled ? Material.GOLD_BLOCK : Material.COAL;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("自定义奖励")
                .color(enabled ? NamedTextColor.GOLD : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        if (enabled) {
            int count = plugin.getCustomRewardManager().getAllRewards().size();
            lore.add(Component.text("  可用奖励: " + count + " 个")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("  点击查看可领取奖励")
                    .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("  未启用")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createGameplayRewardItem() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("玩法奖励")
                .color(NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("  幸运之柱 · 强化末影龙")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("  点击查看")
                .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createAdminItem() {
        ItemStack item = new ItemStack(Material.COMMAND_BLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("奖励管理")
                .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("  点击进入管理界面")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));

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

    private ItemStack createBorderItem() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }
}
