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
    
    // ==================== PERSISTENT DATA KEYS ====================
    
    /**
     * NamespacedKey to identify items as backpacks via PersistentDataContainer.
     * Stored as BOOLEAN (true) on backpack items.
     * This is the primary identifier to determine if an item is a valid backpack.
     */
    private NamespacedKey backpackKey;
    
    /**
     * NamespacedKey to store backpack capacity (27 or 54 slots).
     * Stored as INTEGER on backpack items.
     * - 27 = Single chest capacity (default)
     * - 54 = Double chest capacity (upgraded with doubler)
     */
    private NamespacedKey backpackSizeKey;
    
    /**
     * NamespacedKey to store unique UUID for each backpack.
     * Stored as STRING on backpack items.
     * This UUID links the item to its persistent storage file and ensures
     * each backpack maintains its own independent inventory even if duplicated.
     */
    private NamespacedKey backpackUUIDKey;
    
    /**
     * NamespacedKey to identify capacity doubler items.
     * Stored as BOOLEAN (true) on doubler paper items.
     * Used to detect when a player is trying to upgrade a backpack.
     */
    private NamespacedKey doublerKey;
    
    // ==================== STORAGE SYSTEMS ====================
    
    /**
     * Main storage map for all backpack contents.
     * Structure: Map<BackpackUUID, Map<SlotIndex, ItemStack>>
     * 
     * Outer Map Key: String UUID of the backpack
     * Inner Map Key: Integer slot index (0-26 for 27 slots, 0-53 for 54 slots)
     * Inner Map Value: ItemStack stored in that slot
     * 
     * This is loaded from individual YAML files on startup and kept in memory.
     * Each backpack is saved to its own file: plugins/Backpacks/data/<UUID>.yml
     */
    private Map<String, Map<Integer, ItemStack>> backpackStorage = new HashMap<>();
    
    /**
     * Tracks currently open backpack inventories.
     * Structure: Map<PlayerUUID, Inventory>
     * 
     * Key: UUID of the player who has a backpack open
     * Value: The Bukkit Inventory object representing their open backpack GUI
     * 
     * This map is used to:
     * 1. Detect when inventory events occur in backpack GUIs
     * 2. Save contents when the inventory is closed
     * 3. Prevent nested backpacks if configured
     * 
     * Entries are added when a player opens a backpack and removed when they close it.
     */
    private Map<UUID, Inventory> activeBackpacks = new HashMap<>();
    
    /**
     * Links player sessions to specific backpack UUIDs.
     * Structure: Map<PlayerUUID, BackpackUUID>
     * 
     * Key: UUID of the player who has a backpack open
     * Value: String UUID of the backpack they currently have open
     * 
     * This is essential for the save system - when a player closes their inventory,
     * we need to know which backpack UUID to save the contents to.
     * 
     * Entries are synchronized with activeBackpacks map.
     */
    private Map<UUID, String> openBackpackUUIDs = new HashMap<>();
    
    // ==================== PLUGIN LIFECYCLE ====================
    
    /**
     * Called when the plugin is enabled during server startup or plugin load.
     * 
     * Initialization sequence:
     * 1. Display startup console messages (green for start, magenta for author)
     * 2. Initialize all NamespacedKey objects for NBT data
     * 3. Save default config.yml if it doesn't exist
     * 4. Load all backpack contents from data/ directory into memory
     * 5. Register event listeners for interactions and inventory management
     * 6. Register command executor and tab completer
     */
    @Override
    public void onEnable() {
        // Display startup messages to console
        // Green text indicates successful startup (consistent with SupaFloof standards)
        getServer().getConsoleSender().sendMessage(
            Component.text("[Backpacks] Backpacks Started!", NamedTextColor.GREEN)
        );
        // Magenta text displays author credit (consistent with SupaFloof standards)
        getServer().getConsoleSender().sendMessage(
            Component.text("[Backpacks] By SupaFloof Games, LLC", NamedTextColor.LIGHT_PURPLE)
        );
        
        // Initialize persistent data keys for NBT storage on items
        // These keys are namespaced to this plugin to avoid conflicts with other plugins
        backpackKey = new NamespacedKey(this, "backpack");
        backpackSizeKey = new NamespacedKey(this, "backpack_size");
        backpackUUIDKey = new NamespacedKey(this, "backpack_uuid");
        doublerKey = new NamespacedKey(this, "doubler");
        
        // Create default config.yml in plugin folder if it doesn't exist
        // This ensures server admins have a config file to customize
        saveDefaultConfig();
        
        // Load all backpack data from disk into memory
        // This reads all .yml files from plugins/Backpacks/data/
        loadBackpackStorage();
        
        // Register this class as an event listener for Bukkit events
        // Required for PlayerInteractEvent, InventoryClickEvent, InventoryCloseEvent
        getServer().getPluginManager().registerEvents(this, this);
        
        // Register command executor and tab completer for /backpack command
        // Both are handled by this class (implements Listener and TabCompleter)
        getCommand("backpack").setExecutor(this);
        getCommand("backpack").setTabCompleter(this);
        
        // Log successful enable to server console
        getLogger().info("Backpacks plugin enabled!");
    }
    
    /**
     * Retrieves the configured backpack material from config.yml.
     * 
     * Configuration key: "backpack-item"
     * Default value: "BARREL"
     * 
     * If the configured material name is invalid, falls back to BARREL
     * and logs a warning to console.
     * 
     * @return Material to use for backpack items
     */
    private Material getBackpackMaterial() {
        // Get material name from config, default to BARREL if not set
        String materialName = getConfig().getString("backpack-item", "BARREL");
        try {
            // Attempt to convert string to Material enum (case-insensitive)
            Material mat = Material.valueOf(materialName.toUpperCase());
            return mat;
        } catch (IllegalArgumentException e) {
            // If material name is invalid, log warning and use safe default
            getLogger().warning("Invalid backpack-item in config: " + materialName + ", using BARREL");
            return Material.BARREL;
        }
    }
    
    /**
     * Called when the plugin is disabled during server shutdown or plugin unload.
     * 
     * Cleanup sequence:
     * 1. Iterate through all players with open backpacks
     * 2. Save each open backpack's contents to disk
     * 3. Close all backpack inventories
     * 4. Clear tracking maps to free memory
     * 
     * This ensures no data loss when the server shuts down unexpectedly.
     */
    @Override
    public void onDisable() {
        // Save all currently open backpacks before shutdown
        for (Map.Entry<UUID, Inventory> entry : activeBackpacks.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                // Save the backpack contents to file
                saveBackpackContents(player);
                // Force close the inventory GUI
                player.closeInventory();
            }
        }
        
        // Clear tracking maps to free memory
        activeBackpacks.clear();
        openBackpackUUIDs.clear();
        
        // Log successful disable to server console
        getLogger().info("Backpacks plugin disabled!");
    }
    
    // ==================== ITEM CREATION ====================
    
    /**
     * Creates a new backpack item with default capacity (27 slots).
     * 
     * Creation process:
     * 1. Create ItemStack with configured material (default: BARREL)
     * 2. Generate unique UUID for this specific backpack instance
     * 3. Set display name: "Backpack" in gold, bold, no italics
     * 4. Set lore with description, capacity, and usage instructions
     * 5. Add UNBREAKING enchantment for visual glow effect
     * 6. Hide enchantment details from item tooltip
     * 7. Store NBT data: backpack marker, size (27), and UUID
     * 8. Initialize empty storage map for this UUID
     * 
     * The backpack UUID is critical - it links the physical item to its
     * persistent storage. Even if the item is duplicated, each keeps its
     * own UUID and therefore its own separate inventory.
     * 
     * @return ItemStack configured as a new backpack
     */
    private ItemStack createBackpack() {
        // Create base item using configured material
        ItemStack backpack = new ItemStack(getBackpackMaterial());
        ItemMeta meta = backpack.getItemMeta();
        
        // Generate unique identifier for this backpack
        // This UUID is stored in NBT and used as the key in storage files
        String backpackUUID = UUID.randomUUID().toString();
        
        // Set display name using Adventure API
        // Gold color for valuable appearance, bold for emphasis
        // Italic decoration explicitly disabled (default for custom items)
        meta.displayName(Component.text("Backpack", NamedTextColor.GOLD, TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        
        // Build lore (description lines shown in item tooltip)
        List<Component> lore = new ArrayList<>();
        // Line 1: General description in gray
        lore.add(Component.text("A portable storage container", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        // Line 2: Capacity information in yellow
        lore.add(Component.text("Capacity: 27 slots", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
        // Line 3: Empty line for spacing
        lore.add(Component.empty());
        // Line 4: Usage instructions in green
        lore.add(Component.text("Right-click to open", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        
        // Add visual enchanted glow effect
        // UNBREAKING level 1 adds glow without affecting gameplay
        // addEnchant with third parameter 'true' ignores level restrictions
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        // Hide the enchantment text from tooltip (only show glow effect)
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        
        // Access PersistentDataContainer for NBT storage on this item
        // This data persists through server restarts and inventory changes
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        
        // Mark this item as a backpack (used by isBackpack() method)
        pdc.set(backpackKey, PersistentDataType.BOOLEAN, true);
        
        // Store initial capacity (27 slots = single chest)
        pdc.set(backpackSizeKey, PersistentDataType.INTEGER, 27);
        
        // Store unique UUID that links this item to its storage
        pdc.set(backpackUUIDKey, PersistentDataType.STRING, backpackUUID);
        
        // Apply metadata changes to the ItemStack
        backpack.setItemMeta(meta);
        
        // Initialize empty storage map for this new backpack
        // The empty HashMap will be populated when items are added
        backpackStorage.put(backpackUUID, new HashMap<>());
        
        return backpack;
    }
    
    /**
     * Creates a capacity doubler item (paper with enchanted glow).
     * 
     * Creation process:
     * 1. Create PAPER ItemStack
     * 2. Set display name: "Backpack Capacity Doubler" in aqua, bold
     * 3. Set lore explaining the upgrade (27 → 54 slots)
     * 4. Add UNBREAKING enchantment for glow effect
     * 5. Hide enchantment tooltip
     * 6. Store NBT marker identifying this as a doubler
     * 
     * Usage: Right-click this item onto a backpack in any inventory
     * to upgrade it from 27 to 54 slots. The doubler is consumed.
     * 
     * @return ItemStack configured as a capacity doubler
     */
    private ItemStack createDoubler() {
        // Use paper as the base item (cheap, distinctive)
        ItemStack doubler = new ItemStack(Material.PAPER);
        ItemMeta meta = doubler.getItemMeta();
        
        // Set display name using Adventure API
        // Aqua color for utility item, bold for emphasis
        meta.displayName(Component.text("Backpack Capacity Doubler", NamedTextColor.AQUA, TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        
        // Build lore explaining the upgrade functionality
        List<Component> lore = new ArrayList<>();
        // Line 1: What it does (gray description text)
        lore.add(Component.text("Doubles backpack storage capacity", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        // Line 2: Specific upgrade details (yellow for important info)
        lore.add(Component.text("27 slots → 54 slots", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
        // Line 3: Empty line for spacing
        lore.add(Component.empty());
        // Line 4: Usage instructions (green for action)
        lore.add(Component.text("Right-click onto a backpack to apply", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        
        // Add visual enchanted glow effect (same technique as backpacks)
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        
        // Mark this item as a doubler via NBT
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(doublerKey, PersistentDataType.BOOLEAN, true);
        
        // Apply metadata changes
        doubler.setItemMeta(meta);
        return doubler;
    }
    
    // ==================== ITEM DETECTION ====================
    
    /**
     * Checks if an ItemStack is a valid backpack.
     * 
     * Detection process:
     * 1. Verify item is not null
     * 2. Verify item has metadata (required for NBT)
     * 3. Check if PersistentDataContainer has backpack marker key
     * 
     * This method is used throughout the plugin to identify backpack items
     * during right-click events, inventory clicks, and upgrade attempts.
     * 
     * @param item ItemStack to check
     * @return true if item is a backpack, false otherwise
     */
    private boolean isBackpack(ItemStack item) {
        // Null check prevents NullPointerException
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        
        // Access NBT data and check for backpack marker
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(backpackKey, PersistentDataType.BOOLEAN);
    }
    
    /**
     * Checks if an ItemStack is a capacity doubler.
     * 
     * Detection process:
     * 1. Verify item is not null
     * 2. Verify item has metadata
     * 3. Check if PersistentDataContainer has doubler marker key
     * 
     * Used in InventoryClickEvent to detect when a player is attempting
     * to upgrade a backpack by right-clicking the doubler onto it.
     * 
     * @param item ItemStack to check
     * @return true if item is a doubler, false otherwise
     */
    private boolean isDoubler(ItemStack item) {
        // Null check prevents NullPointerException
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        
        // Access NBT data and check for doubler marker
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(doublerKey, PersistentDataType.BOOLEAN);
    }
    
    /**
     * Retrieves the capacity (slot count) of a backpack.
     * 
     * Process:
     * 1. Verify item is a backpack
     * 2. Read backpack_size value from NBT
     * 3. Default to 27 if value is missing (safety fallback)
     * 
     * Valid return values:
     * - 27: Single chest capacity (default/unupgraded)
     * - 54: Double chest capacity (upgraded with doubler)
     * - 0: Not a backpack
     * 
     * @param item Backpack ItemStack to check
     * @return Capacity in slots (27, 54, or 0 if not a backpack)
     */
    private int getBackpackCapacity(ItemStack item) {
        // Return 0 if not a valid backpack
        if (!isBackpack(item)) {
            return 0;
        }
        
        // Read size from NBT, defaulting to 27 if missing
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.getOrDefault(backpackSizeKey, PersistentDataType.INTEGER, 27);
    }
    
    /**
     * Retrieves the unique UUID of a backpack.
     * 
     * This UUID is critical for the storage system - it links the physical
     * item to its persistent inventory data stored in the data/ directory.
     * 
     * Process:
     * 1. Verify item is a backpack
     * 2. Read UUID string from NBT
     * 
     * The UUID is generated when the backpack is created and never changes.
     * Even if the backpack item is duplicated, each keeps its own UUID.
     * 
     * @param item Backpack ItemStack
     * @return UUID string, or null if not a backpack or UUID missing
     */
    private String getBackpackUUID(ItemStack item) {
        // Return null if not a valid backpack
        if (!isBackpack(item)) {
            return null;
        }
        
        // Read UUID from NBT
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.get(backpackUUIDKey, PersistentDataType.STRING);
    }
    
    // ==================== BACKPACK OPERATIONS ====================
    
    /**
     * Upgrades a backpack's capacity from 27 slots to 54 slots.
     * 
     * Upgrade process:
     * 1. Modify backpack_size in NBT from 27 to 54
     * 2. Update lore to reflect new capacity
     * 3. Add "✦ UPGRADED ✦" badge to lore
     * 4. Preserve backpack UUID (maintains link to storage)
     * 
     * Important: The UUID remains unchanged, so the backpack retains
     * all existing items. When opened, it will create a 54-slot GUI
     * with the original 27 items in the first 27 slots.
     * 
     * This method does NOT consume the doubler or check permissions -
     * those checks are done in the InventoryClickEvent handler.
     * 
     * @param backpack Backpack ItemStack to upgrade
     * @return Modified ItemStack with 54-slot capacity
     */
    private ItemStack upgradeBackpack(ItemStack backpack) {
        // Get mutable metadata
        ItemMeta meta = backpack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        
        // Update capacity in NBT (UUID remains the same)
        pdc.set(backpackSizeKey, PersistentDataType.INTEGER, 54);
        
        // Build new lore reflecting upgraded status
        List<Component> lore = new ArrayList<>();
        // Same description line
        lore.add(Component.text("A portable storage container", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        // Updated capacity: 54 instead of 27
        lore.add(Component.text("Capacity: 54 slots", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
        // Special badge indicating upgraded status
        lore.add(Component.text("✦ UPGRADED ✦", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        // Empty line for spacing
        lore.add(Component.empty());
        // Usage instructions (unchanged)
        lore.add(Component.text("Right-click to open", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        
        // Apply changes to ItemStack
        backpack.setItemMeta(meta);
        return backpack;
    }
    
    /**
     * Opens a backpack's inventory GUI for a player.
     * 
     * Opening process:
     * 1. Retrieve backpack UUID from item NBT
     * 2. Validate UUID exists (error if missing)
     * 3. Get backpack capacity (27 or 54)
     * 4. Create Bukkit Inventory with appropriate size and title
     * 5. Load items from backpackStorage map into inventory
     * 6. Track session in activeBackpacks and openBackpackUUIDs
     * 7. Open inventory GUI for player
     * 8. Send confirmation message
     * 
     * The inventory title shows capacity: "Backpack (27 slots)" or "Backpack (54 slots)"
     * 
     * If the backpack's storage doesn't exist in the map (new backpack or
     * corrupt data), an empty map is initialized to prevent errors.
     * 
     * @param player Player opening the backpack
     * @param backpack Backpack ItemStack being opened
     */
    private void openBackpack(Player player, ItemStack backpack) {
        // Get the UUID that identifies this backpack's storage
        String backpackUUID = getBackpackUUID(backpack);
        if (backpackUUID == null) {
            // Should never happen unless NBT is corrupted
            player.sendMessage(Component.text("Error: Invalid backpack!", NamedTextColor.RED));
            return;
        }
        
        // Get capacity to determine inventory size
        int capacity = getBackpackCapacity(backpack);
        
        // Create Bukkit inventory GUI
        // First parameter: null = no InventoryHolder (custom inventory)
        // Second parameter: size (must be multiple of 9, max 54)
        // Third parameter: title shown at top of GUI
        String title = "Backpack (" + capacity + " slots)";
        Inventory inv = Bukkit.createInventory(null, capacity, title);
        
        // Load stored items into the inventory
        Map<Integer, ItemStack> contents = backpackStorage.get(backpackUUID);
        if (contents != null) {
            // Iterate through stored items and place them in correct slots
            for (Map.Entry<Integer, ItemStack> entry : contents.entrySet()) {
                // Safety check: only load items that fit in current capacity
                // This prevents issues if backpack was downgraded (shouldn't happen)
                if (entry.getKey() < capacity) {
                    inv.setItem(entry.getKey(), entry.getValue());
                }
            }
        } else {
            // Initialize empty storage if this UUID doesn't exist
            // This handles new backpacks or corrupted data gracefully
            backpackStorage.put(backpackUUID, new HashMap<>());
        }
        
        // Track this backpack session for save/event handling
        activeBackpacks.put(player.getUniqueId(), inv);
        openBackpackUUIDs.put(player.getUniqueId(), backpackUUID);
        
        // Open the GUI for the player
        player.openInventory(inv);
        
        // Send confirmation message
        player.sendMessage(Component.text("Opened backpack!", NamedTextColor.GREEN));
    }
    
    /**
     * Saves a player's currently open backpack to persistent storage.
     * 
     * Save process:
     * 1. Verify player has an open backpack (check tracking maps)
     * 2. Get the Inventory and backpack UUID from tracking maps
     * 3. Iterate through all slots in the inventory
     * 4. Clone non-empty ItemStacks into contents map
     * 5. Update backpackStorage map with new contents
     * 6. Write contents to disk immediately (no batching)
     * 
     * This method is called:
     * - When a player closes a backpack inventory (InventoryCloseEvent)
     * - During plugin shutdown (onDisable)
     * 
     * Important: Items are CLONED to prevent reference issues. The cloned
     * items in storage are independent of the inventory items.
     * 
     * Air/null items are not stored to keep the YAML files clean.
     * 
     * @param player Player whose backpack is being saved
     */
    private void saveBackpackContents(Player player) {
        UUID playerId = player.getUniqueId();
        
        // Verify this player has an open backpack
        if (!activeBackpacks.containsKey(playerId) || !openBackpackUUIDs.containsKey(playerId)) {
            return;
        }
        
        // Get the inventory GUI and backpack UUID
        Inventory inv = activeBackpacks.get(playerId);
        String backpackUUID = openBackpackUUIDs.get(playerId);
        
        // Build map of slot index -> ItemStack for storage
        Map<Integer, ItemStack> contents = new HashMap<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            // Only store non-empty slots
            if (item != null && item.getType() != Material.AIR) {
                // Clone the item to prevent reference issues
                // If we stored the original, changes to the inventory
                // would affect our storage map
                contents.put(i, item.clone());
            }
        }
        
        // Update in-memory storage
        backpackStorage.put(backpackUUID, contents);
        
        // Write to disk immediately (no batching/delayed saves)
        saveBackpackToFile(backpackUUID, contents);
    }
    
    /**
     * Saves a single backpack's contents to its YAML file.
     * 
     * File structure:
     * - Location: plugins/Backpacks/data/<UUID>.yml
     * - Format: YAML with "slot.<index>" keys
     * - Example:
     *   slot:
     *     0: <ItemStack>
     *     1: <ItemStack>
     *     5: <ItemStack>
     * 
     * Process:
     * 1. Ensure data/ directory exists (create if missing)
     * 2. Create FileConfiguration object
     * 3. Store each ItemStack at "slot.<index>" path
     * 4. Save to file with error handling
     * 
     * Each backpack has its own file to prevent data loss if one file
     * becomes corrupted. This also makes backup/recovery easier.
     * 
     * Bukkit's serialization handles ItemStack -> YAML conversion,
     * preserving NBT data, enchantments, lore, etc.
     * 
     * @param backpackUUID UUID of the backpack to save
     * @param contents Map of slot indices to ItemStacks
     */
    private void saveBackpackToFile(String backpackUUID, Map<Integer, ItemStack> contents) {
        // Ensure the data directory exists
        File backpacksDir = new File(getDataFolder(), "data");
        if (!backpacksDir.exists()) {
            // mkdirs() creates parent directories if needed
            backpacksDir.mkdirs();
        }
        
        // Create file path: plugins/Backpacks/data/<UUID>.yml
        File backpackFile = new File(backpacksDir, backpackUUID + ".yml");
        
        // Create YAML configuration object
        org.bukkit.configuration.file.FileConfiguration config = 
            new org.bukkit.configuration.file.YamlConfiguration();
        
        // Store each ItemStack at "slot.<index>" path
        // Bukkit handles ItemStack serialization automatically
        for (Map.Entry<Integer, ItemStack> entry : contents.entrySet()) {
            config.set("slot." + entry.getKey(), entry.getValue());
        }
        
        // Write configuration to disk
        try {
            config.save(backpackFile);
        } catch (Exception e) {
            // Log error but don't crash - data is still in memory
            getLogger().warning("Failed to save backpack " + backpackUUID + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Loads all backpack data from YAML files into memory.
     * 
     * Loading process:
     * 1. Clear existing backpackStorage map
     * 2. Check if data/ directory exists (skip if not)
     * 3. Find all .yml files in data/ directory
     * 4. For each file:
     *    a. Extract UUID from filename
     *    b. Load YAML configuration
     *    c. Parse "slot" section
     *    d. Deserialize ItemStacks
     *    e. Build contents map
     *    f. Store in backpackStorage map
     * 5. Log count of loaded backpacks
     * 
     * This method is called during onEnable(). If any file is corrupted,
     * it logs a warning but continues loading other files.
     * 
     * File naming: <UUID>.yml (e.g., "a1b2c3d4-e5f6-7890-abcd-ef1234567890.yml")
     * 
     * Empty slots are not stored in YAML, so the deserialized map may be
     * sparse (e.g., only slots 0, 5, 10 might have items).
     */
    private void loadBackpackStorage() {
        // Clear any existing data (important for reload scenarios)
        backpackStorage.clear();
        
        // Check if data directory exists
        File backpacksDir = new File(getDataFolder(), "data");
        if (!backpacksDir.exists()) {
            getLogger().info("No data directory found, starting fresh");
            return;
        }
        
        // Find all YAML files in the directory
        File[] files = backpacksDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            getLogger().info("No backpack files found, starting fresh");
            return;
        }
        
        // Counter for logging
        int loaded = 0;
        
        // Process each backpack file
        for (File file : files) {
            // Extract UUID from filename (remove .yml extension)
            String uuid = file.getName().replace(".yml", "");
            
            // Load YAML configuration from file
            org.bukkit.configuration.file.FileConfiguration config = 
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            
            // Prepare contents map for this backpack
            Map<Integer, ItemStack> contents = new HashMap<>();
            
            // Check if "slot" section exists in YAML
            if (config.contains("slot")) {
                // Get the configuration section containing all slots
                org.bukkit.configuration.ConfigurationSection section = config.getConfigurationSection("slot");
                if (section != null) {
                    // Iterate through each slot key
                    for (String slotStr : section.getKeys(false)) {
                        try {
                            // Parse slot index from string (e.g., "0", "5", "10")
                            int slot = Integer.parseInt(slotStr);
                            
                            // Deserialize ItemStack from YAML
                            // Bukkit handles all NBT, enchantments, lore automatically
                            ItemStack item = section.getItemStack(slotStr);
                            if (item != null) {
                                // Store in contents map
                                contents.put(slot, item);
                            }
                        } catch (NumberFormatException e) {
                            // Handle corrupted slot index gracefully
                            getLogger().warning("Invalid slot number in backpack " + uuid + ": " + slotStr);
                        }
                    }
                }
            }
            
            // Store this backpack's contents in main storage map
            backpackStorage.put(uuid, contents);
            loaded++;
        }
        
        // Log results to console
        getLogger().info("Loaded " + loaded + " backpacks from storage");
    }
    
    // ==================== EVENT HANDLERS ====================
    
    /**
     * Handles right-click interactions with backpacks.
     * 
     * Event: PlayerInteractEvent
     * Priority: HIGHEST (processed last, after other plugins)
     * 
     * Trigger conditions:
     * - Player right-clicks air OR block
     * - Player is holding a backpack item
     * 
     * Action:
     * 1. Check if action is right-click (air or block)
     * 2. Check if held item is a backpack
     * 3. Cancel event to prevent default behavior (e.g., block placement)
     * 4. Open backpack GUI for player
     * 
     * Using HIGHEST priority ensures other plugins can handle the event
     * first (e.g., protection plugins might cancel it).
     * 
     * The event is cancelled to prevent:
     * - Placing the backpack item as a block
     * - Opening container blocks behind where the player clicked
     * 
     * @param event PlayerInteractEvent to handle
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        
        // Check if this is a right-click action
        // Covers both right-click in air and right-click on blocks
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        
        // Handle backpack opening
        if (isBackpack(item)) {
            // Cancel event to prevent default behavior
            event.setCancelled(true);
            // Open the backpack GUI
            openBackpack(player, item);
            return;
        }
    }
    
    /**
     * Handles inventory clicks for backpack upgrade and nested backpack prevention.
     * 
     * Event: InventoryClickEvent
     * Priority: NORMAL (default)
     * 
     * Handles two scenarios:
     * 
     * 1. Doubler Application (any inventory):
     *    - Player has doubler on cursor
     *    - Clicks onto a backpack item
     *    - Validates backpack is not stacked (must be quantity 1)
     *    - Validates backpack is not already 54 slots
     *    - Upgrades backpack capacity
     *    - Consumes one doubler from cursor
     *    - Plays level-up sound and sends success message
     *    - Cancels event to prevent normal click behavior
     * 
     * 2. Nested Backpack Prevention (inside backpack GUI):
     *    - Player has open backpack
     *    - Clicks in the backpack inventory
     *    - Has backpack item on cursor
     *    - Config setting "allow-nested-backpacks" is false
     *    - Cancels event and sends error message
     * 
     * The doubler mechanic works in any inventory (player, chest, etc.)
     * This allows upgrading stored backpacks without taking them out.
     * 
     * @param event InventoryClickEvent to handle
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // Verify clicker is a player (not console/command block)
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getWhoClicked();
        UUID playerId = player.getUniqueId();
        
        // Get items involved in the click
        ItemStack cursor = event.getCursor();       // Item on player's cursor
        ItemStack clicked = event.getCurrentItem(); // Item in the clicked slot
        
        // ===== DOUBLER APPLICATION HANDLING =====
        // Check if player is clicking a doubler onto a backpack
        if (isDoubler(cursor) && isBackpack(clicked)) {
            // Validate backpack is not stacked
            // Stacked backpacks can cause duplication exploits
            if (clicked.getAmount() > 1) {
                player.sendMessage(Component.text("The doubler only works on a single backpack!", NamedTextColor.RED));
                event.setCancelled(true);
                return;
            }
            
            // Get current capacity of the backpack
            int currentCapacity = getBackpackCapacity(clicked);
            
            // Check if backpack is already at maximum capacity
            if (currentCapacity >= 54) {
                player.sendMessage(Component.text("This backpack is already at maximum capacity!", NamedTextColor.RED));
                event.setCancelled(true);
                return;
            }
            
            // Perform the upgrade
            ItemStack upgraded = upgradeBackpack(clicked);
            // Update the clicked slot with upgraded backpack
            event.setCurrentItem(upgraded);
            
            // Consume one doubler from cursor
            if (cursor.getAmount() > 1) {
                // Multiple doublers: reduce stack by 1
                cursor.setAmount(cursor.getAmount() - 1);
                event.setCursor(cursor);
            } else {
                // Single doubler: remove from cursor
                event.setCursor(null);
            }
            
            // Send success feedback
            player.sendMessage(Component.text("✦ Backpack upgraded to 54 slots! ✦", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
            // Play level-up sound at player's location
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
            
            // Cancel event to prevent normal click behavior
            event.setCancelled(true);
            return;
        }
        
        // ===== NESTED BACKPACK PREVENTION =====
        // Check if player has a backpack open
        if (!activeBackpacks.containsKey(playerId)) {
            // Player doesn't have backpack open, no restrictions
            return;
        }
        
        // Check if clicking in the backpack inventory (not player inventory)
        if (event.getClickedInventory() == activeBackpacks.get(playerId)) {
            // Check if trying to place a backpack inside a backpack
            if (isBackpack(cursor) && !getConfig().getBoolean("allow-nested-backpacks", false)) {
                // Cancel and notify player
                event.setCancelled(true);
                player.sendMessage(Component.text("You cannot put a backpack inside another backpack!", NamedTextColor.RED));
            }
        }
    }
    
    /**
     * Handles closing of backpack inventories.
     * 
     * Event: InventoryCloseEvent
     * Priority: NORMAL (default)
     * 
     * Process:
     * 1. Verify closer is a player
     * 2. Check if player has an active backpack
     * 3. Save backpack contents to disk
     * 4. Remove from tracking maps
     * 5. Send confirmation message
     * 
     * This ensures all changes are saved immediately when the GUI closes.
     * No data is lost if the player logs out or the server crashes after
     * closing the backpack.
     * 
     * The tracking maps are cleaned up to prevent memory leaks and allow
     * the player to open another backpack.
     * 
     * @param event InventoryCloseEvent to handle
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Verify closer is a player
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Check if this is a backpack inventory (not a chest, furnace, etc.)
        if (activeBackpacks.containsKey(playerId)) {
            // Save backpack contents to disk immediately
            saveBackpackContents(player);
            
            // Remove from tracking maps
            activeBackpacks.remove(playerId);
            openBackpackUUIDs.remove(playerId);
            
            // Send confirmation message
            player.sendMessage(Component.text("Backpack saved!", NamedTextColor.GREEN));
        }
    }
    
    // ==================== COMMANDS ====================
    
    /**
     * Handles /backpack command execution.
     * 
     * Command structure:
     * - /backpack help - Show help menu
     * - /backpack give <type> <player> - Give backpack or doubler
     * - /backpack reload - Reload configuration
     * 
     * Routing logic:
     * - No arguments → Show help
     * - "give" → Route to handleGive()
     * - "reload" → Route to handleReload()
     * - "help" → Show help
     * - Unknown argument → Show help
     * 
     * Permission checks are done in the sub-handlers, not here.
     * 
     * @param sender CommandSender (player or console)
     * @param command Command object (contains name, aliases, etc.)
     * @param label Alias used to execute command
     * @param args Command arguments (split by spaces)
     * @return true if command was handled, false if usage should be shown
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // No arguments provided - show help menu
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        
        // Route to appropriate handler based on first argument
        switch (args[0].toLowerCase()) {
            case "give":
                return handleGive(sender, args);
            case "reload":
                return handleReload(sender);
            case "help":
                sendHelp(sender);
                return true;
            default:
                // Unknown subcommand - show help
                sendHelp(sender);
                return true;
        }
    }
    
    /**
     * Sends formatted help menu to command sender.
     * 
     * Display format:
     * - Gold decorative border (top and bottom)
     * - Bold gold title
     * - Yellow command text + gray description
     * - Permission-based visibility (only shows accessible commands)
     * 
     * Permissions checked:
     * - backpacks.give: Shows give commands
     * - backpacks.admin: Shows reload command
     * - No permission: Only shows help command
     * 
     * Uses Adventure API for colored text formatting.
     * 
     * @param sender CommandSender to receive help menu
     */
    private void sendHelp(CommandSender sender) {
        // Top border
        sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
        
        // Title
        sender.sendMessage(Component.text("Backpacks Commands", NamedTextColor.GOLD, TextDecoration.BOLD));
        
        // Separator border
        sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
        
        // Help command (always visible)
        sender.sendMessage(Component.text("/backpack help", NamedTextColor.YELLOW)
            .append(Component.text(" - Show this help menu", NamedTextColor.GRAY)));
        
        // Give commands (requires backpacks.give permission)
        if (sender.hasPermission("backpacks.give")) {
            sender.sendMessage(Component.text("/backpack give backpack <player>", NamedTextColor.YELLOW)
                .append(Component.text(" - Give a backpack", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/backpack give doubler <player>", NamedTextColor.YELLOW)
                .append(Component.text(" - Give a capacity doubler", NamedTextColor.GRAY)));
        }
        
        // Reload command (requires backpacks.admin permission)
        if (sender.hasPermission("backpacks.admin")) {
            sender.sendMessage(Component.text("/backpack reload", NamedTextColor.YELLOW)
                .append(Component.text(" - Reload configuration", NamedTextColor.GRAY)));
        }
        
        // Bottom border
        sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
    }
    
    /**
     * Handles /backpack give command.
     * 
     * Syntax: /backpack give <backpack|doubler> <player>
     * Permission: backpacks.give
     * 
     * Process:
     * 1. Check permission (backpacks.give)
     * 2. Validate argument count (need 3: give, type, player)
     * 3. Parse item type (backpack or doubler)
     * 4. Find target player (must be online)
     * 5. Create appropriate item
     * 6. Add to target's inventory
     * 7. Send confirmation to sender and target
     * 
     * Error cases:
     * - No permission → Red error message
     * - Wrong argument count → Red usage message
     * - Player not found → Red error message
     * - Invalid item type → Red error message
     * 
     * Success feedback:
     * - Sender: Green message with player name in gold
     * - Target: Green message about item received
     * 
     * @param sender CommandSender executing the command
     * @param args Command arguments (includes "give")
     * @return true (command was handled)
     */
    private boolean handleGive(CommandSender sender, String[] args) {
        // Check permission
        if (!sender.hasPermission("backpacks.give")) {
            sender.sendMessage(Component.text("You don't have permission to give backpack items!", NamedTextColor.RED));
            return true;
        }
        
        // Validate argument count
        // args[0] = "give", args[1] = item type, args[2] = player name
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /backpack give <backpack|doubler> <player>", NamedTextColor.RED));
            return true;
        }
        
        // Parse arguments
        String itemType = args[1].toLowerCase();
        Player target = Bukkit.getPlayer(args[2]);
        
        // Validate target player exists and is online
        if (target == null) {
            sender.sendMessage(Component.text("Player not found!", NamedTextColor.RED));
            return true;
        }
        
        // Handle different item types
        switch (itemType) {
            case "backpack":
                // Create and give backpack
                target.getInventory().addItem(createBackpack());
                
                // Notify sender
                sender.sendMessage(Component.text("Gave backpack to ", NamedTextColor.GREEN)
                    .append(Component.text(target.getName(), NamedTextColor.GOLD)));
                
                // Notify target
                target.sendMessage(Component.text("You received a backpack!", NamedTextColor.GREEN));
                break;
                
            case "doubler":
                // Create and give doubler
                target.getInventory().addItem(createDoubler());
                
                // Notify sender
                sender.sendMessage(Component.text("Gave capacity doubler to ", NamedTextColor.GREEN)
                    .append(Component.text(target.getName(), NamedTextColor.GOLD)));
                
                // Notify target
                target.sendMessage(Component.text("You received a backpack capacity doubler!", NamedTextColor.GREEN));
                break;
                
            default:
                // Invalid item type
                sender.sendMessage(Component.text("Invalid item type! Use 'backpack' or 'doubler'", NamedTextColor.RED));
                return true;
        }
        
        return true;
    }
    
    /**
     * Handles /backpack reload command.
     * 
     * Syntax: /backpack reload
     * Permission: backpacks.admin
     * 
     * Process:
     * 1. Check permission (backpacks.admin)
     * 2. Reload configuration from config.yml
     * 3. Send confirmation message
     * 
     * What gets reloaded:
     * - config.yml settings (backpack-item, allow-nested-backpacks)
     * 
     * What does NOT get reloaded:
     * - Backpack storage data (would require closing all open backpacks)
     * - NamespacedKeys (immutable after initialization)
     * - Active backpack sessions
     * 
     * This command is safe to use while players have backpacks open.
     * It only affects new backpacks and configuration checks.
     * 
     * @param sender CommandSender executing the command
     * @return true (command was handled)
     */
    private boolean handleReload(CommandSender sender) {
        // Check permission
        if (!sender.hasPermission("backpacks.admin")) {
            sender.sendMessage(Component.text("You don't have permission to reload configuration!", NamedTextColor.RED));
            return true;
        }
        
        // Reload configuration from disk
        reloadConfig();
        
        // Send confirmation
        sender.sendMessage(Component.text("Backpacks configuration reloaded!", NamedTextColor.GREEN));
        
        return true;
    }
    
    // ==================== TAB COMPLETION ====================
    
    /**
     * Provides tab completion suggestions for /backpack command.
     * 
     * Completion logic by argument position:
     * 
     * Position 1 (subcommand):
     * - Always: "help"
     * - If has backpacks.give: "give"
     * - If has backpacks.admin: "reload"
     * 
     * Position 2 (after "give"):
     * - "backpack"
     * - "doubler"
     * 
     * Position 3 (after "give <type>"):
     * - All online player names
     * 
     * Results are filtered by partial input:
     * - Typing "/backpack g" → suggests "give"
     * - Typing "/backpack give b" → suggests "backpack"
     * - Typing "/backpack give backpack St" → suggests "Steve", "Steve2", etc.
     * 
     * Filtering is case-insensitive.
     * 
     * @param sender CommandSender typing the command
     * @param command Command object being completed
     * @param alias Alias used to execute command
     * @param args Current command arguments
     * @return List of completion suggestions (may be empty)
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        // First argument: subcommand
        if (args.length == 1) {
            // Always suggest help
            completions.add("help");
            
            // Suggest give if has permission
            if (sender.hasPermission("backpacks.give")) {
                completions.add("give");
            }
            
            // Suggest reload if has permission
            if (sender.hasPermission("backpacks.admin")) {
                completions.add("reload");
            }
        } 
        // Second argument after "give": item type
        else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            completions.add("backpack");
            completions.add("doubler");
        } 
        // Third argument after "give <type>": player name
        else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            // Return list of all online player names
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
        }
        
        // Filter suggestions by partial input (case-insensitive)
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
            .collect(Collectors.toList());
    }
}
