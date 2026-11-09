# DiscordAuth 2.0 (unified Velocity + Paper)

One jar, two platforms. The same artifact runs as a **Velocity proxy** plugin and as a
**Paper/Purpur** server plugin; the platform is detected automatically (or forced with
`platform` in `config.json`).

## What it does

- **Discord verification first** on the very first join of a new account (code shown in game,
  typed in Discord with `/verify <code>`), **then the password** (`/register` or `/login`).
- **Paper**: native 1.21.6+ client dialogs (`Login` title, password field, green **Login**,
  red **Cancel**, plus a **Discord** button). Clients older than 1.21.6 fall back to chat.
- **Velocity**: limbo + chat/title UI (the proxy cannot receive dialog input).
- Moderation from Discord and in game: `/ban`, `/tempban`, `/unban`, `/kick`, `/banlist`
  and `/discordauth status|unlink|password|unregister|list`.
- PlaceholderAPI (`%discordauth_nick%`, `%discordauth_username%`, `%discordauth_global%`,
  `%discordauth_id%`, `%discordauth_linked%`, `%discordauth_name%`) and TAB placeholders on Velocity.

## Build

Fully offline (no Maven/Gradle needed):

```bash
./build.sh
# -> target/DiscordAuth-2.0.0.jar
```

Set any missing dependency explicitly, for example:

```bash
PAPER_API=/path/to/purpur-api.jar LIMBO_JAR=/path/to/limboapi.jar ./build.sh
```

## Install

1. Copy `DiscordAuth-2.0.0.jar` into `plugins/`.
2. Start once — `plugins/discord-auth/` is created with `config.json`.
3. Put the bot token in **`plugins/discord-auth/secrets.json`** (mode 600) or in the
   `DISCORD_TOKEN` environment variable. A token found in a 1.x `config.json` is moved
   automatically and the old value is deleted from `config.json`.
4. Restart.

Existing `accounts.json`, `links.json` and `bans.json` from 1.x are read as-is — no conversion.

## Roles

| `role` | Platform | Behaviour |
|---|---|---|
| `auth` | Velocity | full authentication in the limbo, then transfer/release |
| `auth` | Paper | full authentication in a frozen lobby using dialogs |
| `bridge` | Paper | no login here: only Discord identities from the proxy → PlaceholderAPI |

Data files are only touched in `auth` mode, so a `bridge` server never edits the proxy's store.

## Security notes

- Passwords: BCrypt cost 12, hashed off the main thread, never sent through chat on Paper.
- Per-session **and** per-IP failed-attempt throttling; codes are CSPRNG digits with a TTL.
- Corrupt JSON makes the plugin **fail closed** (it disables instead of starting empty).
- The Discord token lives only in `secrets.json` (chmod 600) or `DISCORD_TOKEN`.
- User-controlled Discord names are inserted as literal components (no colour/MiniMessage injection).
- The admin permission is re-checked inside the Discord password-reset modal, not only on the command.
