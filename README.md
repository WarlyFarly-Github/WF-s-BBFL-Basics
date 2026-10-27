# WF's BBFL Basics ⚽

A Minecraft plugin by **WarlyFarly** providing basic utilities and a football match system for the BBFL Minecraft server.

**Platform:** Paper 26.3  
**Internal name:** `WFs_BBRL_Basics`

## Features

- 📯 **Whistle:** Global announcements with titles and sound effects.
- ⚽ **Football matches:** Create matches, invite players, assign teams, and track scores.
- 🏟️ **Stadiums:** Create stadiums, configure goals, and detect goals automatically.
- 👕 **Team jerseys:** Team-coloured chestplates restored after matches.
- ⏱️ **Match timers:** Two five-minute halves with half-time breaks.
- 🔔 **Freeze countdowns:** Five-second freezes with titles, action bars, and note-block sounds.
- 🖥️ **Interactive GUIs:** Menus for match management, stadium selection, and player invitations.

## Commands

| Command | Description |
|---|---|
| `/whistle` | Obtain the whistle |
| `/match` | Open the match menu |
| `/match create` | Create a match |
| `/match start` | Start a match |
| `/match stadium <name>` | Select a stadium |
| `/match goal` | Manually award a goal |
| `/match end` | End a match |
| `/match leave` | Leave a match |
| `/stadium create <name>` | Create a stadium |
| `/stadium delete <name>` | Delete a stadium |
| `/stadium list` | List stadiums |
| `/stadium info <name>` | View stadium information |

Additional commands support player invitations and stadium goal configuration.

## Permissions

- **Operator:** Access to the whistle and bypass functionality.
- `group.builder`: Stadium management access for the LuckPerms builder group.
- `wfsbbrl.stadium`: Stadium management permission.

## Installation

1. Build the plugin using Gradle.
2. Place the generated JAR in your server's `plugins` folder.
3. Restart your Paper server.
4. Configure stadiums and start playing!

Build with:

```bash
gradlew build
```

## Project Structure

```text
WFsBBRLBasics/
├── src/main/java/dev/whistle/
│   ├── WFsBBRLBasics.java
│   ├── Match.java
│   ├── MatchManager.java
│   ├── Stadium.java
│   └── StadiumManager.java
├── src/main/resources/plugin.yml
├── build.gradle.kts
└── settings.gradle.kts
```

Stadium configurations are stored in `stadiums.yml`.

## Development Status

The initial whistle implementation is complete. The match system, stadiums, GUIs, and newer features still require compilation and testing.

**Developer:** WarlyFarly  
**Server:** BBFL  
**Platform:** Paper 26.3
