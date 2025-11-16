# Backpacks - Developer Guide

## Architecture Overview

Backpacks is a single-file Paper/Spigot plugin that implements a portable storage system using Minecraft's inventory system and persistent data containers.

### Core Design Principles

1. **Single-file architecture** - All code in one class for simplicity
2. **Immediate persistence** - Data is saved on every inventory close
3. **UUID-based storage** - Each backpack has a unique identifier
4. **Adventure API** - Modern text component system
5. **NBT-based identification** - Items identified via PersistentDataContainer

## Technology Stack

### Required APIs
- **Bukkit/Spigot API** - Core Minecraft server API
- **Adventure API** (net.kyori) - Text components and formatting
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
- `JavaPlugin` - Standard plugin lifecycle
- `Listener` - Event handling
- `TabCompleter` - Command tab completion

## Data Storage Architecture

### Three-Tier Storage System

#### 1. NBT Storage (on items)
Stored on backpack ItemStacks via PersistentDataContainer:
```java
// Keys
backpackKey      - Boolean marker (identifies as backpack)
backpackSizeKey  - Integer capacity (27 or 54)
backpackUUIDKey  - String UUID (links to storage)
doublerKey       - Boolean marker (identifies as doubler)
```

#### 2. Memory Storage (runtime)
```java
// Main storage map
Map<String, Map<Integer, ItemStack>> backpackStorage
// Outer: BackpackUUID -> Inner Map
// Inner: SlotIndex -> ItemStack

// Active session tracking
Map<UUID, Inventory> activeBackpacks
// PlayerUUID -> Open Inventory

Map<UUID, String> openBackpackUUIDs
// PlayerUUID -> BackpackUUID
```

#### 3. File Storage (persistent)
```
plugins/Backpacks/data/<UUID>.yml
```

YAML structure:
```yaml
slot:
  '0': <ItemStack>
  '5': <ItemStack>
  '10': <ItemStack>
```

### Data Flow Diagram

```
Player right-clicks backpack
         ↓
Read UUID from item NBT
         ↓
Load from backpackStorage Map
         ↓
Create Bukkit Inventory GUI
         ↓
Track in activeBackpacks Map
         ↓
Player modifies contents
         ↓
Player closes inventory
         ↓
Save to backpackStorage Map
         ↓
Write to <UUID>.yml file
```

## Key Systems

### 1. Item Creation System

#### Backpack Creation
```java
private ItemStack createBackpack()
```

**Process:**
1. Create ItemStack with configured material
2. Generate unique UUID (UUID.randomUUID())
3. Build display name and lore with Adventure API
4. Add UNBREAKING enchantment (visual glow)
5. Hide enchantment tooltip
6. Store NBT data (marker, size, UUID)
7. Initialize empty storage map entry

**NBT Data:**
- `backpack` = true (Boolean)
- `backpack_size` = 27 (Integer)
- `backpack_uuid` = "<uuid>" (String)

#### Doubler Creation
```java
private ItemStack createDoubler()
```

**Process:**
1. Create PAPER ItemStack
2. Build display name and lore
3. Add enchantment glow
4. Store NBT marker
5. No UUID (doublers are consumable)

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
2. Check if has ItemMeta
3. Access PersistentDataContainer
4. Check for specific NamespacedKey

**Why NBT instead of lore/name?**
- NBT survives anvil renaming
- NBT cannot be manipulated by players
- NBT is more performant than string parsing
- NBT supports complex data types

### 3. Inventory Management System

#### Opening Backpacks
```java
private void openBackpack(Player player, ItemStack backpack)
```

**Process:**
1. Extract UUID from item
2. Get capacity (27 or 54)
3. Create Bukkit inventory: `Bukkit.createInventory(null, capacity, title)`
4. Load items from storage map
5. Track session in two maps:
   - `activeBackpacks.put(playerUUID, inventory)`
   - `openBackpackUUIDs.put(playerUUID, backpackUUID)`
6. Open GUI for player

**Title Format:** `"Backpack (27 slots)"` or `"Backpack (54 slots)"`

#### Saving Backpacks
```java
private void saveBackpackContents(Player player)
private void saveBackpackToFile(String uuid, Map<Integer, ItemStack> contents)
```

**Process:**
1. Get player's open inventory and backpack UUID
2. Iterate through all slots
3. Clone non-empty ItemStacks (prevents reference issues)
4. Build contents map (skip AIR items)
5. Update in-memory storage
6. Write to YAML file immediately

**Critical:** Items are cloned to prevent modifications to the inventory affecting stored data.

### 4. Upgrade System

#### Upgrade Process
```java
private ItemStack upgradeBackpack(ItemStack backpack)
```

**Process:**
1. Modify NBT: `backpack_size` = 54
2. Update lore to reflect new capacity
3. Add "✦ UPGRADED ✦" badge
4. UUID remains unchanged (contents preserved)

**Important:** This method only modifies the item, not the storage. The storage map already supports 54 slots, so no data migration is needed.

#### Upgrade Detection
Handled in `InventoryClickEvent`:
```java
if (isDoubler(cursor) && isBackpack(clicked))
```

**Validation:**
1. Backpack not stacked (amount must be 1)
2. Current capacity < 54
3. Consume one doubler
4. Apply upgrade
5. Play sound and send message

### 5. Event Handling System

#### PlayerInteractEvent (HIGHEST priority)
```java
@EventHandler(priority = EventPriority.HIGHEST)
public void onPlayerInteract(PlayerInteractEvent event)
```

**Triggers:** Right-click air or block with backpack

**Actions:**
1. Detect right-click action
2. Check if item is backpack
3. Cancel event (prevent block placement)
4. Open backpack GUI

**Priority:** HIGHEST ensures other plugins process first (protection plugins, etc.)

#### InventoryClickEvent
```java
@EventHandler
public void onInventoryClick(InventoryClickEvent event)
```

**Handles two scenarios:**

**Scenario 1: Doubler Application**
- Cursor has doubler
- Clicked slot has backpack
- Validate and upgrade
- Consume doubler

**Scenario 2: Nested Backpack Prevention**
- Player has backpack open
- Clicked inventory is active backpack
- Cursor has backpack
- Config disallows nesting
- Cancel and notify

#### InventoryCloseEvent
```java
@EventHandler
public void onInventoryClose(InventoryCloseEvent event)
```

**Process:**
1. Check if closed inventory is tracked in activeBackpacks
2. Save contents to storage
3. Write to file
4. Remove from tracking maps
5. Send confirmation message

### 6. Command System

#### Command Structure
```
/backpack help
/backpack give <backpack|doubler> <player>
/backpack reload
```

#### Command Handler
```java
@Override
public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
```

**Routing:**
- No args → Show help
- "give" → `handleGive()`
- "reload" → `handleReload()`
- "help" → Show help
- Unknown → Show help

#### Permission Checks
```java
if (!sender.hasPermission("backpacks.give")) {
    // Deny and notify
}
```

Permissions checked in sub-handlers, not main router.

#### Tab Completion
```java
@Override
public List<String> onTabComplete(...)
```

**Completion Logic:**
- Args 1: Subcommands (filtered by permission)
- Args 2: Item types (if "give")
- Args 3: Player names (if "give <type>")

**Filtering:** Case-insensitive startsWith matching

## Configuration System

### Config Loading
```java
saveDefaultConfig();  // Creates config.yml if missing
reloadConfig();       // Reloads from disk
getConfig();          // Access configuration
```

### Reading Values
```java
// Material with fallback
String materialName = getConfig().getString("backpack-item", "BARREL");

// Boolean with default
boolean nested = getConfig().getBoolean("allow-nested-backpacks", false);
```

### Material Validation
```java
try {
    Material mat = Material.valueOf(materialName.toUpperCase());
    return mat;
} catch (IllegalArgumentException e) {
    getLogger().warning("Invalid material, using BARREL");
    return Material.BARREL;
}
```

## Lifecycle Management

### Plugin Startup
```java
@Override
public void onEnable()
```

**Sequence:**
1. Display console messages (green/magenta per standards)
2. Initialize NamespacedKeys
3. Save default config
4. Load backpack storage from files
5. Register event listener
6. Register command executor and tab completer

### Plugin Shutdown
```java
@Override
public void onDisable()
```

**Sequence:**
1. Iterate active backpacks
2. Save each to file
3. Close player inventories
4. Clear tracking maps
5. Display console message

**Critical:** This ensures no data loss on server shutdown.

## Extension Points

### Adding New Commands

```java
// In onCommand() switch statement
case "mynewcommand":
    return handleMyNewCommand(sender, args);

// Implement handler
private boolean handleMyNewCommand(CommandSender sender, String[] args) {
    // Permission check
    // Validation
    // Logic
    // Feedback
    return true;
}
```

### Adding New Item Types

```java
// 1. Add NamespacedKey
private NamespacedKey myItemKey;

// 2. Initialize in onEnable()
myItemKey = new NamespacedKey(this, "myitem");

// 3. Create item method
private ItemStack createMyItem() {
    ItemStack item = new ItemStack(Material.SOMETHING);
    // Set meta
    pdc.set(myItemKey, PersistentDataType.BOOLEAN, true);
    return item;
}

// 4. Detection method
private boolean isMyItem(ItemStack item) {
    if (item == null || !item.hasItemMeta()) return false;
    return item.getItemMeta().getPersistentDataContainer()
        .has(myItemKey, PersistentDataType.BOOLEAN);
}
```

### Adding Event Handlers

```java
@EventHandler
public void onMyEvent(MyEvent event) {
    // Check if involves backpack
    if (isBackpack(event.getItem())) {
        // Custom logic
    }
}
```

### Adding Configuration Options

```java
// 1. Add to config.yml default
// 2. Read in code
int myValue = getConfig().getInt("my-option", defaultValue);

// 3. Document in comments
```

## Best Practices

### Code Style (SupaFloof Standards)

1. **Console Messages:**
   - Green for startup/success
   - Magenta for author credit
   - Red for shutdown/errors
   ```java
   Component.text("[Backpacks] Started!", NamedTextColor.GREEN)
   ```

2. **JavaDoc Comments:**
   - Every public/private method
   - Explain parameters and returns
   - Document side effects

3. **Adventure API:**
   - Use Component instead of String for messages
   - Explicitly disable italic on lore
   ```java
   .decoration(TextDecoration.ITALIC, false)
   ```

4. **NBT Data:**
   - Use PersistentDataContainer
   - Never use deprecated NBT methods
   - Always specify data type explicitly

### Performance Considerations

1. **Clone Items When Storing:**
   ```java
   contents.put(i, item.clone());
   ```
   Prevents reference issues.

2. **Immediate File Saves:**
   ```java
   saveBackpackToFile(uuid, contents);
   ```
   No batching - prevents data loss but may impact performance with thousands of saves per second.

3. **Map Lookups:**
   ```java
   if (!activeBackpacks.containsKey(playerId)) return;
   ```
   Early returns prevent unnecessary processing.

4. **Event Priority:**
   ```java
   @EventHandler(priority = EventPriority.HIGHEST)
   ```
   Use appropriate priority for event cancellation.

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
       // Prevent
   }
   ```
   Prevents duplication exploits.

3. **UUID Validation:**
   ```java
   if (backpackUUID == null) {
       player.sendMessage("Error: Invalid backpack!");
       return;
   }
   ```
   Never trust NBT data completely.

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
   Log but don't crash - data is still in memory.

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

## Testing Considerations

### Manual Testing Checklist

- [ ] Create new backpack
- [ ] Open and close backpack
- [ ] Add/remove items
- [ ] Verify persistence (restart server)
- [ ] Apply doubler to backpack
- [ ] Attempt nested backpack (should fail)
- [ ] Test with stacked backpack (doubler should fail)
- [ ] Test permissions (give, reload)
- [ ] Test tab completion
- [ ] Test with different materials in config
- [ ] Test with invalid config values

### Edge Cases

1. **Backpack UUID collision:** Extremely unlikely (UUID.randomUUID())
2. **File corruption:** Individual files, isolated damage
3. **Concurrent access:** Maps are not thread-safe but Bukkit is single-threaded
4. **Memory leaks:** Maps cleared on disable and close events
5. **Item duplication:** Prevented by nested backpack check

## Common Development Tasks

### Adding a New Permission

1. Define permission node (e.g., `backpacks.newfeature`)
2. Check in command/event handler
3. Document in permissions section
4. Add to tab completion filter

### Adding a New Configuration Option

1. Add to default config.yml with comments
2. Read value in code with default
3. Apply validation if needed
4. Document in server admin guide

### Debugging

Enable verbose logging:
```java
getLogger().info("Debug: " + variableName);
```

Check backpack UUID:
```java
getLogger().info("Backpack UUID: " + getBackpackUUID(item));
```

List active sessions:
```java
getLogger().info("Active backpacks: " + activeBackpacks.size());
```

## API for Other Plugins

While not officially an API, other plugins can interact with backpacks:

### Detecting Backpacks
```java
// Check if item is a backpack (NBT-based)
ItemStack item = player.getInventory().getItemInMainHand();
if (item.hasItemMeta()) {
    PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
    NamespacedKey key = new NamespacedKey(backpacksPlugin, "backpack");
    if (pdc.has(key, PersistentDataType.BOOLEAN)) {
        // It's a backpack!
    }
}
```

### Getting Commands
```java
// Give backpack via other plugin
Bukkit.dispatchCommand(Bukkit.getConsoleSender(), 
    "backpack give backpack " + playerName);
```

## Future Enhancement Ideas

### Potential Features
1. **Backpack tiers:** Small (9), Medium (27), Large (54)
2. **Color-coded backpacks:** Different materials/colors
3. **Backpack rental system:** Time-limited backpacks
4. **Shared backpacks:** Multi-player access (risky)
5. **Auto-sort feature:** Organize contents automatically
6. **Search feature:** Find items in backpack
7. **Backpack preview:** See contents without opening
8. **Database storage:** MySQL/SQLite instead of YAML
9. **Backup backpack command:** Admin tool to view any backpack
10. **Statistics:** Track usage, most stored item, etc.

### Implementation Considerations
- Database storage would improve scalability but add complexity
- Shared backpacks would need concurrency locks
- Tiers would need multiple item creation methods
- Search would need inventory scanning API

## Troubleshooting Development Issues

### Plugin Not Loading
- Check Java version (17+)
- Verify plugin.yml exists
- Check for syntax errors
- Review console for stack traces

### Items Not Saving
- Verify PersistentDataContainer is being written
- Check file permissions on data/ directory
- Ensure saveBackpackToFile() is being called
- Look for exceptions in console

### NBT Data Not Persisting
- Ensure setItemMeta() is called after modifying PDC
- Verify NamespacedKey is initialized
- Check that item isn't being replaced without copying NBT

### Memory Leaks
- Ensure maps are cleared on plugin disable
- Check that event handlers don't accumulate references
- Verify inventory close events remove tracking entries

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

### Community Resources
- [SpigotMC Forums](https://www.spigotmc.org/forums/)
- [Paper Discord](https://discord.gg/papermc)
- [Bukkit Forums](https://bukkit.org/)

---

**Happy developing!** 🎒💻

**SupaFloof Games, LLC**
