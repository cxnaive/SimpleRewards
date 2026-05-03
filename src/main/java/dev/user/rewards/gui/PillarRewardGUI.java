package dev.user.rewards.gui;

import dev.user.rewards.SimpleRewardsPlugin;
import dev.user.rewards.manager.GameRewardManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 幸运之柱奖励领取 GUI
 * 移植自 ThwReward 的 RewardGUI
 *
 * 布局 (6行 54格):
 * Row 0: 边框
 * Row 1: 边框 + [待领取金币](slot 4) + 边框
 * Row 2: 边框
 * Row 3-5: [奖励明细]
 * Row 5: [金币限制](slot 49) + [返回](slot 48)
 */
public class PillarRewardGUI extends AbstractGUI {

    private static final int PENDING_SLOT = 4;
    private static final int REWARD_START = 27;
    private static final int MAX_REWARD_SLOTS = 18;
    private static final int INFO_SLOT = 49;

    // 保存从DB加载的数据，供点击时使用
    private int pendingTotal = 0;
    private int pendingCount = 0;
    private int dailyEarned = 0;
    private int weeklyEarned = 0;
    private int dailyLimit = 2500;
    private int weeklyLimit = 5000;

    public PillarRewardGUI(SimpleRewardsPlugin plugin, Player player) {
        super(plugin, player, "§6§l幸运之柱 · 金币领取", 54);
    }

    public static void open(SimpleRewardsPlugin plugin, Player player) {
        new PillarRewardGUI(plugin, player).open();
    }

    @Override
    public void initialize() {
        actions.clear();
        ItemStack border = createBorderItem();
        fillBorder(border);
        // Row 2 分隔线 (slots 18-26)
        for (int col = 0; col < 9; col++) {
            setItem(18 + col, border);
        }

        GameRewardManager mgr = plugin.getGameRewardManager();

        // 先用缓存数据渲染，再异步刷新
        setItem(PENDING_SLOT, createPendingItem(0, 0), (p, e) -> handleClaim(p));
        setItem(INFO_SLOT, createLimitItem(0, 0, 2500, 5000));
        setItem(48, createBackItem(), (p, e) -> {
            close();
            GameplayRewardGUI.open(plugin, p);
        });
        setItem(53, createCloseItem(), (p, e) -> close());

        // 异步加载最新数据后刷新
        if (mgr != null) {
            mgr.getPendingInfo(player.getUniqueId(), info -> {
                p().getScheduler().execute(plugin, () -> {
                    if (!p().isOnline()) return;
                    this.pendingTotal = info.total();
                    this.pendingCount = info.count();
                    this.dailyEarned = info.dailyEarned();
                    this.weeklyEarned = info.weeklyEarned();
                    this.dailyLimit = info.dailyLimit();
                    this.weeklyLimit = info.weeklyLimit();

                    setItem(PENDING_SLOT, createPendingItem(pendingTotal, pendingCount), (pl, e) -> handleClaim(pl));
                    setItem(INFO_SLOT, createLimitItem(dailyEarned, weeklyEarned, dailyLimit, weeklyLimit));
                    p().updateInventory();
                }, () -> {}, 0L);
            });
        }
    }

    private void handleClaim(Player player) {
        if (pendingTotal <= 0) {
            player.sendMessage("§c你没有待领取的奖励");
            return;
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.2f);

        GameRewardManager mgr = plugin.getGameRewardManager();
        if (mgr == null) return;

        mgr.claimRewards(player, () -> {
            player.getScheduler().execute(plugin, () -> {
                if (!player.isOnline()) return;
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                // 刷新 GUI
                inventory.clear();
                initialize();
            }, () -> {}, 0L);
        });
    }

    private Player p() { return player; }

    private ItemStack createPendingItem(int total, int count) {
        boolean hasRewards = total > 0;
        Material material = hasRewards ? Material.GOLD_BLOCK : Material.COAL;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("待领取金币")
                .color(hasRewards ? NamedTextColor.GOLD : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("  待领取: " + total + " 金币")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("  局数: " + count + " 局")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        if (hasRewards) {
            lore.add(Component.text("  点击领取所有奖励")
                    .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("  暂无待领取奖励")
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createLimitItem(int daily, int weekly, int dLimit, int wLimit) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("金币限制")
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("  今日已获取: " + daily + "/" + dLimit)
                .color(daily >= dLimit ? NamedTextColor.RED : NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("  本周已获取: " + weekly + "/" + wLimit)
                .color(weekly >= wLimit ? NamedTextColor.RED : NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBackItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text("返回").color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCloseItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text("关闭").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
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
}
