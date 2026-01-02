# Backpacks - Developer Guide

## Architecture Overview

Backpacks is a single-file Paper/Spigot plugin that implements a portable storage system using Minecraft's inventory system and persistent data containers. The plugin provides two distinct storage mechanisms: personal backpacks (command-based, per-player) and item-based backpacks (physical items that can be traded, stored, and used by any player).

### Core Design Principles

1. **Single-file architecture** - All code in one class (`Backpacks.java`) for simplicity and maintainability
2. **Immediate persistence** - Data is saved to disk on every inventory close, preventing data loss
3. **UUID-based storage** - Each backpack has a unique identifier linking items to their storage
4. **Adventure API** - Modern text component system for all player-facing messages
5. **NBT-based identification** - Items identified via PersistentDataContainer, immune to anvil renaming

## Technology Stack

### Required APIs
- **Bukkit/Spigot API** - Core Minecraft server API
- **Adventure API** (net.kyori) - Text components and formatting (included in Paper)
- **Java 17+** - Modern Java features

### Key Minecraft Systems Used
- **PersistentDataContainer** - NBT storage on ItemStacks
- **Bukkit Inventory** - GUI system for backpack contents
- **Event System** - PlayerInteractEvent, InventoryClickEvent, InventoryCloseEvent
- **YAML Configuration** - Both plugin config and item serialization

## Code Structure

### Package
```
com.supafloof.backpacks
```

### Main Class
```java
public class Backpacks extends JavaPlugin implements Listener, TabCompleter
```

The plugin implements:
- `JavaPlugin` - Standard plugin lifecycle (onEnable, onDisable)
- `Listener` - Event handling for inventory and player interactions
- `TabCompleter` - Command tab completion

## Two Backpack Systems

The plugin provides two distinct backpack systems:

### 1. Personal Backpacks (Command-Based)

Accessed via the `/bp` command, personal backpacks are:
- **Per-player**: One backpack per player, tied to their UUID
- **Fixed capacity**: Always 54 slots (double chest size)
- **Permission-gated**: Requires `backpacks.use` permission
- **No physical item**: Exists only as stored data
- **Storage key format**: `personal-{PlayerUUID}`

### 2. Item-Based Backpacks

Physical items that can be given, traded, dropped, and stored:
- **Tradeable**: Can be given to other players, stored in chests
- **No permission required**: Any player can use backpack items
- **Upgradeable**: Start at 27 slots, upgradeable to 54 with a doubler
- **Unique storage**: Each backpack item has its own UUID and storage
- **Storage key format**: Random UUID (e.g., `a1b2c3d4-e5f6-7890-abcd-ef1234567890`)

## Data Storage Architecture

### Three-Tier Storage System

#### 1. NBT Storage (on items)
Stored on backpack ItemStacks via PersistentDataContainer:
```java
// NamespacedKey objects
backpackKey      // Boolean - identifies item as a backpack
backpackSizeKey  // Integer - capacity (27 or 54)
backpackUUIDKey  // String - links to storage file
doublerKey       // Boolean - identifies item as a doubler
```

#### 2. Memory Storage (runtime)
```java
// Main storage map
Map<String, Map<Integer, ItemStack>> backpackStorage
// Key: BackpackUUID (or "personal-{PlayerUUID}")
// Value: Map of SlotIndex → ItemStack

// Active session tracking
Map<UUID, Inventory> activeBackpacks
// Key: PlayerUUID
// Value: Currently open Inventory

Map<UUID, String> openBackpackUUIDs
// Key: PlayerUUID  
// Value: BackpackUUID being viewed
```

#### 3. File Storage (persistent)
```
plugins/Backpacks/playerdata/<UUID>.yml
```

YAML structure:
```yaml
slot:
  '0': <serialized ItemStack>
  '5': <serialized ItemStack>
  '10': <serialized ItemStack>
```

**Note:** The storage directory is `playerdata/`, not `data/`.

### Data Flow Diagram

```
Player right-clicks backpack item
         ↓
Read UUID from item NBT (backpack_uuid)
         ↓
Load contents from backpackStorage Map
         ↓
Create Bukkit Inventory GUI (27 or 54 slots)
         ↓
Track session in activeBackpacks + openBackpackUUIDs
         ↓
Player modifies contents
         ↓
Player closes inventory (ESC or inventory key)
         ↓
InventoryCloseEvent fires
         ↓
Clone all items, save to backpackStorage Map
         ↓
Write immediately to <UUID>.yml file
         ↓
Remove from tracking maps
```

## Key Systems

### 1. Item Creation System

#### Backpack Creation (`createBackpack()`)

**Process:**
1. Create ItemStack with configured material (default: BARREL)
2. Generate unique UUID via `UUID.randomUUID()`
3. Build display name: "Backpack" in gold, bold, no italics
4. Build lore with capacity and usage instructions
5. Add UNBREAKING I enchantment (visual glow only)
6. Hide enchantment text via ItemFlag.HIDE_ENCHANTS
7. Store NBT data in PersistentDataContainer
8. Initialize empty storage map entry

**NBT Data:**
- `backpack` = true (Boolean)
- `backpack_size` = 27 (Integer)
- `backpack_uuid` = "<uuid>" (String)

#### Doubler Creation (`createDoubler()`)

**Process:**
1. Create PAPER ItemStack
2. Build display name: "Backpack Capacity Doubler" in aqua, bold
3. Build lore explaining upgrade (27 → 54 slots)
4. Add enchantment glow
5. Store NBT marker (no UUID - doublers are consumable)

**NBT Data:**
- `doubler` = true (Boolean)

### 2. Item Detection System

#### Detection Methods
```java
private boolean isBackpack(ItemStack item)
private boolean isDoubler(ItemStack item)
private int getBackpackCapacity(ItemStack item)
private String getBackpackUUID(ItemStack item)
```

**Detection Logic:**
1. Null check on ItemStack
2. Check if hasItemMeta()
3. Access PersistentDataContainer
4. Check for specific NamespacedKey presence

**Why NBT instead of lore/name?**
- NBT survives anvil renaming
- NBT cannot be manipulated by players
- NBT is more performant than string parsing
- NBT supports typed data (Boolean, Integer, String)

### 3. Inventory Management System

#### Opening Item-Based Backpacks (`openBackpack()`)

**Process:**
1. Extract UUID from item's PersistentDataContainer
2. Validate UUID exists (error if corrupted)
3. Get capacity from NBT (27 or 54)
4. Create Bukkit inventory: `Bukkit.createInventory(null, capacity, title)`
5. Load items from backpackStorage map into inventory slots
6. Track session in both maps:
   - `activeBackpacks.put(playerUUID, inventory)`
   - `openBackpackUUIDs.put(playerUUID, backpackUUID)`
7. Open GUI for player
8. Send confirmation message

**Title Format:** `"Backpack (27 slots)"` or `"Backpack (54 slots)"`

#### Opening Personal Backpacks (`openPersonalBackpack()`)

**Process:**
1. Generate storage key: `"personal-" + player.getUniqueId().toString()`
2. Create 54-slot Bukkit inventory with title "Personal Backpack"
3. Load stored items from backpackStorage
4. Track session in both maps
5. Open GUI and send confirmation

#### Saving Backpacks (`saveBackpackContents()`)

**Process:**
1. Verify player has active backpack session
2. Get Inventory and backpack UUID from tracking maps
3. Iterate through all inventory slots
4. Clone each non-empty ItemStack (prevents reference issues)
5. Build contents map (skip AIR items)
6. Update in-memory backpackStorage
7. Write to YAML file immediately via `saveBackpackToFile()`

**Critical:** Items are CLONED before storage to prevent modifications to the inventory affecting stored data or vice versa.

### 4. Upgrade System

#### Upgrade Process (`upgradeBackpack()`)

**Process:**
1. Modify NBT: `backpack_size` = 54
2. Rebuild lore with new capacity
3. Add "✦ UPGRADED ✦" badge in light purple
4. UUID remains unchanged (contents preserved)

**Important:** This method only modifies the item's metadata. The storage system already supports 54 slots, so existing items in slots 0-26 remain in place and slots 27-53 become available.

#### Upgrade Detection (in InventoryClickEvent)
```java
if (isDoubler(cursor) && isBackpack(clicked))
```

**Validation:**
1. Backpack not stacked (amount must be 1)
2. Current capacity < 54
3. Consume one doubler from cursor
4. Apply upgrade to clicked backpack
5. Play ENTITY_PLAYER_LEVELUP sound (pitch 1.5f)
6. Send success message

### 5. Event Handling System

#### PlayerInteractEvent (HIGHEST priority)
```java
@EventHandler(priority = EventPriority.HIGHEST)
public void onPlayerInteract(PlayerInteractEvent event)
```

**Triggers:** Right-click air or block while holding backpack item

**Actions:**
1. Verify action is RIGHT_CLICK_AIR or RIGHT_CLICK_BLOCK
2. Check if held item is a backpack
3. Cancel event (prevent block placement/interaction)
4. Open backpack GUI

**Priority:** HIGHEST ensures other plugins (protection, etc.) process first

**No permission check:** Item-based backpacks work for all players

#### InventoryClickEvent
```java
@EventHandler
public void onInventoryClick(InventoryClickEvent event)
```

**Handles two scenarios:**

**Scenario 1: Doubler Application**
- Cursor contains doubler
- Clicked slot contains backpack
- Validate backpack not stacked
- Validate not already 54 slots
- Upgrade backpack, consume doubler

**Scenario 2: Nested Backpack Prevention**
- Player has backpack open (in activeBackpacks)
- Clicked inventory is the active backpack
- Cursor contains a backpack
- Config `allow-nested-backpacks` is false
- Cancel click and notify player

#### InventoryCloseEvent
```java
@EventHandler
public void onInventoryClose(InventoryCloseEvent event)
```

**Process:**
1. Verify closer is a Player
2. Check if player has entry in activeBackpacks
3. Save contents to storage and disk
4. Remove from activeBackpacks map
5. Remove from openBackpackUUIDs map
6. Send "Backpack saved!" confirmation

### 6. Command System

#### Command Structure
```
/bp                                    - Open personal backpack
/backpack help                         - Show help menu
/backpack give <backpack|doubler> <player>  - Give items
/backpack reload                       - Reload configuration
```

#### Command Handler (`onCommand()`)

**Routing logic:**
- `/bp` with no args → Check player, check `backpacks.use` permission, open personal backpack
- `/backpack` with no args → Show help
- `/backpack give` → Route to `handleGive()`
- `/backpack reload` → Route to `handleReload()`
- `/backpack help` → Show help
- Unknown subcommand → Show help

#### Permission Checks
Permissions are checked in sub-handlers:
- `handleGive()` checks `backpacks.give`
- `handleReload()` checks `backpacks.admin`
- Personal backpack checks `backpacks.use`

#### Help Menu (`sendHelp()`)

Permission-filtered display:
- `backpacks.use` → Shows /bp command
- `backpacks.give` → Shows give commands
- `backpacks.admin` → Shows reload command
- No permission → Shows help command (always visible)

#### Tab Completion (`onTabComplete()`)

**Completion logic:**
- `/bp` → Empty list (no arguments)
- `/backpack` position 1 → Subcommands filtered by permission
- `/backpack give` position 2 → "backpack", "doubler"
- `/backpack give <type>` position 3 → Online player names

## Configuration System

### Config Loading
```java
saveDefaultConfig();  // Creates config.yml if missing
reloadConfig();       // Reloads from disk
getConfig();          // Access configuration
```

### Configuration Options

```yaml
# config.yml
allow-nested-backpacks: false  # Prevent backpack-in-backpack
backpack-item: BARREL          # Material for backpack items
```

### Reading Values
```java
// Material with fallback
String materialName = getConfig().getString("backpack-item", "BARREL");

// Boolean with default
boolean nested = getConfig().getBoolean("allow-nested-backpacks", false);
```

### Material Validation (`getBackpackMaterial()`)
```java
try {
    Material mat = Material.valueOf(materialName.toUpperCase());
    return mat;
} catch (IllegalArgumentException e) {
    getLogger().warning("Invalid backpack-item: " + materialName + ", using BARREL");
    return Material.BARREL;
}
```

## Lifecycle Management

### Plugin Startup (`onEnable()`)

**Sequence:**
1. Display startup message (green text)
2. Display author credit (light purple/magenta text)
3. Initialize four NamespacedKey objects
4. Save default config
5. Load backpack storage from YAML files
6. Register event listener
7. Register command executors for `/backpack` and `/bp`
8. Register tab completer
9. Log successful enable

### Plugin Shutdown (`onDisable()`)

**Sequence:**
1. Iterate through all entries in activeBackpacks map
2. For each player with open backpack:
   - Save contents to file
   - Force close their inventory
3. Clear activeBackpacks map
4. Clear openBackpackUUIDs map
5. Log successful disable

**Critical:** This ensures no data loss on server shutdown, even if players have backpacks open.

## Extension Points

### Adding New Commands

```java
// In onCommand() switch statement
case "mynewcommand":
    return handleMyNewCommand(sender, args);

// Implement handler
private boolean handleMyNewCommand(CommandSender sender, String[] args) {
    // Permission check
    if (!sender.hasPermission("backpacks.newperm")) {
        sender.sendMessage(Component.text("No permission!", NamedTextColor.RED));
        return true;
    }
    // Validation
    // Logic
    // Feedback
    return true;
}

// Add to tab completion
if (sender.hasPermission("backpacks.newperm")) {
    completions.add("mynewcommand");
}
```

### Adding New Item Types

```java
// 1. Add NamespacedKey field
private NamespacedKey myItemKey;

// 2. Initialize in onEnable()
myItemKey = new NamespacedKey(this, "myitem");

// 3. Create item method
private ItemStack createMyItem() {
    ItemStack item = new ItemStack(Material.SOMETHING);
    ItemMeta meta = item.getItemMeta();
    // Set display name, lore
    // Add enchant glow if desired
    PersistentDataContainer pdc = meta.getPersistentDataContainer();
    pdc.set(myItemKey, PersistentDataType.BOOLEAN, true);
    item.setItemMeta(meta);
    return item;
}

// 4. Detection method
private boolean isMyItem(ItemStack item) {
    if (item == null || !item.hasItemMeta()) return false;
    return item.getItemMeta().getPersistentDataContainer()
        .has(myItemKey, PersistentDataType.BOOLEAN);
}
```

### Adding Configuration Options

```java
// 1. Add to default config.yml with comments
// 2. Read value in code with default
int myValue = getConfig().getInt("my-option", defaultValue);
// 3. Document in server admin guide
```

## Best Practices

### Code Style (SupaFloof Standards)

1. **Console Messages:**
   - Green for startup/success
   - Light purple/magenta for author credit
   - Red for errors
   ```java
   Component.text("[Backpacks] Started!", NamedTextColor.GREEN)
   Component.text("[Backpacks] By SupaFloof Games, LLC", NamedTextColor.LIGHT_PURPLE)
   ```

2. **JavaDoc Comments:**
   - Every method documented
   - Explain parameters and returns
   - Document side effects and important notes

3. **Adventure API:**
   - Use Component instead of String for messages
   - Explicitly disable italic on lore (Minecraft defaults to italic for custom lore)
   ```java
   .decoration(TextDecoration.ITALIC, false)
   ```

4. **NBT Data:**
   - Use PersistentDataContainer exclusively
   - Never use deprecated NBT methods
   - Always specify data type explicitly

### Performance Considerations

1. **Clone Items When Storing:**
   ```java
   contents.put(i, item.clone());
   ```
   Prevents reference issues between inventory and storage.

2. **Immediate File Saves:**
   ```java
   saveBackpackToFile(uuid, contents);
   ```
   No batching - prevents data loss on crashes.

3. **Early Returns:**
   ```java
   if (!activeBackpacks.containsKey(playerId)) return;
   ```
   Prevents unnecessary processing.

4. **Event Priority:**
   ```java
   @EventHandler(priority = EventPriority.HIGHEST)
   ```
   Use HIGHEST for backpack opens so protection plugins run first.

### Security Considerations

1. **Permission Checks:**
   ```java
   if (!sender.hasPermission("backpacks.give")) {
       // Deny
   }
   ```
   Always check before privileged operations.

2. **Nested Backpack Prevention:**
   ```java
   if (!getConfig().getBoolean("allow-nested-backpacks", false)) {
       // Prevent placing backpack in backpack
   }
   ```
   Prevents potential duplication exploits.

3. **UUID Validation:**
   ```java
   if (backpackUUID == null) {
       player.sendMessage("Error: Invalid backpack!");
       return;
   }
   ```
   Never trust NBT data completely.

4. **Stack Size Validation:**
   ```java
   if (clicked.getAmount() > 1) {
       // Prevent upgrade on stacked backpacks
   }
   ```

### Error Handling

1. **Graceful Degradation:**
   ```java
   try {
       config.save(backpackFile);
   } catch (Exception e) {
       getLogger().warning("Failed to save: " + e.getMessage());
       e.printStackTrace();
   }
   ```
   Log but don't crash - data is still safe in memory.

2. **Null Checks:**
   ```java
   if (item == null || !item.hasItemMeta()) {
       return false;
   }
   ```
   Always validate before accessing.

3. **Default Values:**
   ```java
   return pdc.getOrDefault(backpackSizeKey, PersistentDataType.INTEGER, 27);
   ```
   Use sensible defaults for missing data.

## Testing Checklist

### Manual Testing

- [ ] Create new backpack via `/backpack give backpack <player>`
- [ ] Open and close item backpack (verify "Backpack saved!" message)
- [ ] Add/remove items from backpack
- [ ] Verify persistence (restart server, check items remain)
- [ ] Apply doubler to backpack (verify upgrade message and sound)
- [ ] Attempt doubler on already-upgraded backpack (should fail)
- [ ] Attempt doubler on stacked backpacks (should fail)
- [ ] Attempt to place backpack inside backpack (should fail by default)
- [ ] Test `/bp` command with `backpacks.use` permission
- [ ] Test `/bp` command without permission (should deny)
- [ ] Test `/backpack give` without `backpacks.give` permission
- [ ] Test `/backpack reload` with and without `backpacks.admin`
- [ ] Verify tab completion shows permission-appropriate options
- [ ] Test with different materials in config
- [ ] Test with invalid config values (should fallback gracefully)
- [ ] Verify personal backpack storage is separate from item backpacks

### Edge Cases

1. **Backpack UUID collision:** Extremely unlikely with UUID.randomUUID()
2. **File corruption:** Individual files isolate damage
3. **Concurrent access:** Bukkit is single-threaded for events
4. **Memory leaks:** Maps cleared on disable and close events
5. **Server crash during save:** Immediate saves minimize window
6. **Item duplication:** Prevented by nested backpack check

## API for Other Plugins

While not an official API, other plugins can interact:

### Detecting Backpacks
```java
ItemStack item = player.getInventory().getItemInMainHand();
if (item.hasItemMeta()) {
    PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
    NamespacedKey key = new NamespacedKey(backpacksPlugin, "backpack");
    if (pdc.has(key, PersistentDataType.BOOLEAN)) {
        // It's a backpack!
        
        // Get capacity
        NamespacedKey sizeKey = new NamespacedKey(backpacksPlugin, "backpack_size");
        int capacity = pdc.getOrDefault(sizeKey, PersistentDataType.INTEGER, 27);
        
        // Get UUID
        NamespacedKey uuidKey = new NamespacedKey(backpacksPlugin, "backpack_uuid");
        String uuid = pdc.get(uuidKey, PersistentDataType.STRING);
    }
}
```

### Giving via Commands
```java
Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
    "backpack give backpack " + playerName);
```

## File Structure Summary

```
plugins/Backpacks/
├── config.yml                    # Plugin configuration
└── playerdata/                   # Backpack storage directory
    ├── <random-uuid>.yml         # Item-based backpack storage
    ├── <random-uuid>.yml         # Item-based backpack storage
    └── personal-<player-uuid>.yml # Personal backpack storage
```

## Troubleshooting Development Issues

### Plugin Not Loading
- Check Java version (17+)
- Verify plugin.yml exists and is valid
- Check for syntax errors in main class
- Review console for stack traces

### Items Not Saving
- Verify saveBackpackContents() is called on close
- Check file permissions on playerdata/ directory
- Ensure saveBackpackToFile() completes without exception
- Look for exceptions in console

### NBT Data Not Persisting
- Ensure setItemMeta() is called after modifying PDC
- Verify NamespacedKey is initialized in onEnable()
- Check that item isn't being replaced without copying NBT

### Memory Leaks
- Ensure maps are cleared in onDisable()
- Verify inventory close events remove tracking entries
- Check that event handlers don't accumulate references

---

## Development Resources

### Official Documentation
- [Spigot API JavaDocs](https://hub.spigotmc.org/javadocs/spigot/)
- [Adventure API Docs](https://docs.adventure.kyori.net/)
- [Paper API Docs](https://papermc.io/javadocs/)

### Useful Tools
- [IntelliJ IDEA](https://www.jetbrains.com/idea/) - Recommended IDE
- [Maven](https://maven.apache.org/) - Build tool
- [BuildTools](https://www.spigotmc.org/wiki/buildtools/) - Get Spigot/Paper APIs

---

**Happy developing!** 🎒💻

**SupaFloof Games, LLC**
