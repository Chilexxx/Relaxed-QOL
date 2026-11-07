# Relaxed QOL

A quality-of-life client for **Minecraft 26.2** (Fabric): handy HUD, chat, inventory and visual tweaks.

Mod id and config folder: `relaxed-qol` (`.minecraft/config/relaxed-qol`).

## Requirements

- Minecraft 26.2 with Fabric Loader 0.19.5 or newer
- Fabric API 0.161.0+26.2 (put it in your `mods` folder too)
- Java 25

## Building

Run `build.bat`, or `powershell -ExecutionPolicy Bypass -File .\build.ps1` (also copies the jar into
`.minecraft\mods` and removes an older copy there). The jar is `build\libs\relaxed-qol-1.0.0.jar`.

## What is in it

Open the menu with **Right Shift** (or the gear on the title screen).

- **Render** - HUD-style visuals: Chat (longer history, timestamps, stacked repeats), Armor Status, Keystrokes, Boss
  Bar, Scoreboard, Block Outline, Custom Sky, Locator Bar, Scrollable Tooltips, Chunk Borders, Clear View, Crosshair,
  Fullbright, Freelook, Zoom, Waypoints.
- **Player** - Inventory Tweaks (shulker preview), Sprint, Durability Alert.
- **Misc** - HUD (watermark, coordinates, FPS, ping, module list), Quick Panel (4 command buttons on every chest /
  inventory), Notes, Coords Copy. Screenshots get Copy / Delete buttons in chat.
- **LoverCraft** - Radar, Player Alert, Trade Shot, Action Bar, LoverFella Mode, Live Map.
- **Settings** (gear) - menu look, *Edit HUD & Theme* (move and style every HUD element), *Accounts*.

## Accounts (switch account in the game, session renews by itself)

- **Switch Minecraft accounts without restarting.** On the title screen / server list there is an **Account: Name**
  button next to the gear (also Settings > *Accounts*, or `.account`). Click a saved account to switch - only outside
  a world.
- **+ Add account** opens a separate sign-in window with its own empty profile (your own browser is not touched), so
  you can sign in with any Microsoft account. The code is already on your clipboard: paste it, sign in, done.
- **The session renews by itself** about an hour before it runs out (~24 h), also while you play. Got "Invalid
  session" anyway? It renews on the disconnect screen - just join again. `.account refresh` does it right now.
- Saved in `.minecraft/config/relaxed-qol/accounts.json` - **that file lets anyone play as you; never share it or
  your config folder.**
- The account code only talks to login.live.com, Xbox Live and api.minecraftservices.com.

## Licence

[PolyForm Strict License 1.0.0](https://polyformproject.org/licenses/strict/1.0.0) (see `LICENSE`). You may download,
build and use it for yourself, for any non-commercial purpose. You may **not** change it, share copies of it or of
changed versions, or sell it. The software is provided **as is**, without warranty, and you use it at your own risk.
Read `DISCLAIMER.md` for the plain-language version. The game also shows this once, the first time you are in a world.
