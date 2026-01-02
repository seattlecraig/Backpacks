# Backpacks - Server Admin Guide

## Overview

Backpacks is a lightweight, performant plugin that provides two types of portable storage for your players:

1. **Personal Backpacks** - Per-player storage accessed via the `/bp` command (54 slots, requires permission)
2. **Item-Based Backpacks** - Physical items that can be given, traded, and used by any player (27 or 54 slots)

## Requirements

- **Minecraft Server:** Paper/Spigot 1.19+ (or any server supporting Adventure API)
- **Java Version:** Java 17+
- **Dependencies:** None! Backpacks is a standalone plugin

## Installation

1. Download the `Backpacks.jar` file
2. Place it in your server's `plugins/` directory
3. Restart your server (or use a plugin manager to load it)
4. The plugin will create its configuration files automatically

### First Startup

On first startup, you'll see:
```
[Backpacks] Backpacks Started!
[Backpacks] By SupaFloof Games, LLC
[Backpacks] No playerdata directory found, starting fresh
[Backpacks] Backpacks plugin enabled!
```

The plugin will create:
- `plugins/Backpacks/config.yml` - Configuration file
- `plugins/Backpacks/playerdata/` - Directory for backpack storage files

## Configuration

### config.yml

```yaml
# Backpacks Configuration
# By SupaFloof Games, LLC

# Allow backpacks to be placed in other backpacks (not recommended - can cause duplication bugs)
allow-nested-backpacks: false

# Material type for backpack item (default: BARREL)
# Must be a valid Bukkit Material name
backpack-item: BARREL
```

### Configuration Options Explained

#### allow-nested-backpacks
- **Type:** Boolean
- **Default:** `false`
- **Description:** Controls whether players can place backpack items inside other backpacks
- **⚠️ Warning:** Setting this to `true` is NOT RECOMMENDED as it can potentially cause duplication exploits
- **Use Case:** Keep this `false` unless you have a specific reason and understand the risks

#### backpack-item
- **Type:** Material name (string)
- **Default:** `BARREL`
- **Description:** The Minecraft material used to represent backpack items
- **Valid Values:** Any valid Bukkit Material (e.g., `BARREL`, `CHEST`, `ENDER_CHEST`, `BUNDLE`)
- **Note:** If an invalid material is specified, the plugin falls back to `BARREL` and logs a warning
- **Tip:** Use distinctive items to make backpacks easily recognizable

### Changing Configuration

After editing `config.yml`, you can reload without restarting:

```
/backpack reload
```

**Note:** Configuration changes only affect:
- Newly created backpacks (material type)
- Future nested backpack checks

Configuration changes do NOT affect:
- Existing backpack items (they keep their original material)
- Already open backpacks
- Stored backpack data

## Commands

### Player Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/bp` | Open your personal backpack (54 slots) | `backpacks.use` |
| `/backpack help` | Show help menu | None (default) |

### Admin Commands

| Command | Description | Permission | Usage |
|---------|-------------|------------|-------|
| `/backpack give backpack <player>` | Give a backpack item (27 slots) | `backpacks.give` | `/backpack give backpack Notch` |
| `/backpack give doubler <player>` | Give a capacity doubler | `backpacks.give` | `/backpack give doubler Steve` |
| `/backpack reload` | Reload configuration | `backpacks.admin` | `/backpack reload` |

### Command Examples

```bash
# Give a backpack to a player
/backpack give backpack PlayerName

# Give multiple doublers using multiple commands
/backpack give doubler PlayerName
/backpack give doubler PlayerName
/backpack give doubler PlayerName

# Give to yourself (if you're a player)
/backpack give backpack YourName

# Reload configuration after changes
/backpack reload
```

## Permissions

### Permission Nodes

| Permission | Description | Default | Recommended For |
|-----------|-------------|---------|-----------------|
| `backpacks.use` | Access personal backpack via /bp | OP | All players (if desired) |
| `backpacks.give` | Give backpacks and doublers to players | OP | Admins, Moderators |
| `backpacks.admin` | Reload configuration | OP | Server Admins |

### Setting Up Permissions

**Using LuckPerms:**
```bash
# Give all players access to personal backpacks
/lp group default permission set backpacks.use true

# Give admin permissions
/lp group admin permission set backpacks.give true
/lp group admin permission set backpacks.admin true

# Give moderators the ability to give items
/lp group moderator permission set backpacks.give true
```

**Using PermissionsEx:**
```bash
/pex group default add backpacks.use
/pex group admin add backpacks.give
/pex group admin add backpacks.admin
```

### Default Permissions

By default, only server operators (OPs) have all permissions. Players with no permissions can:
- Use backpack items they've obtained (no permission required)
- Use capacity doublers they've obtained (no permission required)
- View the help menu (no permission required)

**Note:** Item-based backpacks require NO permissions to use. Once a player has a backpack item, they can open it regardless of their permissions. Only the `/bp` personal backpack command requires the `backpacks.use` permission.

## Data Storage

### Storage System

Backpacks uses a **file-based storage system** with individual YAML files for each backpack.

**Location:** `plugins/Backpacks/playerdata/`

**File Naming:**
- Item-based backpacks: `<random-UUID>.yml` (e.g., `a1b2c3d4-e5f6-7890-abcd-ef1234567890.yml`)
- Personal backpacks: `personal-<player-UUID>.yml` (e.g., `personal-12345678-abcd-1234-5678-ef1234567890.yml`)

### Storage Architecture

```
plugins/Backpacks/
├── config.yml                         # Plugin configuration
└── playerdata/                        # Backpack storage directory
    ├── <uuid-1>.yml                   # Item backpack 1 contents
    ├── <uuid-2>.yml                   # Item backpack 2 contents
    └── personal-<player-uuid>.yml     # Player's personal backpack
```

### Example Backpack File

```yaml
slot:
  '0':
    v: 3465
    type: DIAMOND_PICKAXE
  '5':
    v: 3465
    type: GOLDEN_APPLE
    amount: 16
  '10':
    v: 3465
    type: COBBLESTONE
    amount: 64
```

### Storage Benefits

✅ **Individual files** prevent total data loss if one file corrupts
✅ **Immediate saves** ensure no data loss on crashes
✅ **Easy backup** - just copy the playerdata folder
✅ **Easy restore** - replace individual UUID files if needed
✅ **Human readable** YAML format for debugging

## Backup & Restore

### Backing Up

**Full Backup:**
```bash
# Stop server or ensure no backpacks are being modified
cp -r plugins/Backpacks/playerdata /path/to/backup/backpacks-playerdata-backup
```

**Individual Backpack Backup:**
```bash
cp plugins/Backpacks/playerdata/<uuid>.yml /path/to/backup/
```

### Restoring

**Full Restore:**
```bash
# Stop server first
rm -rf plugins/Backpacks/playerdata
cp -r /path/to/backup/backpacks-playerdata-backup plugins/Backpacks/playerdata
# Start server
```

**Individual Backpack Restore:**
```bash
# Can be done while server is running if backpack is not open
cp /path/to/backup/<uuid>.yml plugins/Backpacks/playerdata/
```

### Automated Backup Script Example

```bash
#!/bin/bash
# backup-backpacks.sh
DATE=$(date +%Y-%m-%d)
BACKUP_DIR="/backups/minecraft/backpacks"
mkdir -p "$BACKUP_DIR"
cp -r /path/to/server/plugins/Backpacks/playerdata "$BACKUP_DIR/backpacks-$DATE"
# Keep only last 7 days
find "$BACKUP_DIR" -name "backpacks-*" -mtime +7 -delete
```

Add to crontab:
```bash
0 2 * * * /path/to/backup-backpacks.sh
```

## Integration with Economy/Shops

Backpacks can be integrated into your server economy. Here are some approaches:

### Shop Plugin Integration

**Example with ShopGUI+:**
```yaml
# In ShopGUI+ config
items:
  backpack:
    material: BARREL
    name: "&6&lBackpack"
    buyPrice: 5000
    commands:
      - "backpack give backpack %player%"
  
  doubler:
    material: PAPER
    name: "&b&lCapacity Doubler"
    buyPrice: 2500
    commands:
      - "backpack give doubler %player%"
```

### Quest/Reward Integration

**CommandPrompter/DeluxeMenus:**
```yaml
reward_commands:
  - "backpack give backpack %player%"
  - "backpack give doubler %player%"
```

### Economy Examples

**Starter Kit:**
```yaml
# In your kit plugin
starter_kit:
  - backpack give backpack %player%
```

**Vote Rewards:**
```yaml
# In Votifier config
vote_rewards:
  common:
    commands:
      - "backpack give doubler %player%"
```

**Donor Perks:**
```yaml
# Give upgraded backpacks to donors
- backpack give backpack %player%
- backpack give doubler %player%
```

## Performance Considerations

### Resource Usage

- **CPU:** Minimal - Events only process on player interaction
- **RAM:** ~10-50KB per backpack in memory (depends on contents)
- **Disk I/O:** Write operations only when backpacks are closed

### Performance Tips

1. **Data folder on SSD:** Place the playerdata folder on an SSD for faster saves
2. **Backup timing:** Schedule backups during low-population hours
3. **Monitor file count:** Thousands of backpack files are fine, but keep organized

### Expected Performance

- **Backpack open/close:** < 1ms per operation
- **Doubler upgrade:** Instant (modifies item metadata only)
- **Plugin startup:** Load time scales with number of backpacks
  - 100 backpacks: ~50ms
  - 1000 backpacks: ~500ms
  - 10000 backpacks: ~5s

## Troubleshooting

### Common Issues

#### Players can't open backpacks
**Check:**
- Is the item actually a backpack? (Should have gold text and enchant glow)
- Are there any conflicting plugins preventing right-click?
- Check console for errors

#### Personal backpack (/bp) not working
**Check:**
- Does the player have the `backpacks.use` permission?
- Is the player typing `/bp` (not `/backpack`)?
- Check console for permission errors

#### Backpack contents not saving
**Check:**
- Do you see "Backpack saved!" message when closing?
- Check file permissions on `plugins/Backpacks/playerdata/` directory
- Look for errors in console during save

#### Doubler not working
**Check:**
- Is the backpack stacked? (Must be quantity 1)
- Is the backpack already 54 slots?
- Are you right-clicking the doubler onto the backpack (not just clicking)?

#### "Invalid backpack-item" warning
**Fix:**
- Check config.yml for typos in material name
- Use valid Bukkit material names (all caps, underscores)
- Valid examples: `BARREL`, `CHEST`, `ENDER_CHEST`, `BUNDLE`

### Getting Help

1. Check console logs for error messages
2. Verify configuration is valid YAML
3. Test with a fresh config.yml (delete and restart)
4. Check for plugin conflicts (disable other plugins temporarily)
5. Contact SupaFloof Games, LLC support

## Maintenance Tasks

### Regular Maintenance

**Weekly:**
- Check playerdata folder size
- Verify backups are working
- Review any console warnings

**Monthly:**
- Clean up orphaned backpack files (from deleted/old accounts if desired)
- Review and optimize backup retention

**After Major Updates:**
- Test backpack functionality
- Verify data integrity
- Check for conflicts with new plugins

### Cleanup Old Backpacks

To remove backpacks from players who haven't logged in for months:

```bash
# Find backpack files older than 90 days
find plugins/Backpacks/playerdata -name "*.yml" -mtime +90

# Delete after verification (USE WITH CAUTION)
find plugins/Backpacks/playerdata -name "*.yml" -mtime +90 -delete
```

**Warning:** Only delete if you're certain players won't return!

## Security Considerations

### Permission Security
- Only give `backpacks.give` to trusted staff
- Regularly audit who has admin permissions
- Monitor usage in logs if needed

### Data Security
- Regular backups prevent data loss
- File permissions should prevent player access to playerdata folder
- Consider encrypting backups if storing sensitive data

### Exploit Prevention
- Keep `allow-nested-backpacks: false` to prevent duplication
- Monitor for unusual file creation patterns
- Update plugin regularly for security patches

## Advanced Configuration

### Custom Give Commands (CommandHelper/Skript)

**CommandHelper:**
```javascript
@givebackpack = >>>
    run('/backpack give backpack ' + player())
<<<
```

**Skript:**
```
command /starterpack:
    trigger:
        execute console command "backpack give backpack %player%"
```

### Personal Backpack Access Control

Control who can access personal backpacks via `/bp`:

**All players:**
```bash
/lp group default permission set backpacks.use true
```

**VIP only:**
```bash
/lp group vip permission set backpacks.use true
```

**Disabled (item backpacks only):**
Don't grant `backpacks.use` to anyone - players can still use item-based backpacks.

## Migration & Updates

### Updating the Plugin

1. Stop the server
2. Replace old `Backpacks.jar` with new version
3. Start the server
4. Run `/backpack reload` if configuration format changed

### Data Compatibility

Backpack data files are compatible across versions. No migration needed.

---

## Quick Reference

### Essential Commands
```bash
/bp                               # Open personal backpack (needs backpacks.use)
/backpack give backpack <player>  # Give backpack item
/backpack give doubler <player>   # Give doubler
/backpack reload                  # Reload config
```

### Essential Permissions
```
backpacks.use    # Personal backpack (/bp)
backpacks.give   # Give items
backpacks.admin  # Admin commands
```

### File Locations
```
plugins/Backpacks/config.yml         # Configuration
plugins/Backpacks/playerdata/*.yml   # Backpack storage
```

### Support
For technical support or feature requests, contact:
**SupaFloof Games, LLC**

---

**Happy administrating!** 🎒⚙️
