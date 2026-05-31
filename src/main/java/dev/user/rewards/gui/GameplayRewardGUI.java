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
 * 玩法奖励子菜单
 *
 * 布局 (3行 27格):
 * Row 0: 边框
 * Row 1: [幸运之柱奖励]  [强化末影龙]
 * Row 2: 边框 + [返回]
 */
public class GameplayRewardGUI extends AbstractGUI {

    public GameplayRewardGUI(SimpleRewardsPlugin plugin, Player player) {
        super(plugin, player, "§8§l玩法奖励", 27);
    }

    public static void open(SimpleRewardsPlugin plugin, Player player) {
        new GameplayRewardGUI(plugin, player).open();
    }

    @Override
    public void initialize() {
        actions.clear();

        ItemStack border = createBorderItem();
        fillBorder(border);

        // 幸运之柱奖励 (slot 12)
        boolean gameRewardEnabled = plugin.getGameRewardManager() != null;
        setItem(12, createPillarItem(gameRewardEnabled), (p, e) -> {
            if (gameRewardEnabled) {
                close();
                PillarRewardGUI.open(plugin, p);
            }
        });

        // 强化末影龙 (slot 14)
        boolean dragonEnabled = plugin.getDragonKillManager() != null
                && plugin.getConfig().getBoolean("dragon-kill.enabled", true);
        setItem(14, createDragonKillItem(dragonEnabled));

        // 返回按钮 (slot 22)
        setItem(22, createBackItem(), (p, e) -> {
            close();
            MainGUI.open(plugin, p);
        });

        // 关闭按钮 (slot 26)
        setItem(26, createCloseItem(), (p, e) -> close());
    }

    private ItemStack createPillarItem(boolean enabled) {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("小游戏")
                .color(enabled ? NamedTextColor.GOLD : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        if (enabled) {
            lore.add(Component.text("  参与服务器小游戏")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("  获得击杀、胜负奖励！")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("  奖励都将只在小游戏区发放")
                    .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("  点击前往小游戏大厅")
                    .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("  未启用")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createDragonKillItem(boolean enabled) {
        Material material = enabled ? Material.DRAGON_HEAD : Material.COAL;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        int reward = plugin.getConfig().getInt("dragon-kill.reward", 200);
        int limit = plugin.getConfig().getInt("dragon-kill.daily-limit", 3);

        meta.displayName(Component.text("强化末影龙")
                .color(enabled ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        if (enabled) {
            lore.add(Component.text("  击杀奖励: " + reward + " 金币/次")
                    .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("  每日上限: " + limit + " 次")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));

            int kills = plugin.getDragonKillManager().getKillCount(player.getUniqueId());
            int remaining = Math.max(0, limit - kills);
            lore.add(Component.empty());
            lore.add(Component.text("  今日已击杀: " + kills + " 次")
                    .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("  今日剩余: " + remaining + " 次")
                    .color(remaining > 0 ? NamedTextColor.GREEN : NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("  未启用")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBackItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("返回")
                .color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
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
