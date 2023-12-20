package com.some.domain.itemmanagementsystem;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.*;
import org.bukkit.persistence.PersistentDataType;

// チェストをデータベースに登録する
public class RegisterChest {

    //カスタム斧を作成するメソッド
    public static ItemStack createCustomAxe() {
        ItemStack axe = new ItemStack(Material.IRON_AXE);
        ItemMeta meta = axe.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("imsystem_axe");
            meta.getPersistentDataContainer().set(new NamespacedKey(ItemManagementSystem.getInstance(), "imsystem_axe"), PersistentDataType.INTEGER, 1);
            axe.setItemMeta(meta);
        }
        return axe;
    }

    // クリックされたチェストの情報を管理
    public static class ChestClickListener implements Listener {
        public String facilityName = "";
        private final List<Location> chestLocations = new ArrayList<>();
        public List<Location> chestLocationsCopy = new ArrayList<>();

        public void copyAndResetChestLocations() {
            chestLocationsCopy = new ArrayList<>(chestLocations);
            chestLocations.clear();
        }

        public void ResetChestLocations() {
            chestLocations.clear();
        }

        // プレイヤーがクリックしたチェストを一時的なリストに追加
        @EventHandler
        public void onPlayerInteract(PlayerInteractEvent event) {
            ItemStack item = event.getItem();

            if (item != null && item.getType() == Material.IRON_AXE && item.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(ItemManagementSystem.getInstance(), "imsystem_axe"), PersistentDataType.INTEGER)) {
                Block clickedBlock = event.getClickedBlock();
                if (clickedBlock != null && (clickedBlock.getType() == Material.CHEST)) {
                    event.setCancelled(true); // チェストが開かないようにする
                    String location = "Chest at: " + clickedBlock.getLocation().toString();
                    event.getPlayer().sendMessage(ChatColor.GREEN + "Chest added: " + location);
                    Chest chestData = (Chest) clickedBlock.getBlockData();

                    // ラージチェストの左側と右側の位置を特定
                    // プレイヤーから見て右がleftchest, LEFTです。
                    Location leftChest = null;
                    Location rightChest = null;

                    if (chestData.getType() == Chest.Type.LEFT) {
                        leftChest = clickedBlock.getLocation();
                        rightChest = getOtherHalfChestLocation(clickedBlock);
                    } else if (chestData.getType() == Chest.Type.RIGHT) {
                        rightChest = clickedBlock.getLocation();
                        leftChest = getOtherHalfChestLocation(clickedBlock);
                    } else {
                        // 単一のチェストの場合
                        rightChest = clickedBlock.getLocation();
                    }

                    // リストに追加
                    if (rightChest != null && !chestLocations.contains(rightChest)) {
                        chestLocations.add(rightChest);
                    }
                    if (leftChest != null && !chestLocations.contains(leftChest)) {
                        chestLocations.add(leftChest);
                    }
                }
            }
        }

        // ラージチェストの片方から、もう片方の位置情報を得る
        public static Location getOtherHalfChestLocation(Block block) {
            Chest chestData = (Chest) block.getBlockData();
            BlockFace facing = chestData.getFacing();

            int modX = 0, modZ = 0;
            if (chestData.getType() == Chest.Type.RIGHT) {
                // 左側のチェストを探す
                modX = facing == BlockFace.NORTH ? -1 : facing == BlockFace.SOUTH ? 1 : 0;
                modZ = facing == BlockFace.WEST ? 1 : facing == BlockFace.EAST ? -1 : 0;
            } else if (chestData.getType() == Chest.Type.LEFT) {
                // 右側のチェストを探す
                modX = facing == BlockFace.NORTH ? 1 : facing == BlockFace.SOUTH ? -1 : 0;
                modZ = facing == BlockFace.WEST ? -1 : facing == BlockFace.EAST ? 1 : 0;
            }

            return block.getRelative(modX, 0, modZ).getLocation();
        }
    }
}

