# Karma as Currency (KIC)

![Karma as Currency icon](src/main/resources/assets/karmaiscurrency/icon.png)

**Karma as Currency** (**KIC**, mod ID `karmaiscurrency`) is a server-oriented Fabric mod for Minecraft that builds a persistent alignment and social economy around player actions. Players develop toward Light or Dark, earn alignment-linked currencies, interact with generated shrines, contribute to shared World Progression, shop from rotating stock, and pursue Trials, Contracts, and Hunt participation.

> **Development status:** This is an active development project. The source-update bundle includes Java source, resources, design notes, and focused verification scripts; it is not a compiled release. A full Fabric/Gradle build and in-game testing are still required before deploying a build to a live server.

## Contents

- [Highlights](#highlights)
- [Core systems](#core-systems)
- [Quest roster](#quest-roster-in-this-source-snapshot)
- [Commands](#commands)
- [Gamerules](#gamerules)
- [Configuration and saved data](#configuration-and-saved-data)
- [Requirements and compatibility](#requirements-and-compatibility)
- [Build and verification](#build-and-verification)
- [Project documentation](#project-documentation)
- [Known limitations](#known-limitations)

## Highlights

- Persistent Karma, alignment state, reward preference, social balances, and progression.
- Light, Dark, and Neutral playstyles with alignment-specific shrine access and reward paths.
- Generated shrines and monuments with interaction menus, discovery/repair behavior, and shrine compass support.
- Rotating Light, Dark, and Neutral shop stock with configurable item costs, categories, stage requirements, and purchase limits.
- Offerings that value item bundles and pay social currency according to the current economy rules.
- Shared World Progression powered by submitted physical Influence.
- Blessings, Curses, permanent commitments, effect stacking, and shared Sigil capacity.
- Trials, Contracts, special Hunt participation, objective tracking, configurable rewards, and cooldown/eligibility checks.
- Java inventory-menu interfaces and a Bedrock-oriented UI path for supported Geyser/Floodgate server setups.
- Admin/debug commands and source-level verification scripts for economy, quest, shop, progression, and Sigil logic.

## Core systems

### Karma and alignment

Karma is the persistent measure used by KIC's alignment and progression systems. Player actions, quests, combat, shrines, and other registered events can change Karma. Alignment affects which activities and services are available and which social-currency rewards are used. The implementation also maintains alignment-related state separately from a player's original choice so established alignment can be handled consistently around the neutral boundary.

KIC uses several related values:

- **Karma** — alignment and personal progression value.
- **Reputation** — the primary social currency associated with Light-side activities.
- **Infamy** — the primary social currency associated with Dark-side activities.
- **Influence** — a physical contribution resource used for shared World Progression. Influence items are distinct from ordinary items that may share their vanilla item type.

Neutral players can have a saved Light/Dark reward preference for applicable events, but that preference does not grant access to a shrine or alignment-locked activity that their actual stance cannot use.

### Shrines and monuments

KIC includes generated shrine/monument structures and the services attached to them. Depending on shrine type and player eligibility, menus provide access to Karma/status information, shop stock, offerings, Sigils, World Progression, quests, and related services. Shrine discovery and saved locations are managed by the mod.

Shrine-specific protection, access, compass, and interaction behavior can be controlled with gamerules. See [Gamerules](#gamerules).

### Shops and offerings

Shop rotations use the shared KIC cycle (normally 12 hours) and select from Light, Dark, and Neutral catalogues. Stock entries can have categories, progression-stage requirements, prices, and per-cycle limits. Prices and stock are data/config driven where supported by the shop configuration.

Offerings consume eligible items after validation. The current source uses a **32 gross-value minimum** for ordinary offerings and pays **95% of gross value**, rounded down at settlement. Certain quest submissions have their own reward rules and confirmation flow and do not necessarily use the ordinary-offering minimum.

### World Progression

Players submit physical Influence through the World Progression UI. Submissions are confirmed before items are consumed, and the server checks the player's inventory and the shared pool again at commit time. If the shared pool changes while a confirmation is open, the selection is rejected and the player is asked to review it again.

The current source snapshot defines five World Progression thresholds in `CommunityProgressionService.java`:

`512 → 1,536 → 3,072 → 6,144 → 10,240 Influence`

World Progression affects stage-based systems such as shop pricing, shrine costs, objective tiers, Karma limits, and shared Sigil-stack capacity. **These numbers describe this source snapshot**; check the service before publishing balance documentation or carrying design changes into a release.

### Sigils: Blessings, Curses, and commitments

Sigils offer temporary or persistent character effects. Blessings and Curses have separate type-slot limits, while shared stack capacity is affected by World Progression and permanent commitments. Effects may require a current rotation, compatible alignment/shrine access, free capacity, and materials.

Devotion is the Light commitment and The Devil is the Dark commitment. They restrict access to the opposing shrine path and affect Sigil capacity. The Java/Bedrock menus expose Sigil details, costs, stack counts, effect information, and reasons an activation is blocked.

A Sigil can be present in stored loadout state but inactive when it exceeds current capacity or fails an eligibility requirement. The status/debug interface is intended to make those reasons visible.

### Quests

KIC supports normal quest activity with an active-quest slot, objective selection, eligibility checks, reward calculation, progress/status displays, and per-objective completion budgets. Submission-based quests preview the transaction and require confirmation before eligible items are consumed.

Hunt participation has its own lifecycle and does not use the normal quest slot. Hunt includes Hunted/Hunter roles, participation eligibility and cooldowns, a compass feature, grace periods, and configurable limits on concurrent Hunts.

## Quest roster in this source snapshot

The source uses the internal name `survivor` for **Endurance** for saved-state compatibility.

| Quest | Pool / role | Core objective |
|---|---|---|
| Trial of Abundance | Light | Submit a selected crop, resource, fish, or wood bundle. |
| Trial of Mercy | Light | Cure a Zombie Villager within the trial window. |
| Trial of Courage | Light | Complete a tracked exploration expedition and return alive to a shrine. |
| Trial of Offering | Alternates between Light and Dark by cycle | Submit the selected eligible bundle. |
| Endurance | Light | Earn movement-supported online time in a 12-hour window, with capped eligible-hostile-kill rewards and an optional Untouchable bonus. |
| Pilgrimage | Light | Visit three distinct functional shrines and return to the first. |
| Challenger | Either eligible alignment pool | Complete a selected number of distinct Trial Spawners as a registered participant. |
| Blood Price | Dark | Make valid hostile kills during a 30-minute timer. |
| The Executioner | Dark | Kill a selected target. |
| The Collector | Dark | Submit a selected themed item bundle. |
| The Warden | Dark | Kill a Warden. |
| The Hunt | Separate participation system | Participate as the Hunted or join as an eligible Hunter. |
| Duel | Unavailable in this source update | The ID/state remains reserved, but player-facing commands and menus do not offer it. |

The normal rotating pools display a limited selection rather than guaranteeing that every eligible quest appears each cycle. Neutral players cannot start Trials or Contracts. See `QUESTS_POOLS_AND_REWARDS.md` and `QUEST_REVAMP.md` for objective pools, rewards, budgets, edge cases, and migration notes.

## Commands

Commands and options are subject to gamerules, alignment, permissions, and the current quest state.

### Player commands

| Command | Purpose |
|---|---|
| `/karma` | Show Karma/alignment status. |
| `/karma choose light` | Choose Light as your starting alignment; this is one-time and only available before you have Karma. |
| `/karma choose dark` | Choose Dark as your starting alignment; this is one-time and only available before you have Karma. |
| `/karma quests` | Open the Quest menu. |
| `/quest` | Show active quest, objective, progress, and status information. |
| `/quest submit` | Preview an active submission quest. |
| `/quest submit confirm` | Confirm the previewed submission while the confirmation is still valid. |
| `/karma quests hunt status` | Show Hunt status. |
| `/karma quests hunt accept` | Accept the Hunted role when eligible. |
| `/karma quests hunt hunter join [player]` | Join an available Hunt as Hunter, optionally naming the Hunted player. |
| `/karma quests hunt hunter leave` | Leave Hunter participation. |
| `/karma quests hunt compass` | Request a Hunt Compass when eligible. |

The graphical menu is the normal route for shop purchases, offerings, Sigils, World Progression, and most quest interactions.

### Operator / maintainer commands

The following use the Game Master command permission or other server-side eligibility checks:

| Command | Purpose |
|---|---|
| `/karma reload` | Reload supported KIC configuration/catalogue data. |
| `/karma debug check <player>` | Inspect balances, alignment/preference, commitment, Sigils, and inactive reasons. |
| `/karma debug quest inspect <player>` | Inspect active objective, progress, reward, and modifier. |
| `/karma debug quest limits <player>` | Inspect objective completion budgets and Endurance restart timing. |
| `/karma debug reward preview <player>` | Preview a reward without paying it. |
| `/karma debug rotation` | Inspect the current quest rotation and Offering side. |

Additional debug commands exist in source; use the in-game command tree as the authoritative syntax for the build installed on the server.

## Gamerules

KIC registers these namespaced gamerules. Boolean defaults are `true` except `duels_enabled`, which defaults to `false`; `max_ongoing_hunts` defaults to `2` and permits values from 0 to 16.

| Gamerule | Default | Controls |
|---|---:|---|
| `karmaiscurrency:karma_menu_enabled` | `true` | Karma/status menu. |
| `karmaiscurrency:titles_enabled` | `true` | Titles feature. |
| `karmaiscurrency:shop_enabled` | `true` | Shops. |
| `karmaiscurrency:social_currency_enabled` | `true` | Social-currency mechanics and rewards. |
| `karmaiscurrency:sigils_enabled` | `true` | Sigil system. |
| `karmaiscurrency:shrine_compass_enabled` | `true` | Shrine Compass. |
| `karmaiscurrency:offerings_enabled` | `true` | Ordinary offerings. |
| `karmaiscurrency:shrines_enabled` | `true` | Shrine services. |
| `karmaiscurrency:shrine_protection_enabled` | `true` | Shrine protection behavior. |
| `karmaiscurrency:blessings_enabled` | `true` | Blessings. |
| `karmaiscurrency:curses_enabled` | `true` | Curses. |
| `karmaiscurrency:compass_cooldown_enabled` | `true` | Compass cooldowns. |
| `karmaiscurrency:quests_enabled` | `true` | Quests and Hunt availability. |
| `karmaiscurrency:contracts_enabled` | `true` | Contract availability. |
| `karmaiscurrency:duels_enabled` | `false` | Duel feature switch; Duel is not currently exposed through player commands/menus in this source update. |
| `karmaiscurrency:bonus_objectives_enabled` | `true` | Quest bonus-objective selection, restrictions, and rewards. |
| `karmaiscurrency:trials_enabled` | `true` | Trial availability. |
| `karmaiscurrency:max_ongoing_hunts` | `2` | Maximum simultaneous ongoing Hunts (0–16). |

Example:

```mcfunction
/gamerule karmaiscurrency:quests_enabled false
/gamerule karmaiscurrency:max_ongoing_hunts 1
```

## Configuration and saved data

The mod migrates its six properties files to `config/kic/`:

- `karmaiscurrency.properties`
- `karmaiscurrency-shop.properties`
- `karmaiscurrency-offerings.properties`
- `karmaiscurrency_quests.properties`
- `karmaiscurrency_hunt.properties`
- `karmaiscurrency-statue-names.properties`

If a legacy file exists at the old `config/` root and the destination does not, KIC moves it into `config/kic/`. If both copies exist, KIC uses the file under `config/kic/`, preserves the old copy, and logs a warning. Back up configuration and world data before installing source updates.

World/player state is persisted by the mod using the existing Minecraft save-data/player-state mechanisms. Configuration migration does not intentionally move world/player save data.

## Requirements and compatibility

From `src/main/resources/fabric.mod.json` in this snapshot:

- **Minecraft:** 26.2 (`~26.2`)
- **Java:** 25 or newer
- **Fabric Loader:** 0.19.5 or newer
- **Fabric API:** required
- **Geyser / Floodgate:** optional/suggested for Bedrock connectivity; Bedrock menu behavior depends on the server's Geyser/Floodgate setup.

Use dependencies appropriate for the exact Minecraft/Fabric versions above. Do not assume a JAR built for another Minecraft version will work.

## Build and verification

### Build the full project

The source snapshot supplied for this update does **not** include `build.gradle`, `settings.gradle`, `gradle.properties`, the Gradle wrapper, or a compiled JAR. This source-only snapshot cannot be built by itself. Apply its `src/` to the full KIC Gradle project (after making a backup), then run from that full project root:

**Windows**

```bat
gradlew.bat clean build
```

**Linux/macOS**

```bash
./gradlew clean build
```

If the build succeeds, the JAR should be in `build/libs/`. Install the resulting JAR in the server's `mods/` directory, along with required dependencies, and restart the server. Do not replace a production server's mod or world without backups.

### Source-level verification

The update bundle includes Python scripts under `verification/`, including:

- `check_quest_revamp.py`
- `check_sigil_revamp.py`
- `check_shop_v5.py`
- `check_economy_update.py`
- `check_world_contributions.py`
- `check_social_confirmation.py`

These are focused source-logic checks and regression fixtures. They are useful during development but do not substitute for a full Fabric/Gradle compile, mixin application, server startup, or Java/Bedrock playtesting. The bundled `check_regressions.py` contains historical expectations that predate the latest quest/Sigil revamp; see `UPDATE_NOTES.md` before treating it as the definitive result for the current source.

## Project documentation

- `UPDATE_NOTES.md` — chronological implementation notes, known issues, and verification status.
- `QUEST_REVAMP.md` — quest submission rules, Endurance/Pilgrimage/Challenger mechanics, Sigil slot behavior, debug commands, and migration notes.
- `QUESTS_POOLS_AND_REWARDS.md` — quest pool, objectives, difficulty tiers, rewards, and completion limits.
- `ECONOMY_UPDATE.md` — current economy rules, offering values, shop defaults, and balance notes.
- `SHOP_CURRENT.md` — current shop balance reference.
- `SHOP_BALANCE_V5.md` — shop balance update notes.
- `WORLD_CONTRIBUTION_UPDATE.md` — World Progression contribution UI and transaction behavior.
- `UI_BALANCE_UPDATE.md` — UI-related balance and display changes.
- `MAINTAINER_ONLY.md` — maintainer-only implementation detail; do not publish as a player guide.

## Known limitations and maintainer notes

- The source-update bundle does not contain the complete Gradle project, so the complete Fabric compilation has not been verified from this archive alone.
- Source-level test scripts use focused checks/stubs; a live Minecraft/Fabric environment is still required to verify mixins, actual damage calculations, persistence under restart, and Java/Bedrock UI behavior.
- Balance is under active development. Quest rewards, costs, rotation rules, Sigil effects, and World Progression values should be checked against source/config before publishing a player-facing guide.
- The `fabric.mod.json` contact URLs still point to the Fabric example project. Replace them with the actual project homepage/source repository before a public release.
- The metadata declares `CC0-1.0`; confirm that this is the intended license and include a root `LICENSE` file in the full repository before distribution.

## Contributing

When changing a feature, update the matching source, public-facing descriptions, configuration defaults/migrations, and focused verification checks together. Record balance decisions and any unverified behavior in `UPDATE_NOTES.md`. Avoid describing a proposed or statically checked feature as release-ready until it has passed the full build and an in-game test.
