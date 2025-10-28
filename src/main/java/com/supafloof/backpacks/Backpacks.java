package com.supafloof.backpacks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Backpacks Plugin
 * 
 * Provides portable storage containers that players can carry and access anywhere.
 * Backpacks are represented as enchanted items (configurable material, default barrel)
 * and can hold either a single chest (27 slots) or double chest (54 slots) worth of items.
 * 
 * Features:
 * - Backpack items using bundle visual with enchanted glow
 * - Single chest (27 slots) or double chest (54 slots) storage
 * - Doubler item (paper) to upgrade backpack capacity from 27 to 54 slots
 * - Right-click backpack to open inventory GUI
 * - Right-click paper doubler onto backpack to upgrade
 * - Persistent storage using PersistentDataContainer
 * - Admin commands to give backpacks and doublers
 * - Tab completion for all commands
 * 
 * Commands:
 * - /backpack help - Display help menu
 * - /backpack reload - Reload configuration
 * - /backpack give backpack <player> - Give a backpack (27 slots)
 * - /backpack give doubler <player> - Give a capacity doubler paper
 * 
 * @author SupaFloof Games, LLC
 * @version 1.0.0
 */
public class Backpacks extends JavaPlugin implements Listener, TabCompleter {
    
    // Persistent data keys for backpack system
    private NamespacedKey backpackKey;
    private NamespacedKey backpackSizeKey;
    private NamespacedKey backpackUUIDKey;
    private NamespacedKey doublerKey;
    
    // Storage for backpack contents by UUID
    private Map<String, Map<Integer, ItemStack>> backpackStorage = new HashMap<>();
    
    // Active backpack inventories (player UUID -> backpack inventory)
    private Map<UUID, Inventory> activeBackpacks = new HashMap<>();
    
    // Track which backpack UUID each player has open
    private Map<UUID, String> openBackpackUUIDs = new HashMap<>();
    
    @Override
    public void onEnable() {
        // Display startup messages
        getServer().getConsoleSender().sendMessage(
            Component.text("[Backpacks] Backpacks Started!", NamedTextColor.GREEN)
        );
        getServer().getConsoleSender().sendMessage(
            Component.text("[Backpacks] By SupaFloof Games, LLC", NamedTextColor.LIGHT_PURPLE)
        );
        
        // Initialize persistent data keys
        backpackKey = new NamespacedKey(this, "backpack");
        backpackSizeKey = new NamespacedKey(this, "backpack_size");
        backpackUUIDKey = new NamespacedKey(this, "backpack_uuid");
        doublerKey = new NamespacedKey(this, "doubler");
        
        // Save default config
        saveDefaultConfig();
        
        // Load backpack storage
        loadBackpackStorage();
        
        // Register events and commands
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("backpack").setExecutor(this);
        getCommand("backpack").setTabCompleter(this);
        
        getLogger().info("Backpacks plugin enabled!");
    }
    
    /**
     * Gets the configured backpack material type
     * 
     * @return Material for backpack items
     */
    private Material getBackpackMaterial() {
        String materialName = getConfig().getString("backpack-item", "BARREL");
        try {
            Material mat = Material.valueOf(materialName.toUpperCase());
            return mat;
        } catch (IllegalArgumentException e) {
            getLogger().warning("Invalid backpack-item in config: " + materialName + ", using BARREL");
            return Material.BARREL;
        }
    }
    
    @Override
    public void onDisable() {
        // Save all open backpacks before shutdown
        for (Map.Entry<UUID, Inventory> entry : activeBackpacks.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                saveBackpackContents(player);
                player.closeInventory();
            }
        }
        activeBackpacks.clear();
        openBackpackUUIDs.clear();
        
        getLogger().info("Backpacks plugin disabled!");
    }
    
    /**
     * Creates a new backpack item (configured material with enchanted glow)
     * Default capacity is 27 slots (single chest)
     * 
     * @return ItemStack representing a backpack
     */
    private ItemStack createBackpack() {
        ItemStack backpack = new ItemStack(getBackpackMaterial());
        ItemMeta meta = backpack.getItemMeta();
        
        // Generate unique UUID for this backpack
        String backpackUUID = UUID.randomUUID().toString();
        
        // Set display name
        meta.displayName(Component.text("Backpack", NamedTextColor.GOLD, TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        
        // Set lore
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("A portable storage container", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Capacity: 27 slots", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Right-click to open", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        
        // Add enchanted glow
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        
        // Mark as backpack with NBT and assign UUID
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(backpackKey, PersistentDataType.BOOLEAN, true);
        pdc.set(backpackSizeKey, PersistentDataType.INTEGER, 27);
        pdc.set(backpackUUIDKey, PersistentDataType.STRING, backpackUUID);
        
        backpack.setItemMeta(meta);
        
        // Initialize empty storage for this backpack
        backpackStorage.put(backpackUUID, new HashMap<>());
        
        return backpack;
    }
    
    /**
     * Creates a capacity doubler item (paper)
     * Used to upgrade a backpack from 27 to 54 slots
     * 
     * @return ItemStack representing a capacity doubler
     */
    private ItemStack createDoubler() {
        ItemStack doubler = new ItemStack(Material.PAPER);
        ItemMeta meta = doubler.getItemMeta();
        
        // Set display name
        meta.displayName(Component.text("Backpack Capacity Doubler", NamedTextColor.AQUA, TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        
        // Set lore
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Doubles backpack storage capacity", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("27 slots → 54 slots", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Right-click onto a backpack to apply", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        
        // Add enchanted glow
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        
        // Mark as doubler with NBT
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(doublerKey, PersistentDataType.BOOLEAN, true);
        
        doubler.setItemMeta(meta);
        return doubler;
    }
    
    /**
     * Checks if an item is a backpack
     * 
     * @param item ItemStack to check
     * @return true if the item is a backpack
     */
    private boolean isBackpack(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(backpackKey, PersistentDataType.BOOLEAN);
    }
    
    /**
     * Checks if an item is a capacity doubler
     * 
     * @param item ItemStack to check
     * @return true if the item is a doubler
     */
    private boolean isDoubler(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(doublerKey, PersistentDataType.BOOLEAN);
    }
    
    /**
     * Gets the capacity of a backpack (27 or 54)
     * 
     * @param item Backpack ItemStack
     * @return Capacity in slots (27 or 54)
     */
    private int getBackpackCapacity(ItemStack item) {
        if (!isBackpack(item)) {
            return 0;
        }
        
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.getOrDefault(backpackSizeKey, PersistentDataType.INTEGER, 27);
    }
    
    /**
     * Gets the unique UUID of a backpack
     * 
     * @param item Backpack ItemStack
     * @return UUID string, or null if not a backpack
     */
    private String getBackpackUUID(ItemStack item) {
        if (!isBackpack(item)) {
            return null;
        }
        
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.get(backpackUUIDKey, PersistentDataType.STRING);
    }
    
    /**
     * Upgrades a backpack's capacity from 27 to 54 slots
     * 
     * @param backpack Backpack ItemStack to upgrade
     * @return Updated backpack ItemStack
     */
    private ItemStack upgradeBackpack(ItemStack backpack) {
        ItemMeta meta = backpack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        
        // Set new capacity (UUID remains the same)
        pdc.set(backpackSizeKey, PersistentDataType.INTEGER, 54);
        
        // Update lore
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("A portable storage container", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Capacity: 54 slots", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("✦ UPGRADED ✦", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Right-click to open", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        
        backpack.setItemMeta(meta);
        return backpack;
    }
    
    /**
     * Opens a backpack inventory GUI for a player
     * 
     * @param player Player opening the backpack
     * @param backpack Backpack ItemStack being opened
     */
    private void openBackpack(Player player, ItemStack backpack) {
        String backpackUUID = getBackpackUUID(backpack);
        if (backpackUUID == null) {
            player.sendMessage(Component.text("Error: Invalid backpack!", NamedTextColor.RED));
            return;
        }
        
        int capacity = getBackpackCapacity(backpack);
        
        // Create inventory
        String title = "Backpack (" + capacity + " slots)";
        Inventory inv = Bukkit.createInventory(null, capacity, title);
        
        // Load contents from storage
        Map<Integer, ItemStack> contents = backpackStorage.get(backpackUUID);
        if (contents != null) {
            for (Map.Entry<Integer, ItemStack> entry : contents.entrySet()) {
                if (entry.getKey() < capacity) {
                    inv.setItem(entry.getKey(), entry.getValue());
                }
            }
        } else {
            // Initialize storage if it doesn't exist
            backpackStorage.put(backpackUUID, new HashMap<>());
        }
        
        // Track this backpack session
        activeBackpacks.put(player.getUniqueId(), inv);
        openBackpackUUIDs.put(player.getUniqueId(), backpackUUID);
        
        // Open for player
        player.openInventory(inv);
        player.sendMessage(Component.text("Opened backpack!", NamedTextColor.GREEN));
    }
    
    /**
     * Saves backpack contents to storage immediately
     * 
     * @param player Player closing the backpack
     */
    private void saveBackpackContents(Player player) {
        UUID playerId = player.getUniqueId();
        
        if (!activeBackpacks.containsKey(playerId) || !openBackpackUUIDs.containsKey(playerId)) {
            return;
        }
        
        Inventory inv = activeBackpacks.get(playerId);
        String backpackUUID = openBackpackUUIDs.get(playerId);
        
        // Save inventory contents to storage
        Map<Integer, ItemStack> contents = new HashMap<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                contents.put(i, item.clone());
            }
        }
        
        backpackStorage.put(backpackUUID, contents);
        
        // Save this specific backpack to file immediately
        saveBackpackToFile(backpackUUID, contents);
    }
    
    /**
     * Saves a single backpack to its own file in data/backpacks/
     * 
     * @param backpackUUID UUID of the backpack
     * @param contents Contents to save
     */
    private void saveBackpackToFile(String backpackUUID, Map<Integer, ItemStack> contents) {
        // Ensure data/backpacks folder exists
        File backpacksDir = new File(getDataFolder(), "data");
        if (!backpacksDir.exists()) {
            backpacksDir.mkdirs();
        }
        
        File backpackFile = new File(backpacksDir, backpackUUID + ".yml");
        org.bukkit.configuration.file.FileConfiguration config = 
            new org.bukkit.configuration.file.YamlConfiguration();
        
        for (Map.Entry<Integer, ItemStack> entry : contents.entrySet()) {
            config.set("slot." + entry.getKey(), entry.getValue());
        }
        
        try {
            config.save(backpackFile);
        } catch (Exception e) {
            getLogger().warning("Failed to save backpack " + backpackUUID + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Loads backpack storage from data/backpacks/ directory
     */
    private void loadBackpackStorage() {
        backpackStorage.clear();
        
        File backpacksDir = new File(getDataFolder(), "data");
        if (!backpacksDir.exists()) {
            getLogger().info("No data directory found, starting fresh");
            return;
        }
        
        File[] files = backpacksDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            getLogger().info("No backpack files found, starting fresh");
            return;
        }
        
        int loaded = 0;
        for (File file : files) {
            String uuid = file.getName().replace(".yml", "");
            
            org.bukkit.configuration.file.FileConfiguration config = 
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            
            Map<Integer, ItemStack> contents = new HashMap<>();
            
            if (config.contains("slot")) {
                org.bukkit.configuration.ConfigurationSection section = config.getConfigurationSection("slot");
                if (section != null) {
                    for (String slotStr : section.getKeys(false)) {
                        try {
                            int slot = Integer.parseInt(slotStr);
                            ItemStack item = section.getItemStack(slotStr);
                            if (item != null) {
                                contents.put(slot, item);
                            }
                        } catch (NumberFormatException e) {
                            getLogger().warning("Invalid slot number in backpack " + uuid + ": " + slotStr);
                        }
                    }
                }
            }
            
            backpackStorage.put(uuid, contents);
            loaded++;
        }
        
        getLogger().info("Loaded " + loaded + " backpacks from storage");
    }
    
    // ==================== EVENT HANDLERS ====================
    
    /**
     * Handles right-clicking with backpack
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        
        // Check if right-clicking
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        
        // Handle backpack opening
        if (isBackpack(item)) {
            event.setCancelled(true);
            openBackpack(player, item);
            return;
        }
    }
    
    /**
     * Handles inventory clicks - detects doubler being applied to backpack and prevents nested backpacks
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getWhoClicked();
        UUID playerId = player.getUniqueId();
        
        // Check if player is clicking with a doubler onto a backpack
        ItemStack cursor = event.getCursor();
        ItemStack clicked = event.getCurrentItem();
        
        if (isDoubler(cursor) && isBackpack(clicked)) {
            int currentCapacity = getBackpackCapacity(clicked);
            
            // Check if already upgraded
            if (currentCapacity >= 54) {
                player.sendMessage(Component.text("This backpack is already at maximum capacity!", NamedTextColor.RED));
                event.setCancelled(true);
                return;
            }
            
            // Upgrade the backpack
            ItemStack upgraded = upgradeBackpack(clicked);
            event.setCurrentItem(upgraded);
            
            // Consume the doubler
            if (cursor.getAmount() > 1) {
                cursor.setAmount(cursor.getAmount() - 1);
                event.setCursor(cursor);
            } else {
                event.setCursor(null);
            }
            
            // Send success messages
            player.sendMessage(Component.text("✦ Backpack upgraded to 54 slots! ✦", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
            
            event.setCancelled(true);
            return;
        }
        
        // Check if player has a backpack open
        if (!activeBackpacks.containsKey(playerId)) {
            return;
        }
        
        // Check if they're clicking in the backpack inventory
        if (event.getClickedInventory() == activeBackpacks.get(playerId)) {
            // Prevent placing backpacks inside backpacks (if config disallows)
            if (isBackpack(cursor) && !getConfig().getBoolean("allow-nested-backpacks", false)) {
                event.setCancelled(true);
                player.sendMessage(Component.text("You cannot put a backpack inside another backpack!", NamedTextColor.RED));
            }
        }
    }
    
    /**
     * Handles closing backpack inventory
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Check if this is a backpack inventory
        if (activeBackpacks.containsKey(playerId)) {
            // Save backpack contents
            saveBackpackContents(player);
            
            // Remove from active tracking
            activeBackpacks.remove(playerId);
            openBackpackUUIDs.remove(playerId);
            
            player.sendMessage(Component.text("Backpack saved!", NamedTextColor.GREEN));
        }
    }
    
    // ==================== COMMANDS ====================
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "give":
                return handleGive(sender, args);
            case "reload":
                return handleReload(sender);
            case "help":
                sendHelp(sender);
                return true;
            default:
                sendHelp(sender);
                return true;
        }
    }
    
    /**
     * Sends styled help message
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Backpacks Commands", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/backpack help", NamedTextColor.YELLOW)
            .append(Component.text(" - Show this help menu", NamedTextColor.GRAY)));
        
        if (sender.hasPermission("backpacks.give")) {
            sender.sendMessage(Component.text("/backpack give backpack <player>", NamedTextColor.YELLOW)
                .append(Component.text(" - Give a backpack", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/backpack give doubler <player>", NamedTextColor.YELLOW)
                .append(Component.text(" - Give a capacity doubler", NamedTextColor.GRAY)));
        }
        
        if (sender.hasPermission("backpacks.admin")) {
            sender.sendMessage(Component.text("/backpack reload", NamedTextColor.YELLOW)
                .append(Component.text(" - Reload configuration", NamedTextColor.GRAY)));
        }
        
        sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
    }
    
    /**
     * Handles /backpack give command
     */
    private boolean handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("backpacks.give")) {
            sender.sendMessage(Component.text("You don't have permission to give backpack items!", NamedTextColor.RED));
            return true;
        }
        
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /backpack give <backpack|doubler> <player>", NamedTextColor.RED));
            return true;
        }
        
        String itemType = args[1].toLowerCase();
        Player target = Bukkit.getPlayer(args[2]);
        
        if (target == null) {
            sender.sendMessage(Component.text("Player not found!", NamedTextColor.RED));
            return true;
        }
        
        switch (itemType) {
            case "backpack":
                target.getInventory().addItem(createBackpack());
                sender.sendMessage(Component.text("Gave backpack to ", NamedTextColor.GREEN)
                    .append(Component.text(target.getName(), NamedTextColor.GOLD)));
                target.sendMessage(Component.text("You received a backpack!", NamedTextColor.GREEN));
                break;
                
            case "doubler":
                target.getInventory().addItem(createDoubler());
                sender.sendMessage(Component.text("Gave capacity doubler to ", NamedTextColor.GREEN)
                    .append(Component.text(target.getName(), NamedTextColor.GOLD)));
                target.sendMessage(Component.text("You received a backpack capacity doubler!", NamedTextColor.GREEN));
                break;
                
            default:
                sender.sendMessage(Component.text("Invalid item type! Use 'backpack' or 'doubler'", NamedTextColor.RED));
                return true;
        }
        
        return true;
    }
    
    /**
     * Handles /backpack reload command
     */
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("backpacks.admin")) {
            sender.sendMessage(Component.text("You don't have permission to reload configuration!", NamedTextColor.RED));
            return true;
        }
        
        reloadConfig();
        sender.sendMessage(Component.text("Backpacks configuration reloaded!", NamedTextColor.GREEN));
        
        return true;
    }
    
    // ==================== TAB COMPLETION ====================
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.add("help");
            
            if (sender.hasPermission("backpacks.give")) {
                completions.add("give");
            }
            
            if (sender.hasPermission("backpacks.admin")) {
                completions.add("reload");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            completions.add("backpack");
            completions.add("doubler");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
        }
        
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
            .collect(Collectors.toList());
    }
}