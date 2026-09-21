# AutoBuilder

A client-side Fabric mod for Minecraft **1.21.1** that builds a schematic for you.

- Drop a `.schem` file (Sponge Schematic v2/v3 — e.g. exported by WorldEdit/Litematica/etc.) into `.minecraft/schematics`
- Press **Right Shift** to open the AutoBuilder menu, aimed at where you want to build
- Hit **Auto Build** — it places blocks one at a time, verifying each one before moving to the next

### Getting blocks: Creative or Shop
AutoBuilder needs blocks in your hotbar before it can place them. It has three modes (Settings → **Mode**):

| Mode | Behavior |
|---|---|
| **AUTO** *(default)* | If you're in **singleplayer and in creative mode**, blocks are pulled for free, just like picking a block. Otherwise it falls back to the shop. |
| **CREATIVE** | Always pulls blocks for free via the creative-inventory action. Works in singleplayer, and also on a multiplayer server if you have op/creative there. |
| **SHOP** | Always runs your configured shop command (e.g. `/shop`) and buys what's missing, page by page, respecting your price limit. |

No typing item names, no manual stocking — it figures out what it needs block-by-block as it builds.

### Other features
- **Auto-picks the schematic** — newest file in the folder by default, remembers your last choice, `<`/`>` to switch, and a refresh button if you drop in a new file mid-session
- **Resume support** — stop and restart; already-correct blocks are skipped
- **Live progress HUD** — a small progress bar, block count, and ETA in the top-left corner while building (the menu itself closes once building starts)
- **Adjustable speed** (1–5) — trade a slower, more careful pace for faster building
- **Strict mode** (default on) — stops immediately on a wrong block, an unplaceable block, or a failed placement so nothing gets built incorrectly. Turn it off to just skip problem blocks and keep going
- **Completion sound** — a chime when the build finishes (toggleable)
- **Shop safety net** — a configurable max price per click so a shop typo can't drain your wallet, plus retry/limit handling if a purchase doesn't register
- **Chat commands** — `/autobuilder start`, `/autobuilder stop`, `/autobuilder status`, for when you don't want to open the menu

### Build
Needs Java 21.

```
Windows:   gradlew.bat build
Mac/Linux: ./gradlew build
```

Jar lands in `build/libs/autobuilder-1.1.0.jar` — drop it in `.minecraft/mods/` along with Fabric API `0.102.0+1.21.1`.

No Java installed? Push this repo to GitHub — the included Actions workflow (`.github/workflows/build.yml`) builds the jar on every push and uploads it as an artifact named `autobuilder-jar` (Actions tab → latest run → Artifacts).

### Notes
- Client-side only — no server-side installation needed, and it won't affect other players
- This automates *your own* actions (placing blocks you already have, or that you're entitled to pull/buy) — using it in ways that break a server's rules is on you; check before running it on someone else's survival server
