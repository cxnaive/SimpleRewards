package dev.user.rewards.gui;

import dev.user.rewards.SimpleRewardsPlugin;
import dev.user.rewards.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 每周在线奖励GUI（大箱子 54 格）
 *
 * 布局 (每行最多4个里程碑，共2行，最多8个):
 * Row 0: 边框
 * Row 1: 在线时长信息（居中）
 * Row 2: 里程碑第1行（居中有间隔）
 * Row 3: 里程碑第2行（居中有间隔）
 * Row 4: 分隔线
 * Row 5: 关闭按钮（居中）
 *
 * 里程碑物品:
 * - 已领取:   DIAMOND_BLOCK (璀璨蓝)
 * - 可领取:   GOLD_BLOCK    (金色/附魔发光)
 * - 未达成:   IRON_BLOCK    (银灰)
 */
public class WeeklyOnlineGUI extends AbstractGUI {

    private static final int MAX_PER_ROW = 4;
    private static final int MAX_TOTAL = 8;
    private static final int ROW1_BASE = 18; // 里程碑第1行起始
    private static final int ROW2_BASE = 27; // 里程碑第2行起始

    public WeeklyOnlineGUI(SimpleRewardsPlugin plugin, Player player) {
        super(plugin, player, plugin.getConfigManager().getMessage("gui.online-title"), 54);
    }

    public static void open(SimpleRewardsPlugin plugin, Player player) {
        new WeeklyOnlineGUI(plugin, player).open();
    }

    @Override
    public void initialize() {
        actions.clear();

        UUID uuid = player.getUniqueId();
        int weeklyMinutes = plugin.getWeeklyOnlineManager().getWeeklyMinutes(uuid);
        Set<Integer> claimed = plugin.getWeeklyOnlineManager().getClaimedMilestones(uuid);
        Set<Integer> unclaimed = plugin.getWeeklyOnlineManager().getUnclaimedMilestones(uuid);

        // ===== 边框 =====
        ItemStack border = createBorderItem();
        fillBorder(border);
        // Row 4 分隔线 (slots 36-44)
        for (int col = 0; col < 9; col++) {
            setItem(36 + col, border);
        }

        // ===== Row 1: 在线时长信息（slot 13） =====
        setItem(13, createInfoItem(weeklyMinutes));

        // ===== Row 2-3: 里程碑（每行最多4个，居中有间隔） =====
        List<ConfigManager.Milestone> milestones = plugin.getConfigManager().getMilestones()
                .values().stream()
                .limit(MAX_TOTAL)
                .toList();

        int total = milestones.size();
        int row1Count = Math.min(total, MAX_PER_ROW);
        int row2Count = total - row1Count;

        // 第1行
        int[] row1Slots = calculateRowSlots(row1Count, ROW1_BASE);
        for (int i = 0; i < row1Count; i++) {
            placeMilestone(row1Slots[i], milestones.get(i), claimed, unclaimed, weeklyMinutes);
        }

        // 第2行
        if (row2Count > 0) {
            int[] row2Slots = calculateRowSlots(row2Count, ROW2_BASE);
            for (int i = 0; i < row2Count; i++) {
                placeMilestone(row2Slots[i], milestones.get(row1Count + i), claimed, unclaimed, weeklyMinutes);
            }
        }

        // ===== Row 5: 关闭按钮（slot 49） =====
        setItem(49, createCloseItem(), (p, e) -> close());
    }

    private void placeMilestone(int slot, ConfigManager.Milestone ms,
                                Set<Integer> claimed, Set<Integer> unclaimed, int weeklyMinutes) {
        boolean isClaimed = claimed.contains(ms.getMinutes());
        boolean canClaim = unclaimed.contains(ms.getMinutes()) && !plugin.getConfigManager().isAutoGrant();
        boolean isReached = weeklyMinutes >= ms.getMinutes();

        setItem(slot, createMilestoneItem(ms, isClaimed, canClaim, isReached, weeklyMinutes), (p, e) -> {
            if (canClaim) {
                claimMilestone(p, ms);
            }
        });
    }

    /**
     * 计算一行内里程碑的槽位（居中，相邻间隔1格）
     * count=4: cols 1,3,5,7
     * count=3: cols 2,4,6
     * count=2: cols 3,5
     * count=1: col 4
     */
    private int[] calculateRowSlots(int count, int rowBase) {
        int[] slots = new int[count];
        int totalWidth = count * 2 - 1; // 带间隔的总宽度
        int startCol = (7 - totalWidth) / 2 + 1; // 在 7 格可用空间(col 1-7)中居中
        for (int i = 0; i < count; i++) {
            slots[i] = rowBase + startCol + i * 2;
        }
        return slots;
    }

    private void claimMilestone(Player player, ConfigManager.Milestone ms) {
        plugin.getWeeklyOnlineManager().claimMilestone(player.getUniqueId(), ms.getMinutes(), success -> {
            player.getScheduler().execute(plugin, () -> {
                if (!success) {
                    player.sendMessage(plugin.getConfigManager().getMessage("milestone-claim-failed"));
                }
                inventory.clear();
                initialize();
            }, () -> {}, 0L);
        });
    }

    // ==================== 物品创建 ====================

    private ItemStack createInfoItem(int weeklyMinutes) {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Component.text("本周在线时长")
                .color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));

        int hours = weeklyMinutes / 60;
        int mins = weeklyMinutes % 60;

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("  " + hours + " 小时 " + mins + " 分钟")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("  在线时长每周重置")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createMilestoneItem(ConfigManager.Milestone ms, boolean isClaimed,
                                           boolean canClaim, boolean isReached, int weeklyMinutes) {
        Material material = isClaimed ? Material.DIAMOND_BLOCK
                : canClaim ? Material.GOLD_BLOCK
                : Material.IRON_BLOCK;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        NamedTextColor nameColor = isClaimed ? NamedTextColor.AQUA
                : canClaim ? NamedTextColor.GOLD
                : NamedTextColor.GRAY;

        meta.displayName(Component.text(ms.getDescription())
                .color(nameColor).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());

        if (ms.getMoney() > 0) {
            lore.add(Component.text("  金币: " + String.format("%.0f", ms.getMoney()))
                    .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        }
        if (ms.getPoints() > 0) {
            lore.add(Component.text("  点券: " + ms.getPoints())
                    .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        }

        lore.add(Component.empty());

        if (isClaimed) {
            lore.add(Component.text("  ✔ 已领取")
                    .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        } else if (canClaim) {
            lore.add(Component.text("  ✦ 点击领取")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        } else if (isReached) {
            lore.add(Component.text("  ● 即将发放...")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        } else {
            int remaining = ms.getMinutes() - weeklyMinutes;
            lore.add(Component.text("  还需 " + remaining / 60 + "h " + remaining % 60 + "m")
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);

        if (canClaim) {
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

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
