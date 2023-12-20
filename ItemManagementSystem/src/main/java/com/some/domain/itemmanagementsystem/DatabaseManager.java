package com.some.domain.itemmanagementsystem;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitRunnable;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

import org.bukkit.Location;

// データベース操作を含むメソッドのクラス
public class DatabaseManager {
    private final JavaPlugin plugin;
    private String dbUrl;
    private String dbUser;
    private String dbPassword;
    private Integer batchSize;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    // データベース情報を取得
    private void loadConfig() {
        plugin.saveDefaultConfig();
        dbUrl = "jdbc:mysql://" + plugin.getConfig().getString("database.host") + ":"
                + plugin.getConfig().getInt("database.port") + "/"
                + plugin.getConfig().getString("database.dbname") + "?allowPublicKeyRetrieval=true&useSSL=false";
        dbUser = plugin.getConfig().getString("database.user");
        dbPassword = plugin.getConfig().getString("database.password");
        batchSize = plugin.getConfig().getInt("batchSize");
    }


    // 指定されたグループ名でチェストを登録
    public void saveChestData(List<Location> chestLocations, String facilityName, String groupName, CommandSender sender) {
        new BukkitRunnable() {
            @Override
            public void run() {
                // 1. 既に存在するグループ名を確認する
                Connection conn = null;
                try {
                    conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                    conn.setAutoCommit(false);

                    // 既存のグループ名を確認
                    String sqlCheckGroup = "SELECT COUNT(*) FROM ChestFacilityGroups WHERE facility_name = ? AND group_name = ?";
                    try (PreparedStatement stmtCheckGroup = conn.prepareStatement(sqlCheckGroup)) {
                        stmtCheckGroup.setString(1, facilityName);
                        stmtCheckGroup.setString(2, groupName);
                        try (ResultSet rs = stmtCheckGroup.executeQuery()) {
                            if (rs.next() && rs.getInt(1) > 0) {
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        sender.sendMessage("施設名：" + facilityName + " グループ名：" + groupName + " はすでに登録されています。\naddを使用してください。");
                                    }
                                }.runTask(plugin);
                                return;
                            }
                        }
                    }

                    // 2.登録されていないグループ名であればチェストをデータベースへ登録
                    String sqlChests = "INSERT INTO Chests (world, x, y, z) VALUES (?, ?, ?, ?)";
                    try (PreparedStatement stmtChests = conn.prepareStatement(sqlChests, Statement.RETURN_GENERATED_KEYS)) {
                        for (int i = 0; i < chestLocations.size(); i++) {
                            // チェストの位置情報を保存
                            Location loc = chestLocations.get(i);
                            stmtChests.setString(1, loc.getWorld().getName());
                            stmtChests.setInt(2, loc.getBlockX());
                            stmtChests.setInt(3, loc.getBlockY());
                            stmtChests.setInt(4, loc.getBlockZ());
                            stmtChests.executeUpdate();

                            // 生成された chest_id を取得
                            try (ResultSet generatedKeys = stmtChests.getGeneratedKeys()) {
                                if (generatedKeys.next()) {
                                    int chestId = generatedKeys.getInt(1);

                                    // ChestNumbers テーブルに挿入
                                    String sqlChestNumbers = "INSERT INTO ChestNumbers (chest_id, chest_number) VALUES (?, ?)";
                                    try (PreparedStatement stmtChestNumbers = conn.prepareStatement(sqlChestNumbers)) {
                                        stmtChestNumbers.setInt(1, chestId);
                                        stmtChestNumbers.setInt(2, i + 1);
                                        stmtChestNumbers.executeUpdate();
                                    }

                                    // ChestGroups テーブルに挿入
                                    String sqlGroups = "INSERT INTO ChestFacilityGroups (chest_id, facility_name, group_name) VALUES (?, ?, ?)";
                                    try (PreparedStatement stmtGroups = conn.prepareStatement(sqlGroups)) {
                                        stmtGroups.setInt(1, chestId);
                                        stmtGroups.setString(2, facilityName);
                                        stmtGroups.setString(3, groupName);
                                        stmtGroups.executeUpdate();
                                    }
                                }
                            }
                        }
                    }
                    conn.commit();
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            sender.sendMessage("施設名：" + facilityName + " グループ名：" + groupName + " で" + chestLocations.size() + "個のチェストを登録しました。");
                        }
                    }.runTask(plugin);

                } catch (SQLException e) {
                    // ロールバック
                    try {
                        conn.rollback();
                    } catch (SQLException ex) {
                        ex.printStackTrace();
                    }
                    e.printStackTrace();
                } finally {
                    // Connection をクローズ
                    try {
                        if (conn != null && !conn.isClosed()) {
                            conn.close();
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    // 既存のグループにチェストを追加（グループ内番号はすでにあるチェストの続きとして登録）
    public void addChestsToExistingGroup(List<Location> newChestLocations, String facilityName, String groupName, CommandSender sender) {
        new BukkitRunnable() {
            @Override
            public void run() {
                Connection conn = null;
                int maxChestNumber = 0;
                try {
                    conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                    conn.setAutoCommit(false);

                    // 最大 chest_number を取得する
                    String sqlGetMaxChestNumber = "SELECT MAX(chest_number) FROM ChestNumbers WHERE chest_id IN (SELECT chest_id FROM ChestFacilityGroups WHERE facility_name = ? AND group_name = ?)";
                    try (PreparedStatement stmtGetMaxChestNumber = conn.prepareStatement(sqlGetMaxChestNumber)) {

                        stmtGetMaxChestNumber.setString(1, facilityName);
                        stmtGetMaxChestNumber.setString(2, groupName);
                        try (ResultSet rs = stmtGetMaxChestNumber.executeQuery()) {
                            if (rs.next()) {
                                maxChestNumber = rs.getInt(1);
                            }
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }

                    // maxChestNumber が 0 の場合は何もせず終了
                    if (maxChestNumber == 0) {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                sender.sendMessage("施設名：" + facilityName + " グループ名：" + groupName + " はまだ登録されていません。\nregisterを使用してください。");
                            }
                        }.runTask(plugin);
                        return;
                    }

                    // 新しいチェストのデータを追加
                    String sqlChests = "INSERT INTO Chests (world, x, y, z) VALUES (?, ?, ?, ?)";
                    try (PreparedStatement stmtChests = conn.prepareStatement(sqlChests, Statement.RETURN_GENERATED_KEYS)) {
                        for (int i = 0; i < newChestLocations.size(); i++) {
                            // チェストの位置情報を保存
                            Location loc = newChestLocations.get(i);
                            stmtChests.setString(1, loc.getWorld().getName());
                            stmtChests.setInt(2, loc.getBlockX());
                            stmtChests.setInt(3, loc.getBlockY());
                            stmtChests.setInt(4, loc.getBlockZ());
                            stmtChests.executeUpdate();

                            // 生成された chest_id を取得
                            try (ResultSet generatedKeys = stmtChests.getGeneratedKeys()) {
                                if (generatedKeys.next()) {
                                    int chestId = generatedKeys.getInt(1);

                                    // ChestNumbers テーブルに挿入
                                    String sqlChestNumbers = "INSERT INTO ChestNumbers (chest_id, chest_number) VALUES (?, ?)";
                                    try (PreparedStatement stmtChestNumbers = conn.prepareStatement(sqlChestNumbers)) {
                                        stmtChestNumbers.setInt(1, chestId);
                                        stmtChestNumbers.setInt(2, ++maxChestNumber);
                                        stmtChestNumbers.executeUpdate();
                                    }

                                    // ChestGroups テーブルに挿入
                                    String sqlGroups = "INSERT INTO ChestFacilityGroups (chest_id, facility_name, group_name) VALUES (?, ?, ?)";
                                    try (PreparedStatement stmtGroups = conn.prepareStatement(sqlGroups)) {
                                        stmtGroups.setInt(1, chestId);
                                        stmtGroups.setString(2, facilityName);
                                        stmtGroups.setString(3, groupName);
                                        stmtGroups.executeUpdate();
                                    }
                                }
                            }
                        }
                    }
                    conn.commit();
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            sender.sendMessage("施設名：" + facilityName + " グループ名 '" + groupName + " で" + newChestLocations.size() + "個のチェストを追加しました。");
                        }
                    }.runTask(plugin);

                } catch (SQLException e) {
                    // エラー処理とロールバック
                    try {
                        if (conn != null) {
                            conn.rollback();
                        }
                    } catch (SQLException ex) {
                        ex.printStackTrace();
                    }
                    e.printStackTrace();
                } finally {
                    try {
                        if (conn != null && !conn.isClosed()) {
                            conn.close();
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    // 指定した施設を丸ごと削除
    public void deleteChestsFromFacility(String facilityName, CommandSender sender) {

        new BukkitRunnable() {
            @Override
            public void run() {
                Connection conn = null;
                ArrayList<Integer> chestIds = new ArrayList<>();
                try {
                    conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                    // トランザクション開始
                    conn.setAutoCommit(false);

                    // 関連する chest_id を取得
                    String sqlSelectChestIds = "SELECT chest_id FROM ChestFacilityGroups WHERE facility_name = ?";
                    try (PreparedStatement stmtSelectChestIds = conn.prepareStatement(sqlSelectChestIds)) {
                        stmtSelectChestIds.setString(1, facilityName);
                        try (ResultSet rs = stmtSelectChestIds.executeQuery()) {
                            while (rs.next()) {
                                chestIds.add(rs.getInt("chest_id"));
                            }
                        }
                    }

                    // ChestNumbers から削除
                    String sqlDeleteChestNumbers = "DELETE FROM ChestNumbers WHERE chest_id = ?";
                    try (PreparedStatement stmtDeleteChestNumbers = conn.prepareStatement(sqlDeleteChestNumbers)) {
                        for (Integer chestId : chestIds) {
                            stmtDeleteChestNumbers.setInt(1, chestId);
                            stmtDeleteChestNumbers.addBatch();
                        }
                        stmtDeleteChestNumbers.executeBatch();
                    }

                    // ChestGroups から削除
                    String sqlDeleteChestGroups = "DELETE FROM ChestFacilityGroups WHERE facility_name = ?";
                    try (PreparedStatement stmtDeleteChestGroups = conn.prepareStatement(sqlDeleteChestGroups)) {
                        stmtDeleteChestGroups.setString(1, facilityName);
                        stmtDeleteChestGroups.executeUpdate();
                    }

                    // アイテムテーブルから削除
                    String sqlDeleteItems = "DELETE FROM ItemTable WHERE chest_id = ?";
                    try (PreparedStatement stmtDeleteItems = conn.prepareStatement(sqlDeleteItems)) {
                        for (Integer chestId : chestIds) {
                            stmtDeleteItems.setInt(1, chestId);
                            stmtDeleteItems.addBatch();
                        }
                        stmtDeleteItems.executeBatch();
                    }

                    // Chests から削除
                    String sqlDeleteChests = "DELETE FROM Chests WHERE chest_id = ?";
                    try (PreparedStatement stmtDeleteChests = conn.prepareStatement(sqlDeleteChests)) {
                        for (Integer chestId : chestIds) {
                            stmtDeleteChests.setInt(1, chestId);
                            stmtDeleteChests.addBatch();
                        }
                        stmtDeleteChests.executeBatch();
                    }

                    // トランザクションをコミット
                    conn.commit();
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            sender.sendMessage("施設名：" + facilityName + " に登録されていた"+ chestIds.size() + "個のチェストを削除しました。");
                        }
                    }.runTask(plugin);

                } catch (SQLException e) {
                    try {
                        conn.rollback();
                    } catch (SQLException ex) {
                        ex.printStackTrace();
                    }
                    e.printStackTrace();
                } finally {
                    try {
                        if (conn != null && !conn.isClosed()) {
                            conn.close();
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    // 指定したグループを丸ごと削除
    public void deleteChestsFromFacilityGroup(String facilityName, String groupName, CommandSender sender) {

        new BukkitRunnable() {
            @Override
            public void run() {
                Connection conn = null;
                ArrayList<Integer> chestIds = new ArrayList<>();
                try {
                    conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                    // トランザクション開始
                    conn.setAutoCommit(false);

                    // 関連する chest_id を取得
                    String sqlSelectChestIds = "SELECT chest_id FROM ChestFacilityGroups WHERE facility_name = ? AND group_name = ?";
                    try (PreparedStatement stmtSelectChestIds = conn.prepareStatement(sqlSelectChestIds)) {
                        stmtSelectChestIds.setString(1, facilityName);
                        stmtSelectChestIds.setString(2, groupName);
                        try (ResultSet rs = stmtSelectChestIds.executeQuery()) {
                            while (rs.next()) {
                                chestIds.add(rs.getInt("chest_id"));
                            }
                        }
                    }

                    // ChestNumbers から削除
                    String sqlDeleteChestNumbers = "DELETE FROM ChestNumbers WHERE chest_id = ?";
                    try (PreparedStatement stmtDeleteChestNumbers = conn.prepareStatement(sqlDeleteChestNumbers)) {
                        for (Integer chestId : chestIds) {
                            stmtDeleteChestNumbers.setInt(1, chestId);
                            stmtDeleteChestNumbers.addBatch();
                        }
                        stmtDeleteChestNumbers.executeBatch();
                    }

                    // ChestGroups から削除
                    String sqlDeleteChestGroups = "DELETE FROM ChestFacilityGroups WHERE facility_name = ? AND group_name = ?";
                    try (PreparedStatement stmtDeleteChestGroups = conn.prepareStatement(sqlDeleteChestGroups)) {
                        stmtDeleteChestGroups.setString(1, facilityName);
                        stmtDeleteChestGroups.setString(2, groupName);
                        stmtDeleteChestGroups.executeUpdate();
                    }

                    // アイテムテーブルから削除
                    String sqlDeleteItems = "DELETE FROM ItemTable WHERE chest_id = ?";
                    try (PreparedStatement stmtDeleteItems = conn.prepareStatement(sqlDeleteItems)) {
                        for (Integer chestId : chestIds) {
                            stmtDeleteItems.setInt(1, chestId);
                            stmtDeleteItems.addBatch();
                        }
                        stmtDeleteItems.executeBatch();
                    }

                    // Chests から削除
                    String sqlDeleteChests = "DELETE FROM Chests WHERE chest_id = ?";
                    try (PreparedStatement stmtDeleteChests = conn.prepareStatement(sqlDeleteChests)) {
                        for (Integer chestId : chestIds) {
                            stmtDeleteChests.setInt(1, chestId);
                            stmtDeleteChests.addBatch();
                        }
                        stmtDeleteChests.executeBatch();
                    }

                    // トランザクションをコミット
                    conn.commit();
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            sender.sendMessage("施設名：" + facilityName + " グループ名：" + groupName + " に登録されていた"+ chestIds.size() + "個のチェストを削除しました。");
                        }
                    }.runTask(plugin);

                } catch (SQLException e) {
                    try {
                        conn.rollback();
                    } catch (SQLException ex) {
                        ex.printStackTrace();
                    }
                    e.printStackTrace();
                } finally {
                    try {
                        if (conn != null && !conn.isClosed()) {
                            conn.close();
                        }
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    // 指定した位置のチェストを削除（グループ内での番号は再割り当て）
    public void deleteChestAtLocation(Location location, CommandSender sender) {

        new BukkitRunnable() {
            @Override
            public void run() {
                Connection conn = null;
                try {
                    conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                    conn.setAutoCommit(false);

                    // 1. Chests テーブルから該当座標の chest_id を取得
                    int chestId = -1;
                    String sqlFindChestId = "SELECT chest_id FROM Chests WHERE world = ? AND x = ? AND y = ? AND z = ?";
                    try (PreparedStatement stmtFindChestId = conn.prepareStatement(sqlFindChestId)) {
                        stmtFindChestId.setString(1, location.getWorld().getName());
                        stmtFindChestId.setInt(2, location.getBlockX());
                        stmtFindChestId.setInt(3, location.getBlockY());
                        stmtFindChestId.setInt(4, location.getBlockZ());
                        try (ResultSet rs = stmtFindChestId.executeQuery()) {
                            if (rs.next()) {
                                chestId = rs.getInt("chest_id");
                            }
                        }
                    }

                    if (chestId != -1) {
                        // 2. 他のテーブルからデータを削除
                        String facilityname;
                        String groupname;
                        String sqlFindGroupname = "SELECT facility_name, group_name FROM ChestFacilityGroups where chest_id = ?";
                        try (PreparedStatement stmtFindGroupname = conn.prepareStatement(sqlFindGroupname)) {
                            stmtFindGroupname.setInt(1, chestId);
                            ResultSet rs = stmtFindGroupname.executeQuery();
                            rs.next();
                            facilityname = rs.getString("facility_name");
                            groupname = rs.getString("group_name");
                        }

                        String sqlDelete = "DELETE FROM %s WHERE chest_id = ?";
                        String[] tables = {"ChestNumbers", "ChestFacilityGroups", "ItemTable"};
                        for (String table : tables) {
                            try (PreparedStatement stmtDelete = conn.prepareStatement(String.format(sqlDelete, table))) {
                                stmtDelete.setInt(1, chestId);
                                stmtDelete.executeUpdate();
                            }
                        }

                        String sqlDeleteChests = "DELETE FROM Chests WHERE chest_id = ?";
                        try (PreparedStatement stmtDeleteChests = conn.prepareStatement(sqlDeleteChests)) {
                            stmtDeleteChests.setInt(1, chestId);
                            stmtDeleteChests.executeUpdate();
                        }

                        // 3. chestNumbers の再割り当て
                        ResultSet rsChestIds;
                        String sqlSelectChestIds = "SELECT chest_id FROM ChestFacilityGroups WHERE facility_name = ? AND group_name = ? ORDER BY chest_id";
                        try (PreparedStatement stmtSelectChestIds = conn.prepareStatement(sqlSelectChestIds)) {
                            stmtSelectChestIds.setString(1, facilityname);
                            stmtSelectChestIds.setString(2, groupname);
                            rsChestIds = stmtSelectChestIds.executeQuery();
                        }

                        int newNumber = 1;
                        String sqlUpdateNumber = "UPDATE ChestNumbers SET chest_number = ? WHERE chest_id = ?";
                        try (PreparedStatement stmtUpdateNumber = conn.prepareStatement(sqlUpdateNumber)) {
                            while (rsChestIds.next()) {
                                int SamechestId = rsChestIds.getInt("chest_id");
                                stmtUpdateNumber.setInt(1, newNumber++);
                                stmtUpdateNumber.setInt(2, SamechestId);
                                stmtUpdateNumber.addBatch();
                            }
                            stmtUpdateNumber.executeBatch();
                        }

                        conn.commit();
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                sender.sendMessage("指定された位置のチェストを削除しました。");
                            }
                        }.runTask(plugin);

                    }
                    else {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                sender.sendMessage("指定された位置にチェストが無い、または登録されていません。");
                            }
                        }.runTask(plugin);
                    }

                } catch (SQLException e) {
                    if (conn != null) {
                        try {
                            conn.rollback();
                        } catch (SQLException ex) {
                            ex.printStackTrace();
                        }
                    }
                    e.printStackTrace();
                } finally {
                    if (conn != null) {
                        try {
                            conn.close();
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    // すべてのチェストのアイテムを登録
    public void processAndSaveAllChestItems(CommandSender sender) {

        Connection conn = null;
        int batchCount = 0;
        int count = 0;
        try {
            conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
            conn.setAutoCommit(false); // トランザクションの開始

            // ItemTableの該当行の削除
            String sqlChestids = "SELECT chest_id FROM Chests";
            try (PreparedStatement stmtChests = conn.prepareStatement(sqlChestids)) {
                ResultSet rs = stmtChests.executeQuery();
                String sqlDeleteItems = "DELETE FROM ItemTable WHERE chest_id = ?";
                try (PreparedStatement stmtDeleteItems = conn.prepareStatement(sqlDeleteItems)) {
                    while (rs.next()) {
                        int chestId = rs.getInt("chest_id");
                        stmtDeleteItems.setInt(1, chestId);
                        stmtDeleteItems.addBatch();
                    }
                    stmtDeleteItems.executeBatch();
                }
            }

            String sqlChests = "SELECT chest_id, world, x, y, z FROM Chests";
            try (PreparedStatement stmtChests = conn.prepareStatement(sqlChests)) {
                ResultSet rs = stmtChests.executeQuery();

                String sqlInsertItems = "INSERT INTO ItemTable (chest_id, item_id, count) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE count = VALUES(count)";
                try (PreparedStatement stmtInsertItems = conn.prepareStatement(sqlInsertItems)) {
                    while (rs.next()) {
                        if (batchCount >= batchSize) {
                            stmtInsertItems.executeBatch();
                            conn.commit(); // バッチサイズに達したらコミット
                            batchCount = 0;
                        }

                        int chestId = rs.getInt("chest_id");
                        World world = Bukkit.getWorld(rs.getString("world"));
                        Location location = new Location(world, rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
                        HashMap<String, Integer> itemCounts = getContentsOfChest(location);
                        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
                            stmtInsertItems.setInt(1, chestId);
                            stmtInsertItems.setString(2, entry.getKey());
                            stmtInsertItems.setInt(3, entry.getValue());
                            stmtInsertItems.addBatch();
                        }
                        batchCount++;
                        count++;
                    }

                    if (batchCount > 0) {
                        stmtInsertItems.executeBatch(); // 残りのデータを処理
                    }
                }
            }
            conn.commit();
            sender.sendMessage("すべてのチェストのうち、" + count + "個のチェストが更新されました。");

        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback(); // ロールバック
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try {
                    conn.close(); // コネクションを閉じる
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void processAndSaveAllChestItems() {

        Connection conn = null;
        int batchCount = 0;
        int count = 0;
        try {
            conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
            conn.setAutoCommit(false); // トランザクションの開始

            // ItemTableの該当行の削除
            String sqlChestids = "SELECT chest_id FROM Chests";
            try (PreparedStatement stmtChests = conn.prepareStatement(sqlChestids)) {
                ResultSet rs = stmtChests.executeQuery();
                String sqlDeleteItems = "DELETE FROM ItemTable WHERE chest_id = ?";
                try (PreparedStatement stmtDeleteItems = conn.prepareStatement(sqlDeleteItems)) {
                    while (rs.next()) {
                        int chestId = rs.getInt("chest_id");
                        stmtDeleteItems.setInt(1, chestId);
                        stmtDeleteItems.addBatch();
                    }
                    stmtDeleteItems.executeBatch();
                }
            }

            String sqlChests = "SELECT chest_id, world, x, y, z FROM Chests";
            try (PreparedStatement stmtChests = conn.prepareStatement(sqlChests)) {
                ResultSet rs = stmtChests.executeQuery();

                String sqlInsertItems = "INSERT INTO ItemTable (chest_id, item_id, count) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE count = VALUES(count)";
                try (PreparedStatement stmtInsertItems = conn.prepareStatement(sqlInsertItems)) {
                    while (rs.next()) {
                        if (batchCount >= batchSize) {
                            stmtInsertItems.executeBatch();
                            conn.commit(); // バッチサイズに達したらコミット
                            batchCount = 0;
                        }

                        int chestId = rs.getInt("chest_id");
                        World world = Bukkit.getWorld(rs.getString("world"));
                        Location location = new Location(world, rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
                        HashMap<String, Integer> itemCounts = getContentsOfChest(location);
                        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
                            stmtInsertItems.setInt(1, chestId);
                            stmtInsertItems.setString(2, entry.getKey());
                            stmtInsertItems.setInt(3, entry.getValue());
                            stmtInsertItems.addBatch();
                        }
                        batchCount++;
                        count++;
                    }

                    if (batchCount > 0) {
                        stmtInsertItems.executeBatch(); // 残りのデータを処理
                    }
                }
            }
            conn.commit();

        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback(); // ロールバック
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try {
                    conn.close(); // コネクションを閉じる
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // 指定された施設のグループに属するチェストのアイテムを登録
    public void processAndSaveItemsForGroup(String facilityName, String groupName, CommandSender sender) {

        Connection conn = null;
        int batchCount = 0;
        int count = 0;
        try {
            conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
            conn.setAutoCommit(false);

            // ItemTableの該当行の削除
            String sqlChestids = "SELECT chest_id FROM Chests";
            try (PreparedStatement stmtChests = conn.prepareStatement(sqlChestids)) {
                ResultSet rs = stmtChests.executeQuery();
                String sqlDeleteItems = "DELETE FROM ItemTable WHERE chest_id = ?";
                try (PreparedStatement stmtDeleteItems = conn.prepareStatement(sqlDeleteItems)) {
                    while (rs.next()) {
                        int chestId = rs.getInt("chest_id");
                        stmtDeleteItems.setInt(1, chestId);
                        stmtDeleteItems.addBatch();
                    }
                    stmtDeleteItems.executeBatch();
                }
            }

            String sqlSelectChests = "SELECT c.chest_id, c.world, c.x, c.y, c.z FROM Chests c INNER JOIN ChestFacilityGroups g ON c.chest_id = g.chest_id WHERE g.facility_name = ? AND g.group_name = ?";
            try (PreparedStatement stmtSelectChests = conn.prepareStatement(sqlSelectChests)) {
                stmtSelectChests.setString(1, facilityName);
                stmtSelectChests.setString(2, groupName);
                ResultSet rs = stmtSelectChests.executeQuery();

                String sqlInsertItems = "INSERT INTO ItemTable (chest_id, item_id, count) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE count = VALUES(count)";
                try (PreparedStatement stmtInsertItems = conn.prepareStatement(sqlInsertItems)) {
                    while (rs.next()) {
                        if (batchCount >= batchSize) {
                            stmtInsertItems.executeBatch();
                            conn.commit(); // バッチサイズに達したらコミット
                            batchCount = 0;
                        }

                        int chestId = rs.getInt("chest_id");
                        World world = Bukkit.getWorld(rs.getString("world"));
                        Location location = new Location(world, rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
                        HashMap<String, Integer> itemCounts = getContentsOfChest(location);
                        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
                            stmtInsertItems.setInt(1, chestId);
                            stmtInsertItems.setString(2, entry.getKey());
                            stmtInsertItems.setInt(3, entry.getValue());
                            stmtInsertItems.addBatch();
                        }
                        batchCount++;
                        count++;
                    }
                    if (batchCount > 0) {
                        stmtInsertItems.executeBatch(); // 残りのデータを処理
                    }
                }
            }
            conn.commit();
            sender.sendMessage("施設名：" + facilityName + " グループ名：" + groupName + " に登録されていたチェストのうち、" + count + "個のチェストが更新されました。");

        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback(); // ロールバック
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try {
                    conn.close(); // コネクションを閉じる
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // 指定した位置のチェストのアイテム情報を更新
    public void updateItemTableForChest(Location location) {
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
            conn.setAutoCommit(false); // トランザクションの開始

            // Chest IDの取得
            int chestId = -1;
            String sqlGetChestIdFromLocation = "SELECT chest_id FROM Chests WHERE world = ? AND x = ? AND y = ? AND z = ?";
            try (PreparedStatement stmtFindChestId = conn.prepareStatement(sqlGetChestIdFromLocation)) {
                stmtFindChestId.setString(1, location.getWorld().getName());
                stmtFindChestId.setInt(2, location.getBlockX());
                stmtFindChestId.setInt(3, location.getBlockY());
                stmtFindChestId.setInt(4, location.getBlockZ());
                try (ResultSet rs = stmtFindChestId.executeQuery()) {
                    if (rs.next()) {
                        chestId = rs.getInt("chest_id");
                    }
                }
            }
            if (chestId == -1) return;

            // ItemTableの該当行の削除
            String sqlDeleteItems = "DELETE FROM ItemTable WHERE chest_id = ?";
            try (PreparedStatement stmtDelete = conn.prepareStatement(sqlDeleteItems)) {
                stmtDelete.setInt(1, chestId);
                stmtDelete.executeUpdate();
            }

            // アイテム情報の再登録
            String sqlInsertItems = "INSERT INTO ItemTable (chest_id, item_id, count) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE count = VALUES(count)";
            try (PreparedStatement stmtInsertItems = conn.prepareStatement(sqlInsertItems)) {
                HashMap<String, Integer> itemCounts = getContentsOfChest(location);

                for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
                    stmtInsertItems.setInt(1, chestId);
                    stmtInsertItems.setString(2, entry.getKey());
                    stmtInsertItems.setInt(3, entry.getValue());
                    stmtInsertItems.addBatch();
                }
                if (!itemCounts.isEmpty()) stmtInsertItems.executeBatch();
            }
            conn.commit(); // トランザクションをコミット

        } catch (SQLException e) {
            e.printStackTrace();
            if (conn != null) {
                try {
                    conn.rollback(); // エラーが発生した場合はロールバック
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
        } finally {
            if (conn != null) {
                try {
                    conn.close(); // コネクションを閉じる
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // 指定した位置のチェストに、どのアイテムがどのくらいあるか調べる
    private HashMap<String, Integer> getContentsOfChest(Location location) {
        HashMap<String, Integer> itemCounts = new HashMap<>();

        Block block = location.getBlock();
        Chest chest = (Chest) block.getState();
        Inventory inventory = chest.getInventory();
        org.bukkit.block.data.type.Chest.Type type = ((org.bukkit.block.data.type.Chest) block.getBlockData()).getType();

        if (type == org.bukkit.block.data.type.Chest.Type.RIGHT){
            for (int i = 0; i < inventory.getContents().length/2; i++) {
                ItemStack item = inventory.getContents()[i];
                if (item != null) {
                    if (item.getType() == Material.SHULKER_BOX) {
                        addItemCountsShulker(item, itemCounts);
                        continue;
                    }
                    else if (item.getType() == Material.ENCHANTED_BOOK) {
                        getEnchantmentInfo(item, itemCounts);
                        continue;
                    }
                    else if (item.getType() == Material.POTION || item.getType() == Material.SPLASH_POTION || item.getType() == Material.LINGERING_POTION) {
                        getPotionInfo(item, itemCounts);
                        continue;
                    }
                    else if (item.getType() == Material.TIPPED_ARROW) {
                        getTippedArrowInfo(item, itemCounts);
                        continue;
                    }
                    String itemId = item.getType().toString();
                    itemCounts.put(itemId, itemCounts.getOrDefault(itemId, 0) + item.getAmount());
                }
            }
        }
        else if (type == org.bukkit.block.data.type.Chest.Type.LEFT) {
            for (int i = inventory.getContents().length/2; i < inventory.getContents().length; i++) {
                ItemStack item = inventory.getContents()[i];
                if (item != null) {
                    if (item.getType() == Material.SHULKER_BOX) {
                        addItemCountsShulker(item, itemCounts);
                        continue;
                    }
                    else if (item.getType() == Material.ENCHANTED_BOOK) {
                        getEnchantmentInfo(item, itemCounts);
                        continue;
                    }
                    else if (item.getType() == Material.POTION || item.getType() == Material.SPLASH_POTION || item.getType() == Material.LINGERING_POTION) {
                        getPotionInfo(item, itemCounts);
                        continue;
                    }
                    else if (item.getType() == Material.TIPPED_ARROW) {
                        getTippedArrowInfo(item, itemCounts);
                        continue;
                    }
                    String itemId = item.getType().toString();
                    itemCounts.put(itemId, itemCounts.getOrDefault(itemId, 0) + item.getAmount());
                }
            }
        }
        else {
            for (int i = 0; i < inventory.getContents().length; i++) {
                ItemStack item = inventory.getContents()[i];
                if (item != null) {
                    if (item.getType() == Material.SHULKER_BOX) {
                        addItemCountsShulker(item, itemCounts);
                        continue;
                    }
                    else if (item.getType() == Material.ENCHANTED_BOOK) {
                        getEnchantmentInfo(item, itemCounts);
                        continue;
                    }
                    else if (item.getType() == Material.POTION || item.getType() == Material.SPLASH_POTION || item.getType() == Material.LINGERING_POTION) {
                        getPotionInfo(item, itemCounts);
                        continue;
                    }
                    else if (item.getType() == Material.TIPPED_ARROW) {
                        getTippedArrowInfo(item, itemCounts);
                        continue;
                    }
                    String itemId = item.getType().toString();
                    itemCounts.put(itemId, itemCounts.getOrDefault(itemId, 0) + item.getAmount());
                }
            }
        }
        return itemCounts;
    }

    // アイテムがシュルカーボックスであった場合は中身を登録。中身がなかった場合はシュルカーボックスを登録。
    private void addItemCountsShulker(ItemStack item, HashMap<String, Integer> itemCounts) {
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta) {
            BlockStateMeta blockStateMeta = (BlockStateMeta) meta;
            if (blockStateMeta.getBlockState() instanceof ShulkerBox) {
                ShulkerBox shulkerBox = (ShulkerBox) blockStateMeta.getBlockState();
                ItemStack[] contents = shulkerBox.getInventory().getContents();
                boolean isEmpty = true;
                for (ItemStack content : contents) {
                    if (content != null) {
                        isEmpty = false;
                        if (content.getType() == Material.ENCHANTED_BOOK) {
                            getEnchantmentInfo(content, itemCounts);
                            continue;
                        }
                        else if (content.getType() == Material.POTION || content.getType() == Material.SPLASH_POTION || content.getType() == Material.LINGERING_POTION) {
                            getPotionInfo(content, itemCounts);
                            continue;
                        }
                        else if (content.getType() == Material.TIPPED_ARROW) {
                            getTippedArrowInfo(content, itemCounts);
                            continue;
                        }
                        String itemId = content.getType().toString();
                        int count = content.getAmount();
                        itemCounts.put(itemId, itemCounts.getOrDefault(itemId, 0) + count);
                    }
                }
                if (isEmpty) {
                    String itemId = item.getType().toString();
                    itemCounts.put(itemId, itemCounts.getOrDefault(itemId, 0) + 1);
                }
            }
        }
    }

    // エンチャントの種類とレベルを調べる（エンチャントの本のIDはすべてENCHANTED_BOOKであるため）
    private void getEnchantmentInfo(ItemStack item, HashMap<String, Integer> itemCounts) {
        String info = "";

        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
        Map<Enchantment, Integer> enchants = meta.getStoredEnchants();
        if (enchants.size() == 1) {
            Map.Entry<Enchantment, Integer> enchant = enchants.entrySet().iterator().next();
            info = enchant.getKey().getKey().toString().substring(10) + "_" + enchant.getValue();
        } else if (enchants.size() > 1) {
            // 複数のエンチャントがある場合
            info = "several_enchants";
        }
        itemCounts.put(info, itemCounts.getOrDefault(info, 0) + 1);
    }

    private void getPotionInfo(ItemStack item, HashMap<String, Integer> itemCounts) {
        String info = "Unknown_Potion"; // デフォルト値を設定

        if (item.hasItemMeta() && item.getItemMeta() instanceof PotionMeta) {
            String prefix = "";
            if (item.getType() == Material.SPLASH_POTION) {
                prefix = "splash_";
            } else if (item.getType() == Material.LINGERING_POTION) {
                prefix = "lingering_";
            }

            PotionMeta meta = (PotionMeta) item.getItemMeta();
            PotionType potionType = meta.getBasePotionType();
            info = prefix + potionType;
        }

        itemCounts.put(info, itemCounts.getOrDefault(info, 0) + 1);
    }

    private void getTippedArrowInfo(ItemStack item, HashMap<String, Integer> itemCounts) {
        String info = "Unknown_Tipped_Arrow"; // デフォルト値を設定

        if (item.hasItemMeta() && item.getItemMeta() instanceof PotionMeta) {
            String prefix = "tipped_arrow_";
            PotionMeta meta = (PotionMeta) item.getItemMeta();

            // Tipped Arrowのポーションタイプを取得
            PotionType potionType = meta.getBasePotionType();
            info = prefix + potionType;
        }

        itemCounts.put(info, itemCounts.getOrDefault(info, 0) + item.getAmount());
    }


    // 指定したグループのグループ名を変更
    public void changeFacilityName(String oldFacilityName, String newFacilityName, CommandSender sender) {
        Connection conn = null;
        int affectedRows;
        try {
            conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
            conn.setAutoCommit(false); // トランザクションの開始

            String sql = "UPDATE ChestFacilityGroups SET facility_name = ? WHERE facility_name = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, newFacilityName);
                stmt.setString(2, oldFacilityName);
                affectedRows = stmt.executeUpdate();
            }
            conn.commit(); // トランザクションをコミット
            if (affectedRows == 0) sender.sendMessage("施設名：" + oldFacilityName + " は登録されていません。");
            else sender.sendMessage("施設名：" + oldFacilityName + " を\n施設名：" + newFacilityName + "\nに変更しました。");

        } catch (SQLException e) {
            e.printStackTrace();
            if (conn != null) {
                try {
                    conn.rollback(); // エラーが発生した場合はロールバック
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
        } finally {
            if (conn != null) {
                try {
                    conn.close(); // コネクションを閉じる
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // 指定したグループのグループ名を変更
    public void changeGroupName(String oldFacilityName, String oldGroupName, String newFacilityName, String newGroupName, CommandSender sender) {
        Connection conn = null;
        int affectedRows;
        try {
            conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
            conn.setAutoCommit(false); // トランザクションの開始

            String sql = "UPDATE ChestFacilityGroups SET facility_name = ?, group_name = ? WHERE facility_name = ? AND group_name = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, newFacilityName);
                stmt.setString(2, newGroupName);
                stmt.setString(3, oldFacilityName);
                stmt.setString(4, oldGroupName);
                affectedRows = stmt.executeUpdate();
            }
            conn.commit(); // トランザクションをコミット
            if (affectedRows == 0) sender.sendMessage("施設名：" + oldFacilityName + " グループ名：" + oldGroupName + " は登録されていません。");
            else sender.sendMessage("施設名：" + oldFacilityName + " グループ名：" + oldGroupName + " を\n施設名：" + newFacilityName + " グループ名：" + newGroupName + "\nに変更しました。");

        } catch (SQLException e) {
            e.printStackTrace();
            if (conn != null) {
                try {
                    conn.rollback(); // エラーが発生した場合はロールバック
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
        } finally {
            if (conn != null) {
                try {
                    conn.close(); // コネクションを閉じる
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // グループごとにチェストが何個登録されているかをそれぞれ表示
    public void displayChestGroupCounts(CommandSender sender) {
        String sql = "SELECT g.facility_name, g.group_name, COUNT(c.chest_id) AS chest_count " +
                "FROM ChestFacilityGroups g " +
                "JOIN Chests c ON g.chest_id = c.chest_id " +
                "GROUP BY g.facility_name, g.group_name";

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String facilityName = rs.getString("facility_name");
                String groupName = rs.getString("group_name");
                int chestCount = rs.getInt("chest_count");
                sender.sendMessage("施設名：" + facilityName + " グループ名: " + groupName + " チェストの個数: " + chestCount);
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // 指定したグループのチェストの位置情報を表示
    public void displayChestLocationsForGroup(String facilityName, String groupName, CommandSender sender) {
        String sql = "SELECT c.world, c.x, c.y, c.z " +
                "FROM ChestFacilityGroups g " +
                "JOIN Chests c ON g.chest_id = c.chest_id " +
                "WHERE g.facility_name = ? AND g.group_name = ?";

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, facilityName);
            stmt.setString(2, groupName);
            try (ResultSet rs = stmt.executeQuery()) {
                for (int i = 1; rs.next(); i++) {
                    String world = rs.getString("world");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int z = rs.getInt("z");
                    sender.sendMessage(i + " World: " + world + ", X: " + x + ", Y: " + y + ", Z: " + z);
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // 指定した施設のグループ名を表示
    public void displayGroupsForFacility(String facilityName, CommandSender sender) {
        String sql = "SELECT g.group_name FROM ChestFacilityGroups g" +
                "WHERE g.facility_name = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, facilityName);
            try (ResultSet rs = stmt.executeQuery()) {
                for (int i = 1; rs.next(); i++) {
                    String groupname = rs.getString("group_name");
                    sender.sendMessage(i + " " + groupname);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}