package com.some.domain.itemmanagementsystem;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import javax.xml.crypto.Data;

//コマンドの設定
public class Command implements CommandExecutor {
    private final RegisterChest.ChestClickListener listener;

    public Command(RegisterChest.ChestClickListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("imsystem")){

            // アイテムテーブルの一括更新
            if (args.length == 2 && args[0].equalsIgnoreCase("make") && args[1].equalsIgnoreCase("all")) {
                DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                dbManager.processAndSaveAllChestItems(sender);
                return true;
            }

            // 指定した場所のチェストについてアイテムテーブルを更新
            else if (args.length > 4 && args[0].equalsIgnoreCase("make") && args[1].equalsIgnoreCase("chest")) {
                if (args.length < 6) {
                    sender.sendMessage("[ワールド名　X Y Z] を指定してください。");
                    return true;
                }
                Location inputLocation;
                World world = Bukkit.getWorld(args[2]);
                if (world == null) {
                    sender.sendMessage("ワールド '" + args[2] + "' が見つかりません。");
                    return true;
                }
                try {
                    int x = Integer.parseInt(args[3]);
                    int y = Integer.parseInt(args[4]);
                    int z = Integer.parseInt(args[5]);
                    inputLocation = new Location(world, x, y, z);
                } catch (NumberFormatException e) {
                    sender.sendMessage("座標の形式が正しくありません。");
                    return true;
                }
                DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                dbManager.updateItemTableForChest(inputLocation);
                return true;
            }

            // 指定した施設のグループ限定でアイテムテーブルの更新
            else if (args.length > 0 && args[0].equalsIgnoreCase("make")) {
                if (args.length < 3) {
                    sender.sendMessage("施設名とグループ名を指定してください。");
                    return true;
                }
                DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                dbManager.processAndSaveItemsForGroup(args[1], args[2], sender);
                return true;
            }

            // 施設名を変数にセット。以降のregisterではこの施設名で登録される。
            else if (args.length > 1 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("facility")) {
                if (args.length < 3) {
                    sender.sendMessage("[施設名] を指定してください。");
                    return true;
                }
                listener.facilityName = args[2];
                sender.sendMessage("施設名を" + args[2] + "でセットしました。");
                return true;
            }

            // データベースにチェスト情報を登録
            else if (args.length > 1 && args[0].equalsIgnoreCase("register") && args[1].equalsIgnoreCase("as")) {
                if (args.length < 3) {
                    sender.sendMessage("[グループ名] を指定してください。");
                    return true;
                }
                if (listener.facilityName.isEmpty()) {
                    sender.sendMessage("施設名をセットしてください。");
                    return true;
                }
                listener.copyAndResetChestLocations();
                DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                dbManager.saveChestData(listener.chestLocationsCopy, listener.facilityName, args[2], sender);
                return true;
            }

            // 指定した施設のグループにチェストを追加
            // [注意]　チェスト番号はすでにある番号に続く形で登録されるため、場合によっては初めから登録しなおす必要があります。
            else if (args.length == 4 && args[0].equalsIgnoreCase("add") && args[1].equalsIgnoreCase("to")) {
                listener.copyAndResetChestLocations();
                DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                dbManager.addChestsToExistingGroup(listener.chestLocationsCopy, args[2], args[3], sender);
                return true;
            }

            // 施設名とグループ名ごとに登録されたチェストの数を表示
            else if (args.length == 1 && args[0].equalsIgnoreCase("group")) {
                DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                dbManager.displayChestGroupCounts(sender);
                return true;
            }

            // 指定した施設名とグループ名のチェストの位置情報を列挙
            else if (args.length > 1 && args[0].equalsIgnoreCase("chest") && args[1].equalsIgnoreCase("of")) {
                if (args.length < 3) {
                    sender.sendMessage("[施設名 グループ名] を指定してください。");
                    return true;
                }
                DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                dbManager.displayChestLocationsForGroup(args[2], args[3], sender);
                return true;
            }

            // 指定した施設のデータをすべて削除
            else if (args.length > 1 && args[0].equalsIgnoreCase("delete") && args[1].equalsIgnoreCase("facility")) {
                if (args.length < 3) {
                    sender.sendMessage("[施設名] を指定してください。");
                    return true;
                }
                DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                dbManager.deleteChestsFromFacility(args[2], sender);
                return true;
            }

            // 指定した施設のグループのデータをすべて削除
            else if (args.length > 1 && args[0].equalsIgnoreCase("delete") && args[1].equalsIgnoreCase("group")) {
                if (args.length < 4) {
                    sender.sendMessage("[施設名 グループ名] を指定してください。");
                    return true;
                }
                DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                dbManager.deleteChestsFromFacilityGroup(args[2], args[3], sender);
                return true;
            }

            // 指定した位置情報のチェストのデータをすべて削除
            // チェスト番号も再割り当てされます。
            else if (args.length > 1 && args[0].equalsIgnoreCase("delete") && args[1].equalsIgnoreCase("chest")) {
                if (args.length < 6) {
                    sender.sendMessage("[ワールド名 X Y Z] を指定してください。");
                    return true;
                }
                Location inputLocation;
                World world = Bukkit.getWorld(args[2]);
                if (world == null) {
                    sender.sendMessage("ワールド '" + args[2] + "' が見つかりません。");
                    return true;
                }
                try {
                    int x = Integer.parseInt(args[3]);
                    int y = Integer.parseInt(args[4]);
                    int z = Integer.parseInt(args[5]);
                    inputLocation = new Location(world, x, y, z);
                } catch (NumberFormatException e) {
                    sender.sendMessage("座標の形式が正しくありません。");
                    return true;
                }
                DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                dbManager.deleteChestAtLocation(inputLocation, sender);
                return true;
            }

            // カスタム斧を付与
            else if (args.length == 1 && args[0].equalsIgnoreCase("axe")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("このコマンドはプレイヤーのみが使用できます。");
                    return true;
                }
                Player player = (Player) sender;
                ItemStack customAxe = ItemManagementSystem.getCustomAxe();

                if (player.getInventory().addItem(customAxe).isEmpty()) {
                    player.sendMessage("カスタム斧を受け取りました。");
                } else {
                    player.sendMessage("インベントリがいっぱいです。");
                }
                return true;
            }

            // クリックしたチェストリストをリセット
            else if (args.length == 1 && args[0].equalsIgnoreCase("reset_clickedchest")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("このコマンドはプレイヤーのみが使用できます。");
                    return true;
                }
                listener.ResetChestLocations();
                sender.sendMessage("クリックしたチェストのリストをリセットしました。");
                return true;
            }

            // グループ名を変更
            else if (args.length == 6 && args[0].equalsIgnoreCase("rename") && args[3].equalsIgnoreCase("as")) {
                DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                dbManager.changeGroupName(args[1], args[2], args[4], args[5], sender);
                return true;
            }

            // 施設名を変更
            else if (args.length == 4 && args[0].equalsIgnoreCase("rename") && args[2].equalsIgnoreCase("as")) {
                DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                dbManager.changeFacilityName(args[1], args[3], sender);
                return true;
            }

            // 指定した施設のグループ名を列挙
            else if (args.length > 1 && args[0].equalsIgnoreCase("group") && args[1].equalsIgnoreCase("of")) {
                if (args.length < 3) {
                    sender.sendMessage("[施設名] を指定してください。");
                    return true;
                }
                DatabaseManager dbManager = new DatabaseManager(ItemManagementSystem.getInstance());
                dbManager.displayGroupsForFacility(args[2], sender);
                return true;
            }

            else {
                sender.sendMessage("コマンドが間違っています。");
                return true;
            }
        }
        return false;
    }
}

