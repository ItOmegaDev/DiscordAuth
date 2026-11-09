# DiscordAuth 2.0

**Unified Discord + password authentication plugin for Minecraft servers.**

One jar works on both **Velocity** (proxy) and **Paper/Purpur** (server). Automatically detects the platform.

Made by **Omega** ([@ItOmega](https://t.me/ItOmega))

---

## ✨ Features

- **Discord verification** — players get a code in-game, type `/verify <code>` in Discord
- **Password protection** — BCrypt hashed, verified off the main thread
- **Paper dialog UI** — native 1.21.6+ login dialog (password field, Login/Cancel/Discord buttons)
- **Chat fallback** — older clients (<1.21.6) automatically use chat interface
- **Velocity limbo** — holds unauthenticated players in a frozen limbo world
- **IP rate limiting** — blocks IPs with too many connections (anti-flood)
- **Discord login alerts** — notifies players when someone logs in from a new IP, with a "This wasn't me" button to block
- **IP-bound sessions** — skip password for 20 minutes from the same IP
- **Test mode** — skip all auth for development/testing
- **Bridge mode** — Paper server only syncs Discord identities from proxy (no auth on backend)
- **PlaceholderAPI** — `%discordauth_nick%`, `%discordauth_username%`, `%discordauth_global%`, `%discordauth_id%`, `%discordauth_linked%`
- **TAB integration** — placeholders for TAB plugin
- **Moderation** — `/ban`, `/tempban`, `/unban`, `/kick`, `/banlist` from Discord and in-game
- **Admin commands** — `/discordauth status|unlink|password|unregister|list`
- **YAML config** — easy to edit `config.yml`, auto-migration from JSON

---

## 📦 Installation

### 1. Download
Get the latest JAR from [Releases](https://github.com/ItOmegaDev/DiscordAuth/releases).

### 2. Install on Velocity (proxy)
1. Put `DiscordAuth-2.0.0.jar` in `plugins/`
2. Start the proxy once — `plugins/discord-auth/` is created
3. Edit `plugins/discord-auth/config.yml`:
   ```yaml
   platform: velocity  # or auto
   role: auth
   ```
4. Put your bot token in `plugins/discord-auth/secrets.yml`:
   ```yaml
   discordToken: "YOUR_BOT_TOKEN_HERE"
   ```
   Or set the `DISCORD_TOKEN` environment variable.
5. Restart the proxy

### 3. Install on Paper (server) — Bridge mode
1. Put the same `DiscordAuth-2.0.0.jar` in `plugins/`
2. Create `plugins/DiscordAuth/config.yml`:
   ```yaml
   platform: paper
   role: bridge
   bridge:
     enabled: true
     channel: discordauth:identity
   ```
3. Restart the server
4. No token needed — the proxy handles Discord

### 4. Install on Paper (server) — Auth mode
Same as Velocity but with `role: auth` and a token in `secrets.yml`.

---

## ⚙️ Configuration

### config.yml (main settings)

```yaml
platform: auto          # auto, velocity, or paper
role: auth              # auth (full login) or bridge (identity sync only)
ui: auto                # auto, dialog (Paper 1.21.6+), or chat
debug: false

discord:
  guildId: ""           # Your Discord server ID
  verifiedRoleId: ""     # Role given after verification
  adminRoleId: ""        # Admin role ID
  adminUserIds: []       # Discord user IDs with admin access
  inviteUrl: ""          # Discord invite link
  requireGuildMember: true
  maxAccountsPerDiscord: 2
  maxJavaAccountsPerDiscord: 1
  maxBedrockAccountsPerDiscord: 1
  identityRefreshMinutes: 30

auth:
  mainServer: main      # Velocity main server name
  codeTtlSeconds: 300   # Verification code lifetime
  codeLength: 6
  maxAttemptsPerUser: 5
  attemptCooldownSeconds: 60
  transferOnVerify: true
  transferAddress: ""
  transferDelayMillis: 1200
  transferReconnectTimeoutMillis: 15000
  transferFallbackAddress: "127.0.0.1:25565"
  bedrockBypassVerification: false
  bedrockBypassPassword: false
  testMode: false       # ⚠️ Set to false in production!
  maxConnectionsPerIpPerMinute: 10
  ipBlockDurationSeconds: 300
  verificationBypassNames: []

password:
  enabled: true
  requireWhenOnlineMode: false
  sessionMinutes: 20    # Skip password for 20 min from same IP
  minLength: 8
  maxLength: 64
  maxAttempts: 5
  cooldownSeconds: 60
  ipMaxAttempts: 10
  ipCooldownSeconds: 300
  timeoutSeconds: 60

tab:
  enabled: true
  nameSource: nickname   # nickname, username, or global
  format: "&#39D353%player%\n&8&o%discord%"
  hover: "&8Minecraft&7: &f%player%"
  stripUnsupported: true
  skipWhenTabPluginInstalled: true
  registerTabPluginPlaceholders: true

bridge:
  enabled: true
  channel: discordauth:identity
  placeholderPrefix: discordauth
  unlinkedPlaceholder: ""
  debug: false
```

### secrets.yml

```yaml
discordToken: "YOUR_BOT_TOKEN"
```

---

## 🔐 Security

- Passwords stored as **BCrypt** (cost 12), hashed off the main thread
- Discord token in separate `secrets.yml` (chmod 600) or `DISCORD_TOKEN` env var
- Legacy token in `config.json` is automatically migrated and removed
- Corrupt JSON makes the plugin **fail closed** (disables instead of starting empty)
- Per-IP and per-session failed attempt throttling
- User-controlled Discord names inserted as **literal components** (no color/MiniMessage injection)
- Admin permission re-checked inside Discord password-reset modal
- All inputs validated at boundaries

---

## 🔧 Commands

### In-game (Velocity limbo / Paper)
| Command | Description |
|---|---|
| `/login <password>` | Sign in with existing password |
| `/register <password> <password>` | Create a new password |
| `/discordlink` | Get a code to link your Discord account |
| `/discord` | Get the Discord invite link |

### Admin (requires `discordauth.admin` permission)
| Command | Description |
|---|---|
| `/discordauth status <player>` | Show link status |
| `/discordauth unlink <player>` | Remove Discord link |
| `/discordauth password <player>` | Check if player has password |
| `/discordauth unregister <player>` | Remove player's password |
| `/discordauth list` | List pending sessions |

### Discord (slash commands)
| Command | Description |
|---|---|
| `/verify <code>` | Link Minecraft account |
| `/account` | Show linked accounts |
| `/password` | Change password |
| `/unlink` | Unlink account |
| `/password-reset <player>` | Admin: reset password |
| `/ban`, `/tempban`, `/unban`, `/kick`, `/banlist` | Moderation |

---

## 🔄 Migration from 1.x

Existing `accounts.json`, `links.json`, `bans.json` from 1.x are read as-is — **no conversion needed**.

If `config.json` contains `discord.botToken`, it is automatically:
1. Moved to `secrets.yml` (chmod 600)
2. Removed from `config.json`
3. `config.json` is migrated to `config.yml`

---

## 🛠️ Build

Fully offline (no Maven/Gradle needed):

```bash
./build.sh
# -> target/DiscordAuth-2.0.0.jar
```

Set missing dependencies explicitly:
```bash
PAPER_API=/path/to/purpur-api.jar LIMBO_JAR=/path/to/limboapi.jar ./build.sh
```

---

## 📊 Placeholders

### PlaceholderAPI
| Placeholder | Description |
|---|---|
| `%discordauth_nick%` | Discord nickname |
| `%discordauth_username%` | Discord username |
| `%discordauth_global%` | Discord global name |
| `%discordauth_id%` | Discord ID |
| `%discordauth_linked%` | "yes" or "no" |
| `%discordauth_name%` | Best available name |

### TAB
Same placeholders with `%discordauth_*%` syntax.

---

## 📄 License

This project is provided as-is for the OmegaSMP community.

**Contact:** [@ItOmega](https://t.me/ItOmega)
