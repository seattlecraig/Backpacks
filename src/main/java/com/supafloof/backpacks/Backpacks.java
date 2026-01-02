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
 * Backpacks Plugin - Portable Storage Containers for Minecraft
 * 
 * <p>This plugin provides players with two types of portable storage:
 * <ul>
 *   <li><b>Personal Backpack:</b> A per-player storage accessible via the /bp command.
 *       Each player has exactly one personal backpack with 54 slots (double chest size).
 *       Requires the "backpacks.use" permission to access.</li>
 *   <li><b>Item-Based Backpacks:</b> Physical items that can be given to players, stored,
 *       traded, etc. These start with 27 slots (single chest) and can be upgraded to
 *       54 slots using a Capacity Doubler item. No permission required to use these.</li>
 * </ul>
 * 
 * <h2>Storage Architecture</h2>
 * <p>All backpack contents are persisted to individual YAML files in the 
 * plugins/Backpacks/playerdata/ directory. Each backpack (personal or item-based)
 * has a unique UUID that maps to its storage file. This design ensures:
 * <ul>
 *   <li>Data isolation - corruption in one file doesn't affect others</li>
 *   <li>Easy backup/restore of individual backpacks</li>
 *   <li>Immediate persistence on inventory close (no data loss on crash)</li>
 * </ul>
 * 
 * <h2>Item-Based Backpack Mechanics</h2>
 * <p>Item backpacks use PersistentDataContainer (NBT) to store:
 * <ul>
 *   <li>backpack marker (boolean) - identifies the item as a backpack</li>
 *   <li>backpack_size (integer) - capacity in slots (27 or 54)</li>
 *   <li>backpack_uuid (string) - unique identifier linking to storage file</li>
 * </ul>
 * 
 * <h2>Commands</h2>
 * <ul>
 *   <li>/bp - Open your personal backpack (54 slots, requires backpacks.use)</li>
 *   <li>/backpack help - Display the help menu</li>
 *   <li>/backpack reload - Reload configuration file (requires backpacks.admin)</li>
 *   <li>/backpack give backpack &lt;player&gt; - Give a 27-slot backpack item (requires backpacks.give)</li>
 *   <li>/backpack give doubler &lt;player&gt; - Give a capacity doubler paper (requires backpacks.give)</li>
 * </ul>
 * 
 * <h2>Permissions</h2>
 * <ul>
 *   <li>backpacks.use - Required for /bp personal backpack command</li>
 *   <li>backpacks.give - Required for /backpack give commands (admin distribution)</li>
 *   <li>backpacks.admin - Required for /backpack reload command</li>
 *   <li>No permission required - Using backpack items (right-click to open)</li>
 * </ul>
 * 
 * <h2>Configuration (config.yml)</h2>
 * <ul>
 *   <li>backpack-item: Material name for backpack items (default: BARREL)</li>
 *   <li>allow-nested-backpacks: Whether backpacks can be placed inside backpacks (default: false)</li>
 * </ul>
 * 
 * @author SupaFloof Games, LLC
 * @version 1.1.0
 * @since 1.0.0
 */
public class Backpacks extends JavaPlugin implements Listener, TabCompleter {
    
    // ==================== PERSISTENT DATA KEYS ====================
    // These NamespacedKey objects are used to store and retrieve NBT data on ItemStacks.
    // They are namespaced to this plugin ("backpacks") to avoid conflicts with other plugins.
    // All keys are initialized in onEnable() and remain constant for the plugin's lifetime.
    
    /**
     * NamespacedKey used to identify an ItemStack as a backpack item.
     * 
     * <p>Storage details:
     * <ul>
     *   <li>Data type: BOOLEAN</li>
     *   <li>Value: Always true when present</li>
     *   <li>Location: ItemStack's PersistentDataContainer</li>
     * </ul>
     * 
     * <p>This is the primary check used by {@link #isBackpack(ItemStack)} to determine
     * if an item should be treated as a backpack. The presence of this key (with value true)
     * indicates the item is a valid backpack, regardless of the item's material type.</p>
     */
    private NamespacedKey backpackKey;
    
    /**
     * NamespacedKey used to store the capacity (slot count) of a backpack.
     * 
     * <p>Storage details:
     * <ul>
     *   <li>Data type: INTEGER</li>
     *   <li>Valid values: 27 (single chest, default) or 54 (double chest, upgraded)</li>
     *   <li>Location: ItemStack's PersistentDataContainer</li>
     * </ul>
     * 
     * <p>This value determines the size of the inventory GUI created when the backpack
     * is opened. When a doubler is applied to a backpack, this value changes from 27 to 54.
     * The {@link #getBackpackCapacity(ItemStack)} method retrieves this value.</p>
     */
    private NamespacedKey backpackSizeKey;
    
    /**
     * NamespacedKey used to store the unique identifier for each backpack instance.
     * 
     * <p>Storage details:
     * <ul>
     *   <li>Data type: STRING</li>
     *   <li>Value: UUID string (e.g., "a1b2c3d4-e5f6-7890-abcd-ef1234567890")</li>
     *   <li>Location: ItemStack's PersistentDataContainer</li>
     * </ul>
     * 
     * <p>This UUID is the critical link between the physical backpack item and its
     * persistent storage file. The UUID is:
     * <ul>
     *   <li>Generated once when the backpack is created via {@link #createBackpack()}</li>
     *   <li>Never changed, even when the backpack is upgraded</li>
     *   <li>Used as the filename: plugins/Backpacks/playerdata/{UUID}.yml</li>
     *   <li>Used as the key in the {@link #backpackStorage} map</li>
     * </ul>
     * 
     * <p>Note: If a backpack item is duplicated (e.g., through exploits), both copies
     * share the same UUID and therefore the same inventory contents.</p>
     */
    private NamespacedKey backpackUUIDKey;
    
    /**
     * NamespacedKey used to identify an ItemStack as a capacity doubler item.
     * 
     * <p>Storage details:
     * <ul>
     *   <li>Data type: BOOLEAN</li>
     *   <li>Value: Always true when present</li>
     *   <li>Location: ItemStack's PersistentDataContainer (on Paper items)</li>
     * </ul>
     * 
     * <p>Doublers are special paper items that upgrade backpack capacity from 27 to 54 slots.
     * Usage: Right-click with a doubler on cursor onto a backpack item in any inventory.
     * The doubler is consumed and the backpack's capacity is permanently upgraded.</p>
     */
    private NamespacedKey doublerKey;
    
    // ==================== STORAGE SYSTEMS ====================
    // These maps manage backpack contents both in memory and linked to persistent storage.
    // The design prioritizes immediate persistence (save on close) over batched writes.
    
    /**
     * Primary storage map containing all backpack contents currently loaded in memory.
     * 
     * <p>Map structure:
     * <ul>
     *   <li><b>Outer Map Key:</b> String - The backpack's UUID</li>
     *   <li><b>Inner Map Key:</b> Integer - Slot index (0-26 for 27-slot, 0-53 for 54-slot)</li>
     *   <li><b>Inner Map Value:</b> ItemStack - The item stored in that slot (cloned)</li>
     * </ul>
     * 
     * <p>UUID formats:
     * <ul>
     *   <li>Item-based backpacks: Random UUID (e.g., "a1b2c3d4-e5f6-7890-abcd-ef1234567890")</li>
     *   <li>Personal backpacks: "personal-{PlayerUUID}" (e.g., "personal-12345678-abcd-...")</li>
     * </ul>
     * 
     * <p>Lifecycle:
     * <ul>
     *   <li>Populated during {@link #loadBackpackStorage()} at server startup</li>
     *   <li>Updated when backpacks are closed via {@link #saveBackpackContents(Player)}</li>
     *   <li>New entries created when backpacks are first created or opened</li>
     *   <li>Persisted to YAML files in plugins/Backpacks/playerdata/ directory</li>
     * </ul>
     * 
     * <p>Empty slots are NOT stored - only slots containing items appear in the inner map.
     * This keeps memory usage and YAML file sizes minimal.</p>
     */
    private Map<String, Map<Integer, ItemStack>> backpackStorage = new HashMap<>();
    
    /**
     * Tracks which backpack inventory GUIs are currently open, keyed by player UUID.
     * 
     * <p>Map structure:
     * <ul>
     *   <li><b>Key:</b> UUID - The player's unique identifier</li>
     *   <li><b>Value:</b> Inventory - The Bukkit Inventory object for their open backpack</li>
     * </ul>
     * 
     * <p>This map is used for:
     * <ul>
     *   <li>Detecting inventory events in backpack GUIs (not chests, furnaces, etc.)</li>
     *   <li>Enforcing the nested backpack restriction (if configured)</li>
     *   <li>Saving contents when the player closes the inventory</li>
     *   <li>Ensuring a player can only have one backpack open at a time</li>
     * </ul>
     * 
     * <p>Lifecycle:
     * <ul>
     *   <li>Entry added when player opens a backpack (item or personal)</li>
     *   <li>Entry removed when player closes the inventory</li>
     *   <li>All entries cleared during {@link #onDisable()} with forced saves</li>
     * </ul>
     */
    private Map<UUID, Inventory> activeBackpacks = new HashMap<>();
    
    /**
     * Links each player's current open backpack session to its storage UUID.
     * 
     * <p>Map structure:
     * <ul>
     *   <li><b>Key:</b> UUID - The player's unique identifier</li>
     *   <li><b>Value:</b> String - The backpack UUID (links to storage and file)</li>
     * </ul>
     * 
     * <p>This map is essential for the save system. When a player closes their inventory,
     * we need to know WHICH backpack UUID to save the contents under. The Inventory object
     * alone doesn't contain this information.</p>
     * 
     * <p>This map is always synchronized with {@link #activeBackpacks}:
     * <ul>
     *   <li>Both maps have entries added together when a backpack opens</li>
     *   <li>Both maps have entries removed together when a backpack closes</li>
     * </ul>
     */
    private Map<UUID, String> openBackpackUUIDs = new HashMap<>();
    
    /**
     * Prefix used to identify personal backpack storage entries.
     * 
     * <p>Personal backpacks are stored with keys in the format "personal-{PlayerUUID}".
     * This prefix distinguishes them from item-based backpacks which use random UUIDs.
     * Example: "personal-12345678-1234-1234-1234-123456789abc"</p>
     */
    private static final String PERSONAL_BACKPACK_PREFIX = "personal-";
    
    /**
     * The fixed capacity of personal backpacks in inventory slots.
     * 
     * <p>Personal backpacks accessed via /bp are always 54 slots (double chest size).
     * Unlike item-based backpacks, they cannot be upgraded or changed in size.</p>
     */
    private static final int PERSONAL_BACKPACK_SIZE = 54;
    
    // ==================== PLUGIN LIFECYCLE ====================
    
    /**
     * Called by Bukkit when the plugin is enabled (server startup or /reload).
     * 
     * <p>Initialization sequence:</p>
     * <ol>
     *   <li>Display startup banner to console (green text for visibility)</li>
     *   <li>Display author credit (magenta text, SupaFloof standard)</li>
     *   <li>Initialize all four NamespacedKey objects for NBT data storage</li>
     *   <li>Save default config.yml if the file doesn't exist</li>
     *   <li>Load all backpack contents from YAML files into memory</li>
     *   <li>Register this class as an event listener for inventory and interaction events</li>
     *   <li>Register command executor for /backpack command</li>
     *   <li>Register command executor for /bp command (if defined in plugin.yml)</li>
     *   <li>Register tab completer for both commands</li>
     *   <li>Log successful enable to server logger</li>
     * </ol>
     * 
     * <p>After this method completes, the plugin is fully operational and ready
     * to handle backpack interactions, commands, and inventory events.</p>
     */
    @Override
    public void onEnable() {
        // Display startup messages to console using Adventure API
        // Green text indicates successful plugin startup (SupaFloof standard formatting)
        getServer().getConsoleSender().sendMessage(
            Component.text("[Backpacks] Backpacks Started!", NamedTextColor.GREEN)
        );
        // Magenta/light purple text displays author credit (SupaFloof standard formatting)
        getServer().getConsoleSender().sendMessage(
            Component.text("[Backpacks] By SupaFloof Games, LLC", NamedTextColor.LIGHT_PURPLE)
        );
        
        // Initialize NamespacedKey objects for PersistentDataContainer usage
        // These keys are namespaced to "backpacks" (this plugin's name) to prevent
        // collisions with other plugins that might use similar key names
        backpackKey = new NamespacedKey(this, "backpack");           // Identifies item as backpack
        backpackSizeKey = new NamespacedKey(this, "backpack_size"); // Stores capacity (27 or 54)
        backpackUUIDKey = new NamespacedKey(this, "backpack_uuid"); // Links item to storage
        doublerKey = new NamespacedKey(this, "doubler");             // Identifies doubler items
        
        // Save the default config.yml from the JAR to the plugin folder if it doesn't exist
        // This ensures admins have a config file to customize even on fresh installations
        saveDefaultConfig();
        
        // Load all existing backpack data from YAML files in playerdata/ directory
        // This populates the backpackStorage map with all previously saved contents
        loadBackpackStorage();
        
        // Register this class as an event listener with Bukkit's plugin manager
        // This enables the @EventHandler methods: onPlayerInteract, onInventoryClick, onInventoryClose
        getServer().getPluginManager().registerEvents(this, this);
        
        // Register command handlers for /backpack command (defined in plugin.yml)
        // setExecutor: handles command execution via onCommand()
        // setTabCompleter: handles tab completion via onTabComplete()
        getCommand("backpack").setExecutor(this);
        getCommand("backpack").setTabCompleter(this);
        
        // Register command handlers for /bp shorthand command (if defined in plugin.yml)
        // The null check is a safety measure in case /bp isn't defined
        if (getCommand("bp") != null) {
            getCommand("bp").setExecutor(this);
            getCommand("bp").setTabCompleter(this);
        }
        
        // Log successful enable using the plugin's logger (includes [Backpacks] prefix)
        getLogger().info("Backpacks plugin enabled!");
    }
    
    /**
     * Retrieves the configured material type for backpack items from config.yml.
     * 
     * <p>Configuration: "backpack-item" in config.yml
     * <br>Default value: "BARREL"
     * <br>Valid values: Any Bukkit Material enum name (case-insensitive)</p>
     * 
     * <p>This method provides fallback behavior if the configured material is invalid:
     * <ol>
     *   <li>Attempt to parse the configured string as a Material enum</li>
     *   <li>If parsing fails (IllegalArgumentException), log a warning</li>
     *   <li>Return BARREL as the safe default material</li>
     * </ol>
     * 
     * <p>Note: The material only affects newly created backpacks. Existing backpack
     * items keep their original material - they're identified by NBT data, not material type.</p>
     * 
     * @return The Material to use when creating new backpack items (never null)
     */
    private Material getBackpackMaterial() {
        // Get the material name from config, using "BARREL" as default if not set
        String materialName = getConfig().getString("backpack-item", "BARREL");
        try {
            // Attempt to convert the string to a Material enum value
            // toUpperCase() ensures case-insensitive matching (e.g., "barrel" → BARREL)
            Material mat = Material.valueOf(materialName.toUpperCase());
            return mat;
        } catch (IllegalArgumentException e) {
            // The configured value doesn't match any Material enum constant
            // Log a warning so the admin knows their config has an issue
            getLogger().warning("Invalid backpack-item in config: " + materialName + ", using BARREL");
            // Return BARREL as a safe, sensible default (it's a storage-themed item)
            return Material.BARREL;
        }
    }
    
    /**
     * Called by Bukkit when the plugin is disabled (server shutdown, /reload, or plugin unload).
     * 
     * <p>Cleanup sequence:</p>
     * <ol>
     *   <li>Iterate through all players with open backpacks (activeBackpacks map)</li>
     *   <li>For each player:
     *     <ul>
     *       <li>Save their backpack contents to disk immediately</li>
     *       <li>Force-close their inventory GUI</li>
     *     </ul>
     *   </li>
     *   <li>Clear activeBackpacks map to release Inventory references</li>
     *   <li>Clear openBackpackUUIDs map to release string references</li>
     *   <li>Log successful disable to server logger</li>
     * </ol>
     * 
     * <p>This ensures no data loss during server shutdowns, even if players have
     * backpacks open at the time. The forced inventory close triggers no additional
     * save because we save explicitly before closing.</p>
     * 
     * <p>Note: The InventoryCloseEvent will still fire when we close inventories,
     * but saveBackpackContents() checks the tracking maps which we clear afterward,
     * so no duplicate saves occur.</p>
     */
    @Override
    public void onDisable() {
        // Iterate through all currently open backpacks and save/close them
        // This prevents data loss if the server shuts down while players have backpacks open
        for (Map.Entry<UUID, Inventory> entry : activeBackpacks.entrySet()) {
            // Get the Player object from their UUID (may be null if they disconnected)
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                // Save the current contents of their open backpack to disk
                saveBackpackContents(player);
                // Force-close the inventory GUI (player will see it close)
                player.closeInventory();
            }
        }
        
        // Clear tracking maps to release memory and object references
        // This is good practice even though the plugin is being disabled
        activeBackpacks.clear();
        openBackpackUUIDs.clear();
        
        // Log successful disable using the plugin's logger
        getLogger().info("Backpacks plugin disabled!");
    }
    
    // ==================== ITEM CREATION ====================
    // These methods create the special items used by the plugin: backpacks and doublers.
    // Both use PersistentDataContainer to store identifying NBT data.
    
    /**
     * Creates a new backpack item with default 27-slot capacity.
     * 
     * <p>Item properties:</p>
     * <ul>
     *   <li><b>Material:</b> Configured via "backpack-item" in config.yml (default: BARREL)</li>
     *   <li><b>Display Name:</b> "Backpack" in gold, bold, no italics</li>
     *   <li><b>Lore:</b> Description, capacity (27 slots), and usage instructions</li>
     *   <li><b>Enchant Glow:</b> UNBREAKING I with hidden enchantment text</li>
     *   <li><b>Capacity:</b> 27 slots (single chest)</li>
     *   <li><b>UUID:</b> Randomly generated, unique to this backpack instance</li>
     * </ul>
     * 
     * <p>NBT data stored in PersistentDataContainer:</p>
     * <ul>
     *   <li>{@link #backpackKey}: true (BOOLEAN) - identifies as backpack</li>
     *   <li>{@link #backpackSizeKey}: 27 (INTEGER) - capacity in slots</li>
     *   <li>{@link #backpackUUIDKey}: generated UUID (STRING) - storage identifier</li>
     * </ul>
     * 
     * <p>Storage initialization:</p>
     * <p>An empty HashMap is created in {@link #backpackStorage} for this UUID.
     * This entry will be populated when items are added to the backpack.</p>
     * 
     * <p>The generated UUID is critical - it permanently links this physical item
     * to its storage. The UUID never changes, even when the backpack is upgraded.</p>
     * 
     * @return A new ItemStack configured as a 27-slot backpack, ready to be given to a player
     */
    private ItemStack createBackpack() {
        // Create the base ItemStack using the configured material (default: BARREL)
        ItemStack backpack = new ItemStack(getBackpackMaterial());
        // Get mutable ItemMeta to configure display properties and NBT data
        ItemMeta meta = backpack.getItemMeta();
        
        // Generate a unique UUID that will identify this specific backpack
        // This UUID links the physical item to its storage file and in-memory data
        // Format: standard UUID string "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
        String backpackUUID = UUID.randomUUID().toString();
        
        // Set the display name using Adventure API (Paper's modern text API)
        // Gold color makes it look valuable, bold emphasizes importance
        // Italic decoration explicitly set to false to prevent the default
        // italic styling that Minecraft applies to custom item names
        meta.displayName(Component.text("Backpack", NamedTextColor.GOLD, TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        
        // Build the lore (item description shown in tooltip when hovering)
        List<Component> lore = new ArrayList<>();
        // Line 1: General description explaining what the item is (gray = informational)
        lore.add(Component.text("A portable storage container", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        // Line 2: Capacity info so players know how much it holds (yellow = important stat)
        lore.add(Component.text("Capacity: 27 slots", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
        // Line 3: Empty component creates visual spacing in the tooltip
        lore.add(Component.empty());
        // Line 4: Usage instructions so players know how to use it (green = action)
        lore.add(Component.text("Right-click to open", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        
        // Add visual enchanted glow effect to make backpacks stand out in inventories
        // UNBREAKING level 1 is used because:
        // - It adds the enchant glow visual effect
        // - Level 1 has minimal gameplay impact if somehow visible
        // - Third parameter 'true' allows adding enchants that normally wouldn't apply
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        // Hide the enchantment text from the tooltip (we only want the glow, not "Unbreaking I")
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        
        // Access the PersistentDataContainer for storing custom NBT data
        // This data survives server restarts, item moves, and most operations
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        
        // Store the backpack marker - this is how isBackpack() identifies valid backpacks
        pdc.set(backpackKey, PersistentDataType.BOOLEAN, true);
        
        // Store the initial capacity (27 slots = single chest size)
        // This will be changed to 54 if a doubler is applied
        pdc.set(backpackSizeKey, PersistentDataType.INTEGER, 27);
        
        // Store the unique UUID that links this item to its storage
        // This is the critical identifier used for save/load operations
        pdc.set(backpackUUIDKey, PersistentDataType.STRING, backpackUUID);
        
        // Apply all metadata changes back to the ItemStack
        // Without this call, none of the above changes would take effect
        backpack.setItemMeta(meta);
        
        // Initialize empty storage entry for this new backpack
        // The empty HashMap will be populated when items are added to the backpack
        // This prevents null checks elsewhere and ensures save operations work immediately
        backpackStorage.put(backpackUUID, new HashMap<>());
        
        return backpack;
    }
    
    /**
     * Creates a capacity doubler item (enchanted paper that upgrades backpacks).
     * 
     * <p>Item properties:</p>
     * <ul>
     *   <li><b>Material:</b> PAPER (lightweight, distinctive appearance)</li>
     *   <li><b>Display Name:</b> "Backpack Capacity Doubler" in aqua, bold, no italics</li>
     *   <li><b>Lore:</b> Description of upgrade effect and usage instructions</li>
     *   <li><b>Enchant Glow:</b> UNBREAKING I with hidden enchantment text</li>
     * </ul>
     * 
     * <p>NBT data stored in PersistentDataContainer:</p>
     * <ul>
     *   <li>{@link #doublerKey}: true (BOOLEAN) - identifies as doubler</li>
     * </ul>
     * 
     * <p>Usage mechanics:</p>
     * <ol>
     *   <li>Player picks up doubler (on cursor)</li>
     *   <li>Player clicks onto a backpack item in any inventory</li>
     *   <li>If backpack is not already 54 slots, upgrade occurs</li>
     *   <li>One doubler is consumed from cursor</li>
     *   <li>Backpack's lore updates to show 54 slots and "✦ UPGRADED ✦" badge</li>
     * </ol>
     * 
     * <p>Unlike backpacks, doublers don't have UUIDs - they're consumable items
     * with no persistent state beyond being identified as doublers.</p>
     * 
     * @return A new ItemStack configured as a capacity doubler, ready to be given to a player
     */
    private ItemStack createDoubler() {
        // Use PAPER as the base material - it's cheap-looking but distinctive,
        // and makes sense thematically as an "upgrade scroll" or "expansion deed"
        ItemStack doubler = new ItemStack(Material.PAPER);
        // Get mutable ItemMeta for configuration
        ItemMeta meta = doubler.getItemMeta();
        
        // Set display name using Adventure API
        // Aqua color differentiates it from the gold backpack
        // Bold emphasizes it's a special item, italic disabled for clean appearance
        meta.displayName(Component.text("Backpack Capacity Doubler", NamedTextColor.AQUA, TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        
        // Build the lore explaining what this item does and how to use it
        List<Component> lore = new ArrayList<>();
        // Line 1: Brief description of the effect (gray = informational)
        lore.add(Component.text("Doubles backpack storage capacity", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        // Line 2: Specific numbers so players know the exact upgrade (yellow = important stat)
        lore.add(Component.text("27 slots → 54 slots", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
        // Line 3: Empty line for visual spacing
        lore.add(Component.empty());
        // Line 4: Clear usage instructions (green = action)
        lore.add(Component.text("Right-click onto a backpack to apply", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        
        // Add enchanted glow to make it stand out (same technique as backpacks)
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        
        // Mark this item as a doubler via NBT data
        // This is how isDoubler() identifies valid doubler items
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(doublerKey, PersistentDataType.BOOLEAN, true);
        
        // Apply metadata changes to the ItemStack
        doubler.setItemMeta(meta);
        return doubler;
    }
    
    // ==================== ITEM DETECTION ====================
    // These utility methods check NBT data to identify special items.
    // They're used throughout the plugin to validate items before operations.
    
    /**
     * Checks whether an ItemStack is a valid backpack item.
     * 
     * <p>Detection logic:</p>
     * <ol>
     *   <li>Null check: Return false if item is null</li>
     *   <li>Metadata check: Return false if item has no ItemMeta</li>
     *   <li>NBT check: Return true only if {@link #backpackKey} exists in PersistentDataContainer</li>
     * </ol>
     * 
     * <p>This method does NOT check:</p>
     * <ul>
     *   <li>The item's material (backpacks can be any material)</li>
     *   <li>The item's display name or lore</li>
     *   <li>Whether the backpack has a valid UUID</li>
     *   <li>Whether storage exists for this backpack</li>
     * </ul>
     * 
     * <p>The NBT marker is the authoritative identifier because display name
     * and material can be changed by anvils or plugins, but PersistentDataContainer
     * data is preserved through all normal item operations.</p>
     * 
     * @param item The ItemStack to check (may be null)
     * @return true if the item is a valid backpack, false otherwise (including if item is null)
     */
    private boolean isBackpack(ItemStack item) {
        // Null safety: both the item and its metadata must exist
        // Items without metadata can't have PersistentDataContainer entries
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        
        // Check if the backpack marker exists in the item's NBT data
        // We only check presence (has), not the actual value - if the key exists, it's a backpack
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(backpackKey, PersistentDataType.BOOLEAN);
    }
    
    /**
     * Checks whether an ItemStack is a valid capacity doubler item.
     * 
     * <p>Detection logic:</p>
     * <ol>
     *   <li>Null check: Return false if item is null</li>
     *   <li>Metadata check: Return false if item has no ItemMeta</li>
     *   <li>NBT check: Return true only if {@link #doublerKey} exists in PersistentDataContainer</li>
     * </ol>
     * 
     * <p>This method is used in the InventoryClickEvent handler to detect
     * when a player is attempting to upgrade a backpack by clicking a doubler onto it.</p>
     * 
     * @param item The ItemStack to check (may be null)
     * @return true if the item is a valid doubler, false otherwise (including if item is null)
     */
    private boolean isDoubler(ItemStack item) {
        // Null safety: both the item and its metadata must exist
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        
        // Check if the doubler marker exists in the item's NBT data
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(doublerKey, PersistentDataType.BOOLEAN);
    }
    
    /**
     * Retrieves the storage capacity (slot count) of a backpack item.
     * 
     * <p>Return values:</p>
     * <ul>
     *   <li><b>27:</b> Default/unupgraded backpack (single chest size)</li>
     *   <li><b>54:</b> Upgraded backpack (double chest size)</li>
     *   <li><b>0:</b> Item is not a backpack (failed isBackpack check)</li>
     * </ul>
     * 
     * <p>The capacity value determines the size of the Inventory GUI created
     * when the backpack is opened. Bukkit inventories must be multiples of 9,
     * and 54 is the maximum (6 rows × 9 columns = double chest).</p>
     * 
     * <p>If the backpack has the marker but no size key (corrupted data),
     * this method returns 27 as a safe default via getOrDefault().</p>
     * 
     * @param item The backpack ItemStack to check capacity for
     * @return Capacity in slots (27 or 54), or 0 if not a valid backpack
     */
    private int getBackpackCapacity(ItemStack item) {
        // First verify this is actually a backpack
        // Return 0 for non-backpacks so callers can detect invalid items
        if (!isBackpack(item)) {
            return 0;
        }
        
        // Read the capacity from NBT, defaulting to 27 if the key is missing
        // This provides backwards compatibility if backpacks exist without the size key
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.getOrDefault(backpackSizeKey, PersistentDataType.INTEGER, 27);
    }
    
    /**
     * Retrieves the unique identifier UUID of a backpack item.
     * 
     * <p>The UUID is used to:</p>
     * <ul>
     *   <li>Look up contents in the {@link #backpackStorage} map</li>
     *   <li>Determine the filename for persistent storage (UUID.yml)</li>
     *   <li>Link the physical item to its stored inventory contents</li>
     * </ul>
     * 
     * <p>Return values:</p>
     * <ul>
     *   <li>UUID string if backpack is valid and has UUID stored</li>
     *   <li>null if item is not a backpack or UUID is missing (corrupted data)</li>
     * </ul>
     * 
     * <p>The UUID is generated once when the backpack is created and never changes.
     * It survives all item operations including upgrades, stack splits, and renames.</p>
     * 
     * @param item The backpack ItemStack to get the UUID from
     * @return UUID string (e.g., "a1b2c3d4-e5f6-7890-abcd-ef1234567890"), or null if not a backpack
     */
    private String getBackpackUUID(ItemStack item) {
        // First verify this is actually a backpack
        if (!isBackpack(item)) {
            return null;
        }
        
        // Read the UUID string from NBT
        // Returns null if the key doesn't exist (shouldn't happen with valid backpacks)
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.get(backpackUUIDKey, PersistentDataType.STRING);
    }
    
    // ==================== BACKPACK OPERATIONS ====================
    // These methods perform operations on backpacks: upgrading and opening.
    
    /**
     * Upgrades a backpack's capacity from 27 slots to 54 slots.
     * 
     * <p>Modification details:</p>
     * <ul>
     *   <li>NBT: backpack_size changes from 27 to 54</li>
     *   <li>Lore: Capacity line updates to "Capacity: 54 slots"</li>
     *   <li>Lore: "✦ UPGRADED ✦" badge added in light purple/magenta</li>
     *   <li>UUID: Unchanged (preserves link to existing storage)</li>
     *   <li>Enchant glow: Unchanged</li>
     *   <li>Display name: Unchanged</li>
     * </ul>
     * 
     * <p>The upgrade is non-destructive to stored contents. The same UUID means
     * the existing 27 items remain in slots 0-26, and the new slots 27-53 are empty.</p>
     * 
     * <p>Important: This method does NOT:</p>
     * <ul>
     *   <li>Consume the doubler item (caller's responsibility)</li>
     *   <li>Check if the backpack is already upgraded (caller's responsibility)</li>
     *   <li>Play sounds or send messages (caller's responsibility)</li>
     *   <li>Validate that the item is actually a backpack (caller should check)</li>
     * </ul>
     * 
     * @param backpack The backpack ItemStack to upgrade (modified in place)
     * @return The same ItemStack reference, now with 54-slot capacity (for chaining)
     */
    private ItemStack upgradeBackpack(ItemStack backpack) {
        // Get mutable metadata for modification
        ItemMeta meta = backpack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        
        // Update the capacity in NBT from 27 to 54
        // The UUID remains unchanged, so storage link is preserved
        pdc.set(backpackSizeKey, PersistentDataType.INTEGER, 54);
        
        // Rebuild the lore to reflect the upgraded status
        // We create a completely new lore list rather than modifying the existing one
        List<Component> lore = new ArrayList<>();
        // Line 1: Same description as before
        lore.add(Component.text("A portable storage container", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        // Line 2: Updated capacity showing 54 slots instead of 27
        lore.add(Component.text("Capacity: 54 slots", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
        // Line 3: Special "UPGRADED" badge with decorative stars
        // Light purple (magenta) matches SupaFloof branding and looks premium
        lore.add(Component.text("✦ UPGRADED ✦", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
            .decoration(TextDecoration.ITALIC, false));
        // Line 4: Empty line for visual spacing
        lore.add(Component.empty());
        // Line 5: Same usage instructions as before
        lore.add(Component.text("Right-click to open", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        
        // Apply changes to the ItemStack
        backpack.setItemMeta(meta);
        return backpack;
    }
    
    /**
     * Opens an item-based backpack's inventory GUI for a player.
     * 
     * <p>Opening process:</p>
     * <ol>
     *   <li>Extract UUID from backpack's NBT data</li>
     *   <li>Validate UUID exists (show error if missing/corrupted)</li>
     *   <li>Get backpack capacity (27 or 54 slots)</li>
     *   <li>Create Bukkit Inventory with appropriate size</li>
     *   <li>Load stored items from memory into the inventory</li>
     *   <li>Register session in tracking maps for save/event handling</li>
     *   <li>Open the inventory GUI for the player</li>
     *   <li>Send confirmation message</li>
     * </ol>
     * 
     * <p>Inventory title format: "Backpack (27 slots)" or "Backpack (54 slots)"</p>
     * 
     * <p>Storage handling:</p>
     * <ul>
     *   <li>If storage exists for this UUID, items are loaded into their original slots</li>
     *   <li>If storage doesn't exist (new or corrupted), empty HashMap is initialized</li>
     *   <li>Items outside current capacity are skipped (shouldn't happen normally)</li>
     * </ul>
     * 
     * <p>Session tracking:</p>
     * <ul>
     *   <li>{@link #activeBackpacks}: Maps player UUID → Inventory (for event detection)</li>
     *   <li>{@link #openBackpackUUIDs}: Maps player UUID → Backpack UUID (for save operations)</li>
     * </ul>
     * 
     * @param player The player opening the backpack
     * @param backpack The backpack ItemStack being opened
     */
    private void openBackpack(Player player, ItemStack backpack) {
        // Extract the UUID that identifies this backpack's storage
        String backpackUUID = getBackpackUUID(backpack);
        if (backpackUUID == null) {
            // This should never happen with valid backpacks created by this plugin
            // Could indicate NBT corruption or an item falsely identified as a backpack
            player.sendMessage(Component.text("Error: Invalid backpack!", NamedTextColor.RED));
            return;
        }
        
        // Get the capacity to determine inventory size
        int capacity = getBackpackCapacity(backpack);
        
        // Create a custom Bukkit inventory for the backpack GUI
        // Parameters:
        // - null: No InventoryHolder (this is a custom inventory, not a block)
        // - capacity: Number of slots (27 or 54, must be multiple of 9)
        // - title: Text shown at the top of the inventory window
        String title = "Backpack (" + capacity + " slots)";
        Inventory inv = Bukkit.createInventory(null, capacity, title);
        
        // Load stored items into the newly created inventory
        Map<Integer, ItemStack> contents = backpackStorage.get(backpackUUID);
        if (contents != null) {
            // Iterate through all stored items and place them in the inventory
            for (Map.Entry<Integer, ItemStack> entry : contents.entrySet()) {
                // Safety check: only load items that fit within current capacity
                // This prevents issues if a backpack was somehow downgraded (shouldn't happen)
                // or if storage data is corrupted with out-of-range slot indices
                if (entry.getKey() < capacity) {
                    inv.setItem(entry.getKey(), entry.getValue());
                }
            }
        } else {
            // No existing storage for this UUID - initialize empty storage
            // This handles newly created backpacks or recovery from corrupted data
            backpackStorage.put(backpackUUID, new HashMap<>());
        }
        
        // Register this session in tracking maps for later event handling and saving
        // These entries will be removed when the player closes the inventory
        activeBackpacks.put(player.getUniqueId(), inv);
        openBackpackUUIDs.put(player.getUniqueId(), backpackUUID);
        
        // Open the inventory GUI for the player (triggers client-side window)
        player.openInventory(inv);
        
        // Confirm to the player that the backpack is open
        player.sendMessage(Component.text("Opened backpack!", NamedTextColor.GREEN));
    }
    
    /**
     * Opens a player's personal backpack inventory GUI.
     * 
     * <p>Personal backpacks differ from item-based backpacks:</p>
     * <ul>
     *   <li>No physical item required - accessed purely via /bp command</li>
     *   <li>Fixed 54-slot capacity (cannot be upgraded or downgraded)</li>
     *   <li>One per player, tied to their UUID</li>
     *   <li>Storage key format: "personal-{PlayerUUID}"</li>
     * </ul>
     * 
     * <p>Opening process:</p>
     * <ol>
     *   <li>Generate storage key from player's UUID with "personal-" prefix</li>
     *   <li>Create 54-slot Bukkit Inventory with "Personal Backpack" title</li>
     *   <li>Load stored items from memory into the inventory</li>
     *   <li>Register session in tracking maps</li>
     *   <li>Open the inventory GUI</li>
     *   <li>Send confirmation message</li>
     * </ol>
     * 
     * <p>The personal backpack is created on first use - no explicit creation required.
     * If no storage exists for this player's personal backpack, an empty HashMap is initialized.</p>
     * 
     * @param player The player opening their personal backpack
     */
    private void openPersonalBackpack(Player player) {
        // Generate the storage key for this player's personal backpack
        // Format: "personal-{PlayerUUID}" (e.g., "personal-12345678-1234-1234-1234-123456789abc")
        // This distinguishes personal backpacks from item-based backpacks in storage
        String personalBackpackUUID = PERSONAL_BACKPACK_PREFIX + player.getUniqueId().toString();
        
        // Create the inventory GUI with fixed 54-slot capacity
        // Title is simpler than item-based backpacks since capacity never varies
        String title = "Personal Backpack";
        Inventory inv = Bukkit.createInventory(null, PERSONAL_BACKPACK_SIZE, title);
        
        // Load any existing stored items into the inventory
        Map<Integer, ItemStack> contents = backpackStorage.get(personalBackpackUUID);
        if (contents != null) {
            // Place each stored item in its saved slot position
            for (Map.Entry<Integer, ItemStack> entry : contents.entrySet()) {
                // Safety check to prevent ArrayIndexOutOfBoundsException
                if (entry.getKey() < PERSONAL_BACKPACK_SIZE) {
                    inv.setItem(entry.getKey(), entry.getValue());
                }
            }
        } else {
            // First time opening personal backpack - initialize empty storage
            backpackStorage.put(personalBackpackUUID, new HashMap<>());
        }
        
        // Register session in tracking maps (same as item-based backpacks)
        activeBackpacks.put(player.getUniqueId(), inv);
        openBackpackUUIDs.put(player.getUniqueId(), personalBackpackUUID);
        
        // Open the GUI and confirm
        player.openInventory(inv);
        player.sendMessage(Component.text("Opened personal backpack!", NamedTextColor.GREEN));
    }
    
    /**
     * Saves the contents of a player's currently open backpack to persistent storage.
     * 
     * <p>Save process:</p>
     * <ol>
     *   <li>Verify player has an open backpack (check tracking maps)</li>
     *   <li>Get the Inventory and backpack UUID from tracking maps</li>
     *   <li>Iterate through all slots in the inventory</li>
     *   <li>Clone each non-empty ItemStack into a contents map</li>
     *   <li>Update the in-memory {@link #backpackStorage} map</li>
     *   <li>Write contents to YAML file immediately</li>
     * </ol>
     * 
     * <p>Called by:</p>
     * <ul>
     *   <li>{@link #onInventoryClose(InventoryCloseEvent)} - When player closes backpack</li>
     *   <li>{@link #onDisable()} - During server shutdown for all open backpacks</li>
     * </ul>
     * 
     * <p>Important implementation notes:</p>
     * <ul>
     *   <li>Items are CLONED before storage to prevent reference issues</li>
     *   <li>Air/null items are not stored (keeps YAML files clean)</li>
     *   <li>Empty slots don't appear in storage (sparse map)</li>
     *   <li>Save is immediate (no batching) for data safety</li>
     * </ul>
     * 
     * @param player The player whose backpack should be saved
     */
    private void saveBackpackContents(Player player) {
        UUID playerId = player.getUniqueId();
        
        // Verify this player actually has an open backpack
        // If not, this is a no-op (might happen during edge cases)
        if (!activeBackpacks.containsKey(playerId) || !openBackpackUUIDs.containsKey(playerId)) {
            return;
        }
        
        // Get the inventory GUI and the backpack's storage UUID
        Inventory inv = activeBackpacks.get(playerId);
        String backpackUUID = openBackpackUUIDs.get(playerId);
        
        // Build a map of slot → item for all non-empty slots
        Map<Integer, ItemStack> contents = new HashMap<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            // Only store slots that contain actual items (not air/null)
            if (item != null && item.getType() != Material.AIR) {
                // CLONE the item to prevent reference issues
                // Without cloning, changes to the inventory would affect our stored data
                // and vice versa, leading to potential desync or duplication issues
                contents.put(i, item.clone());
            }
        }
        
        // Update the in-memory storage map
        backpackStorage.put(backpackUUID, contents);
        
        // Write to disk immediately for data safety
        // We don't batch saves because losing items is unacceptable
        saveBackpackToFile(backpackUUID, contents);
    }
    
    /**
     * Writes a backpack's contents to its YAML file on disk.
     * 
     * <p>File location: plugins/Backpacks/playerdata/{UUID}.yml</p>
     * 
     * <p>YAML structure:</p>
     * <pre>
     * slot:
     *   0: {ItemStack data}
     *   5: {ItemStack data}
     *   10: {ItemStack data}
     * </pre>
     * 
     * <p>Process:</p>
     * <ol>
     *   <li>Ensure playerdata/ directory exists (create if missing)</li>
     *   <li>Create FileConfiguration object for YAML handling</li>
     *   <li>Store each ItemStack at "slot.{index}" path</li>
     *   <li>Save configuration to file with error handling</li>
     * </ol>
     * 
     * <p>Bukkit's ConfigurationSection handles ItemStack serialization automatically,
     * preserving all NBT data, enchantments, lore, display names, durability, etc.</p>
     * 
     * <p>Each backpack has its own file to:</p>
     * <ul>
     *   <li>Prevent total data loss if one file corrupts</li>
     *   <li>Enable easy per-backpack backup and recovery</li>
     *   <li>Allow manual editing of individual backpack contents</li>
     * </ul>
     * 
     * @param backpackUUID The UUID identifying this backpack (becomes filename)
     * @param contents Map of slot indices to ItemStacks to save
     */
    private void saveBackpackToFile(String backpackUUID, Map<Integer, ItemStack> contents) {
        // Ensure the playerdata directory exists, creating it if necessary
        // Using "playerdata" as the folder name (matches common Minecraft conventions)
        File backpacksDir = new File(getDataFolder(), "playerdata");
        if (!backpacksDir.exists()) {
            // mkdirs() creates parent directories too if needed
            backpacksDir.mkdirs();
        }
        
        // Construct the full file path: plugins/Backpacks/playerdata/{UUID}.yml
        File backpackFile = new File(backpacksDir, backpackUUID + ".yml");
        
        // Create a new YAML configuration object
        // We use a fresh config each save (not loading existing) because we're
        // replacing all content, not updating individual entries
        org.bukkit.configuration.file.FileConfiguration config = 
            new org.bukkit.configuration.file.YamlConfiguration();
        
        // Store each ItemStack in the config at "slot.{index}" path
        // Bukkit's serialization handles the complex ItemStack → YAML conversion
        for (Map.Entry<Integer, ItemStack> entry : contents.entrySet()) {
            config.set("slot." + entry.getKey(), entry.getValue());
        }
        
        // Write the configuration to disk
        try {
            config.save(backpackFile);
        } catch (Exception e) {
            // Log the error but don't crash - data is still safe in memory
            // The next save attempt might succeed (e.g., if disk space freed)
            getLogger().warning("Failed to save backpack " + backpackUUID + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Loads all backpack data from YAML files into memory at startup.
     * 
     * <p>Loading process:</p>
     * <ol>
     *   <li>Clear existing backpackStorage map (important for /reload scenarios)</li>
     *   <li>Check if playerdata/ directory exists (skip if not)</li>
     *   <li>List all .yml files in the directory</li>
     *   <li>For each file:
     *     <ul>
     *       <li>Extract UUID from filename (remove .yml extension)</li>
     *       <li>Load YAML configuration</li>
     *       <li>Parse "slot" section into Integer → ItemStack map</li>
     *       <li>Store in backpackStorage map</li>
     *     </ul>
     *   </li>
     *   <li>Log count of loaded backpacks</li>
     * </ol>
     * 
     * <p>Error handling:</p>
     * <ul>
     *   <li>Missing directory: Logs info and returns (fresh server)</li>
     *   <li>No files: Logs info and returns</li>
     *   <li>Invalid slot number: Logs warning, skips that slot</li>
     *   <li>File read error: YamlConfiguration handles gracefully</li>
     * </ul>
     * 
     * <p>File naming conventions:</p>
     * <ul>
     *   <li>Item backpacks: Random UUID (e.g., "a1b2c3d4-e5f6-7890-abcd-ef1234567890.yml")</li>
     *   <li>Personal backpacks: "personal-{PlayerUUID}.yml"</li>
     * </ul>
     * 
     * <p>Called only during {@link #onEnable()} at server startup.</p>
     */
    private void loadBackpackStorage() {
        // Clear any existing data - important if this is called during a reload
        // (though currently only called in onEnable, this is defensive programming)
        backpackStorage.clear();
        
        // Check if the playerdata directory exists
        File backpacksDir = new File(getDataFolder(), "playerdata");
        if (!backpacksDir.exists()) {
            // No playerdata folder means no backpacks have been created yet
            // This is normal for fresh server installations
            getLogger().info("No playerdata directory found, starting fresh");
            return;
        }
        
        // List all .yml files in the directory using a filename filter
        // The filter lambda only accepts files ending with ".yml"
        File[] files = backpacksDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            // Directory exists but contains no YAML files
            getLogger().info("No backpack files found, starting fresh");
            return;
        }
        
        // Track count for logging
        int loaded = 0;
        
        // Process each backpack file
        for (File file : files) {
            // Extract the UUID from the filename by removing the .yml extension
            // For "a1b2c3d4-e5f6.yml" this gives us "a1b2c3d4-e5f6"
            String uuid = file.getName().replace(".yml", "");
            
            // Load the YAML configuration from the file
            // YamlConfiguration.loadConfiguration handles file reading and parsing
            org.bukkit.configuration.file.FileConfiguration config = 
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            
            // Prepare the contents map for this backpack
            Map<Integer, ItemStack> contents = new HashMap<>();
            
            // Check if the "slot" section exists in the YAML
            if (config.contains("slot")) {
                // Get the ConfigurationSection containing all slot entries
                org.bukkit.configuration.ConfigurationSection section = config.getConfigurationSection("slot");
                if (section != null) {
                    // Iterate through each key in the slot section
                    // Keys are slot numbers as strings: "0", "5", "10", etc.
                    for (String slotStr : section.getKeys(false)) {
                        try {
                            // Parse the string key to an integer slot number
                            int slot = Integer.parseInt(slotStr);
                            
                            // Deserialize the ItemStack from YAML
                            // Bukkit handles all NBT, enchantments, lore, etc. automatically
                            ItemStack item = section.getItemStack(slotStr);
                            if (item != null) {
                                contents.put(slot, item);
                            }
                        } catch (NumberFormatException e) {
                            // The slot key wasn't a valid integer - corrupted data
                            // Log and skip this slot but continue loading the rest
                            getLogger().warning("Invalid slot number in backpack " + uuid + ": " + slotStr);
                        }
                    }
                }
            }
            
            // Store this backpack's contents in the main storage map
            backpackStorage.put(uuid, contents);
            loaded++;
        }
        
        // Log the results for server operators
        getLogger().info("Loaded " + loaded + " backpacks from storage");
    }
    
    // ==================== EVENT HANDLERS ====================
    // These methods respond to Bukkit events for player interactions and inventory management.
    
    /**
     * Handles right-click interactions for opening backpack items.
     * 
     * <p>Event: {@link PlayerInteractEvent}
     * <br>Priority: {@link EventPriority#HIGHEST} (processes after most other plugins)</p>
     * 
     * <p>Trigger conditions:</p>
     * <ul>
     *   <li>Action is RIGHT_CLICK_AIR or RIGHT_CLICK_BLOCK</li>
     *   <li>Player is holding a backpack item (in main or off hand)</li>
     * </ul>
     * 
     * <p>Behavior when triggered:</p>
     * <ol>
     *   <li>Cancel the event to prevent default item/block behavior</li>
     *   <li>Open the backpack GUI via {@link #openBackpack(Player, ItemStack)}</li>
     * </ol>
     * 
     * <p>Important notes:</p>
     * <ul>
     *   <li>NO permission check - backpack items work for all players</li>
     *   <li>HIGHEST priority lets protection plugins cancel first if needed</li>
     *   <li>Event is cancelled to prevent placing the backpack item as a block</li>
     *   <li>Works with both main hand and off-hand (event.getItem() handles both)</li>
     * </ul>
     * 
     * @param event The PlayerInteractEvent from Bukkit
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        // Get the item the player is interacting with (main or off hand)
        ItemStack item = event.getItem();
        
        // Only process right-click actions
        // LEFT_CLICK actions and PHYSICAL (pressure plates) are ignored
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        
        // Check if the held item is a backpack
        // Note: NO permission check here - backpack items given via /backpack give
        // are usable by any player, regardless of permissions
        if (isBackpack(item)) {
            // Cancel the event to prevent:
            // - Placing the backpack item as a block (if it's a BARREL, etc.)
            // - Opening containers the player might be looking at
            // - Any other default right-click behavior
            event.setCancelled(true);
            // Open the backpack's inventory GUI
            openBackpack(player, item);
            return;
        }
    }
    
    /**
     * Handles inventory clicks for backpack upgrades and nested backpack prevention.
     * 
     * <p>Event: {@link InventoryClickEvent}
     * <br>Priority: NORMAL (default)</p>
     * 
     * <p>This handler manages two distinct scenarios:</p>
     * 
     * <h3>Scenario 1: Doubler Application</h3>
     * <p>When a player has a doubler on their cursor and clicks a backpack:</p>
     * <ol>
     *   <li>Validate backpack is not stacked (amount must be 1)</li>
     *   <li>Validate backpack is not already at 54 slots</li>
     *   <li>Upgrade the backpack via {@link #upgradeBackpack(ItemStack)}</li>
     *   <li>Consume one doubler from cursor</li>
     *   <li>Play ENTITY_PLAYER_LEVELUP sound</li>
     *   <li>Send success message</li>
     *   <li>Cancel event to prevent normal click behavior</li>
     * </ol>
     * <p>This works in ANY inventory - player inventory, chests, etc.</p>
     * 
     * <h3>Scenario 2: Nested Backpack Prevention</h3>
     * <p>When a player has a backpack open and tries to place another backpack inside:</p>
     * <ol>
     *   <li>Check if player has an active backpack session</li>
     *   <li>Check if clicking in the backpack inventory (not player inventory)</li>
     *   <li>Check if cursor contains a backpack</li>
     *   <li>Check config "allow-nested-backpacks" setting (default: false)</li>
     *   <li>If nested backpacks disallowed, cancel event and notify player</li>
     * </ol>
     * 
     * @param event The InventoryClickEvent from Bukkit
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // Verify the clicker is a player (not a hopper, dropper, etc.)
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getWhoClicked();
        UUID playerId = player.getUniqueId();
        
        // Get the items involved in this click event
        ItemStack cursor = event.getCursor();       // Item currently on the player's cursor (being held)
        ItemStack clicked = event.getCurrentItem(); // Item in the slot that was clicked
        
        // ===== DOUBLER APPLICATION HANDLING =====
        // Check if player is clicking a doubler (cursor) onto a backpack (clicked slot)
        if (isDoubler(cursor) && isBackpack(clicked)) {
            // Safety check: backpack must not be stacked
            // Stacked backpacks could lead to duplication exploits if upgraded
            if (clicked.getAmount() > 1) {
                player.sendMessage(Component.text("The doubler only works on a single backpack!", NamedTextColor.RED));
                event.setCancelled(true);
                return;
            }
            
            // Check current capacity to see if upgrade is possible
            int currentCapacity = getBackpackCapacity(clicked);
            
            // Prevent upgrading an already-upgraded backpack
            if (currentCapacity >= 54) {
                player.sendMessage(Component.text("This backpack is already at maximum capacity!", NamedTextColor.RED));
                event.setCancelled(true);
                return;
            }
            
            // Perform the upgrade - modifies the ItemStack's NBT and lore
            ItemStack upgraded = upgradeBackpack(clicked);
            // Update the slot with the upgraded backpack
            event.setCurrentItem(upgraded);
            
            // Consume one doubler from the cursor stack
            if (cursor.getAmount() > 1) {
                // Multiple doublers on cursor: decrease stack by 1
                cursor.setAmount(cursor.getAmount() - 1);
                event.setCursor(cursor);
            } else {
                // Single doubler on cursor: remove it entirely
                event.setCursor(null);
            }
            
            // Provide feedback to the player
            // Magenta/light purple text with stars for a premium upgrade feel
            player.sendMessage(Component.text("✦ Backpack upgraded to 54 slots! ✦", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
            // Play the level-up sound for satisfying audio feedback
            // Pitch 1.5f makes it slightly higher than normal (more "upgrade-y")
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
            
            // Cancel the event to prevent the normal click behavior
            event.setCancelled(true);
            return;
        }
        
        // ===== NESTED BACKPACK PREVENTION =====
        // Only applies if player has an open backpack
        if (!activeBackpacks.containsKey(playerId)) {
            // Player doesn't have a backpack open, no restrictions apply
            return;
        }
        
        // Check if the player is clicking in the backpack inventory (top) not their own (bottom)
        if (event.getClickedInventory() == activeBackpacks.get(playerId)) {
            // Player is clicking in the backpack GUI
            // Check if they're trying to place a backpack inside
            if (isBackpack(cursor) && !getConfig().getBoolean("allow-nested-backpacks", false)) {
                // Nested backpacks are disabled in config
                event.setCancelled(true);
                player.sendMessage(Component.text("You cannot put a backpack inside another backpack!", NamedTextColor.RED));
            }
        }
    }
    
    /**
     * Handles saving backpack contents when the inventory GUI is closed.
     * 
     * <p>Event: {@link InventoryCloseEvent}
     * <br>Priority: NORMAL (default)</p>
     * 
     * <p>Process:</p>
     * <ol>
     *   <li>Verify closer is a player (not console or other entity)</li>
     *   <li>Check if this player has an active backpack session</li>
     *   <li>If yes:
     *     <ul>
     *       <li>Save contents via {@link #saveBackpackContents(Player)}</li>
     *       <li>Remove from {@link #activeBackpacks} map</li>
     *       <li>Remove from {@link #openBackpackUUIDs} map</li>
     *       <li>Send "Backpack saved!" confirmation</li>
     *     </ul>
     *   </li>
     * </ol>
     * 
     * <p>This ensures immediate persistence when a backpack is closed, so:</p>
     * <ul>
     *   <li>Server crashes after closing don't lose data</li>
     *   <li>Player logouts after closing don't lose data</li>
     *   <li>Memory is freed by removing tracking map entries</li>
     *   <li>Player can immediately open another backpack</li>
     * </ul>
     * 
     * @param event The InventoryCloseEvent from Bukkit
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Verify the closer is a player (entities can have inventories closed too)
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Check if this player had a backpack open (vs. a chest, furnace, etc.)
        if (activeBackpacks.containsKey(playerId)) {
            // Save the backpack contents to disk immediately
            // This writes to YAML file, not just memory
            saveBackpackContents(player);
            
            // Clean up tracking maps to free memory and allow new backpack opens
            activeBackpacks.remove(playerId);
            openBackpackUUIDs.remove(playerId);
            
            // Confirm to the player that their items are saved
            player.sendMessage(Component.text("Backpack saved!", NamedTextColor.GREEN));
        }
    }
    
    // ==================== COMMANDS ====================
    // Command handling for /backpack and /bp commands.
    
    /**
     * Handles execution of /backpack and /bp commands.
     * 
     * <p>Command routing:</p>
     * <ul>
     *   <li><b>/bp</b> (no args) → Open personal backpack (requires backpacks.use, player only)</li>
     *   <li><b>/backpack</b> (no args) → Display help menu</li>
     *   <li><b>/backpack help</b> → Display help menu</li>
     *   <li><b>/backpack give &lt;type&gt; &lt;player&gt;</b> → Route to {@link #handleGive(CommandSender, String[])}</li>
     *   <li><b>/backpack reload</b> → Route to {@link #handleReload(CommandSender)}</li>
     *   <li><b>/backpack &lt;unknown&gt;</b> → Display help menu</li>
     * </ul>
     * 
     * <p>Permission checks are delegated to sub-handlers, not performed here,
     * except for /bp which requires player check and backpacks.use permission.</p>
     * 
     * @param sender The CommandSender (player or console)
     * @param command The Command object containing command metadata
     * @param label The alias used to execute the command (e.g., "bp" or "backpack")
     * @param args The command arguments (split by spaces)
     * @return true if the command was handled (always returns true)
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Handle /bp with no arguments - opens personal backpack
        if (label.equalsIgnoreCase("bp") && args.length == 0) {
            // Personal backpacks require a player (console can't have an inventory)
            if (!(sender instanceof Player)) {
                sender.sendMessage(Component.text("Only players can use personal backpacks!", NamedTextColor.RED));
                return true;
            }
            
            Player player = (Player) sender;
            
            // Check permission for personal backpack access
            if (!player.hasPermission("backpacks.use")) {
                player.sendMessage(Component.text("You don't have permission to use personal backpacks!", NamedTextColor.RED));
                return true;
            }
            
            // Open the player's personal backpack (54 slots, stored by player UUID)
            openPersonalBackpack(player);
            return true;
        }
        
        // No arguments provided for /backpack - show the help menu
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        
        // Route to appropriate handler based on the first argument (subcommand)
        switch (args[0].toLowerCase()) {
            case "give":
                // Delegate to give handler (handles permission check internally)
                return handleGive(sender, args);
            case "reload":
                // Delegate to reload handler (handles permission check internally)
                return handleReload(sender);
            case "help":
                // Show help menu
                sendHelp(sender);
                return true;
            default:
                // Unknown subcommand - show help as fallback
                sendHelp(sender);
                return true;
        }
    }
    
    /**
     * Displays the formatted help menu to a command sender.
     * 
     * <p>Display format:</p>
     * <ul>
     *   <li>Top border: Gold line of ━ characters</li>
     *   <li>Title: "Backpacks Commands" in gold, bold</li>
     *   <li>Separator: Gold line of ━ characters</li>
     *   <li>Commands: Yellow command + gray description (permission-filtered)</li>
     *   <li>Bottom border: Gold line of ━ characters</li>
     * </ul>
     * 
     * <p>Permission-based visibility:</p>
     * <ul>
     *   <li>backpacks.use → Shows /bp command</li>
     *   <li>backpacks.give → Shows give backpack and doubler commands</li>
     *   <li>backpacks.admin → Shows reload command</li>
     *   <li>No permission required → Shows help command</li>
     * </ul>
     * 
     * <p>This ensures players only see commands they can actually use,
     * reducing confusion and clutter in the help output.</p>
     * 
     * @param sender The CommandSender to receive the help menu
     */
    private void sendHelp(CommandSender sender) {
        // Top decorative border (gold ━ characters for visual separation)
        sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
        
        // Title with bold gold text
        sender.sendMessage(Component.text("Backpacks Commands", NamedTextColor.GOLD, TextDecoration.BOLD));
        
        // Separator border under title
        sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
        
        // /bp command - only show if player has backpacks.use permission
        if (sender.hasPermission("backpacks.use")) {
            sender.sendMessage(Component.text("/bp", NamedTextColor.YELLOW)
                .append(Component.text(" - Open your personal backpack (54 slots)", NamedTextColor.GRAY)));
        }
        
        // /backpack help - always visible (no permission required)
        sender.sendMessage(Component.text("/backpack help", NamedTextColor.YELLOW)
            .append(Component.text(" - Show this help menu", NamedTextColor.GRAY)));
        
        // Give commands - only show if sender has backpacks.give permission
        if (sender.hasPermission("backpacks.give")) {
            sender.sendMessage(Component.text("/backpack give backpack <player>", NamedTextColor.YELLOW)
                .append(Component.text(" - Give a backpack", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/backpack give doubler <player>", NamedTextColor.YELLOW)
                .append(Component.text(" - Give a capacity doubler", NamedTextColor.GRAY)));
        }
        
        // Reload command - only show if sender has backpacks.admin permission
        if (sender.hasPermission("backpacks.admin")) {
            sender.sendMessage(Component.text("/backpack reload", NamedTextColor.YELLOW)
                .append(Component.text(" - Reload configuration", NamedTextColor.GRAY)));
        }
        
        // Bottom decorative border
        sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
    }
    
    /**
     * Handles the /backpack give command for distributing backpacks and doublers.
     * 
     * <p>Command syntax: /backpack give &lt;backpack|doubler&gt; &lt;player&gt;</p>
     * <p>Permission required: backpacks.give</p>
     * 
     * <p>Argument structure:</p>
     * <ul>
     *   <li>args[0]: "give" (subcommand, already matched)</li>
     *   <li>args[1]: Item type ("backpack" or "doubler")</li>
     *   <li>args[2]: Target player name</li>
     * </ul>
     * 
     * <p>Validation sequence:</p>
     * <ol>
     *   <li>Check backpacks.give permission</li>
     *   <li>Validate argument count (need at least 3)</li>
     *   <li>Parse item type (case-insensitive)</li>
     *   <li>Find target player (must be online)</li>
     * </ol>
     * 
     * <p>Success behavior:</p>
     * <ul>
     *   <li>Creates appropriate item via {@link #createBackpack()} or {@link #createDoubler()}</li>
     *   <li>Adds to target player's inventory</li>
     *   <li>Sends green confirmation to sender with player name in gold</li>
     *   <li>Sends green notification to target player</li>
     * </ul>
     * 
     * <p>Error cases:</p>
     * <ul>
     *   <li>No permission → Red error message</li>
     *   <li>Missing arguments → Red usage message</li>
     *   <li>Player not found → Red error message</li>
     *   <li>Invalid item type → Red error message</li>
     * </ul>
     * 
     * @param sender The CommandSender executing the command
     * @param args Full command arguments (includes "give" as args[0])
     * @return true (command was handled)
     */
    private boolean handleGive(CommandSender sender, String[] args) {
        // Check permission first
        if (!sender.hasPermission("backpacks.give")) {
            sender.sendMessage(Component.text("You don't have permission to give backpack items!", NamedTextColor.RED));
            return true;
        }
        
        // Validate argument count
        // Expected: args[0]="give", args[1]=type, args[2]=player
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /backpack give <backpack|doubler> <player>", NamedTextColor.RED));
            return true;
        }
        
        // Parse arguments
        String itemType = args[1].toLowerCase();  // Normalize to lowercase for matching
        Player target = Bukkit.getPlayer(args[2]); // Look up player by name
        
        // Validate target player exists and is online
        if (target == null) {
            sender.sendMessage(Component.text("Player not found!", NamedTextColor.RED));
            return true;
        }
        
        // Handle different item types
        switch (itemType) {
            case "backpack":
                // Create a new 27-slot backpack and add to target's inventory
                target.getInventory().addItem(createBackpack());
                
                // Notify the command sender
                sender.sendMessage(Component.text("Gave backpack to ", NamedTextColor.GREEN)
                    .append(Component.text(target.getName(), NamedTextColor.GOLD)));
                
                // Notify the target player
                target.sendMessage(Component.text("You received a backpack!", NamedTextColor.GREEN));
                break;
                
            case "doubler":
                // Create a capacity doubler and add to target's inventory
                target.getInventory().addItem(createDoubler());
                
                // Notify the command sender
                sender.sendMessage(Component.text("Gave capacity doubler to ", NamedTextColor.GREEN)
                    .append(Component.text(target.getName(), NamedTextColor.GOLD)));
                
                // Notify the target player
                target.sendMessage(Component.text("You received a backpack capacity doubler!", NamedTextColor.GREEN));
                break;
                
            default:
                // Unknown item type - show error
                sender.sendMessage(Component.text("Invalid item type! Use 'backpack' or 'doubler'", NamedTextColor.RED));
                return true;
        }
        
        return true;
    }
    
    /**
     * Handles the /backpack reload command for refreshing configuration.
     * 
     * <p>Command syntax: /backpack reload</p>
     * <p>Permission required: backpacks.admin</p>
     * 
     * <p>Process:</p>
     * <ol>
     *   <li>Check backpacks.admin permission</li>
     *   <li>Call Bukkit's reloadConfig() to re-read config.yml</li>
     *   <li>Send confirmation message</li>
     * </ol>
     * 
     * <p>What gets reloaded:</p>
     * <ul>
     *   <li>backpack-item: Material for new backpacks</li>
     *   <li>allow-nested-backpacks: Nesting restriction setting</li>
     * </ul>
     * 
     * <p>What does NOT get reloaded:</p>
     * <ul>
     *   <li>Backpack storage data (would require closing all open backpacks)</li>
     *   <li>NamespacedKeys (immutable after plugin initialization)</li>
     *   <li>Currently active backpack sessions</li>
     * </ul>
     * 
     * <p>This command is safe to use while players have backpacks open.
     * Config changes only affect new operations, not existing sessions.</p>
     * 
     * @param sender The CommandSender executing the command
     * @return true (command was handled)
     */
    private boolean handleReload(CommandSender sender) {
        // Check admin permission
        if (!sender.hasPermission("backpacks.admin")) {
            sender.sendMessage(Component.text("You don't have permission to reload configuration!", NamedTextColor.RED));
            return true;
        }
        
        // Re-read config.yml from disk
        // This updates getConfig() to return fresh values
        reloadConfig();
        
        // Confirm successful reload
        sender.sendMessage(Component.text("Backpacks configuration reloaded!", NamedTextColor.GREEN));
        
        return true;
    }
    
    // ==================== TAB COMPLETION ====================
    
    /**
     * Provides intelligent tab completion suggestions for /backpack and /bp commands.
     * 
     * <p>Completion behavior by command and position:</p>
     * 
     * <h3>/bp command:</h3>
     * <p>Returns empty list (no arguments needed or supported)</p>
     * 
     * <h3>/backpack command:</h3>
     * <p><b>Position 1 (subcommand):</b></p>
     * <ul>
     *   <li>Always: "help"</li>
     *   <li>If has backpacks.give: "give"</li>
     *   <li>If has backpacks.admin: "reload"</li>
     * </ul>
     * 
     * <p><b>Position 2 (after "give"):</b></p>
     * <ul>
     *   <li>"backpack"</li>
     *   <li>"doubler"</li>
     * </ul>
     * 
     * <p><b>Position 3 (after "give &lt;type&gt;"):</b></p>
     * <ul>
     *   <li>All online player names</li>
     * </ul>
     * 
     * <p>Filtering behavior:</p>
     * <p>All suggestions are filtered by the current partial input (case-insensitive).
     * For example, typing "/backpack g" filters to suggestions starting with "g".</p>
     * 
     * <p>Permission-aware completions ensure players only see options they can use.</p>
     * 
     * @param sender The CommandSender typing the command
     * @param command The Command being completed
     * @param alias The alias used (e.g., "backpack" or "bp")
     * @param args Current arguments being typed
     * @return List of completion suggestions (may be empty, never null)
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        // /bp has no tab completions (opens personal backpack directly)
        if (alias.equalsIgnoreCase("bp")) {
            return completions;
        }
        
        // First argument: subcommand suggestions
        if (args.length == 1) {
            // Help is always available (no permission required)
            completions.add("help");
            
            // Give command requires backpacks.give permission
            if (sender.hasPermission("backpacks.give")) {
                completions.add("give");
            }
            
            // Reload command requires backpacks.admin permission
            if (sender.hasPermission("backpacks.admin")) {
                completions.add("reload");
            }
        } 
        // Second argument: item type (only after "give")
        else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            completions.add("backpack");
            completions.add("doubler");
        } 
        // Third argument: player name (only after "give <type>")
        else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            // Return all online player names for selection
            // No permission check here since give permission was already verified
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
        }
        
        // Filter suggestions by partial input (what the player has typed so far)
        // This provides standard tab-completion behavior where typing narrows the options
        // toLowerCase() on both sides ensures case-insensitive matching
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
            .collect(Collectors.toList());
    }
}