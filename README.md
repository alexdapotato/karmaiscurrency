# Karma Is Currency — AI Agent Development Handoff

**Last updated:** 2026-09-25  
**Project:** `karmaiscurrency`  
**Minecraft:** 26.2  
**Fabric Loader:** 0.19.5  
**Fabric API:** 0.161.0+26.2  
**Fabric Loom:** 1.17.21  
**Java:** 25  
**Current development focus:** finish the player-facing UI before spending time on the remaining gameplay backend.

---

## 1. What this document is for

This is the current handoff for another AI coding agent taking over the **Karma Is Currency** project.

The goal is to prevent the next agent from:

- redoing already-working systems,
- resurrecting removed UI architectures,
- trusting stale README/roadmap statements over the current source,
- changing shrine worldgen unnecessarily,
- claiming a build succeeded without a real Java 25/Fabric 26.2 build,
- or implementing backend systems before the UI milestone is complete.

The user currently wants to **finish the UI first**. Once the UI is stable, they intend to run the server and implement/backfill the gameplay systems while the server is operating, updating the mod incrementally.

---

# 2. Source-of-truth hierarchy

When information conflicts, use this order:

1. **The current source code in the latest supplied project ZIP.**
2. **The user's latest explicit instruction in the current conversation.**
3. This handoff document.
4. Current project documentation such as `MASTER_KARMA_EVENT_TABLE.md`, `PATCH_NOTES.md`, and `SOCIAL_CURRENCY_IMPLEMENTATION.md`.
5. Older handoff documents, old ZIPs, historical notes, or remembered architecture.
6. The public GitHub repository is **not** current source and must not be treated as authoritative.

If the source and this document disagree, do not silently pretend they agree. Inspect the code, identify the mismatch, and ask or report before changing behavior.

---

# 3. Hard development rules

These rules are part of the project design, not suggestions.

1. Inspect existing code before editing.
2. Modify the smallest reasonable number of files.
3. Reuse existing services and state instead of creating duplicate systems.
4. Do not silently replace working systems.
5. Do not create a second economy implementation.
6. Do not rewrite stable shrine ring/world generation unless explicitly requested.
7. Keep shrine protection changes separate from worldgen changes.
8. Do not change UI while fixing an unrelated backend problem unless required.
9. Never claim that Gradle/Fabric compilation succeeded unless it was actually run successfully in the correct Java 25 environment.
10. If compilation fails, report the exact error.
11. Preserve working functionality.
12. Prefer direct implementation over repeatedly asking for permission when the intended change is already explicit.
13. For substantial changes, provide a ZIP or complete patch. For tiny one/two-line fixes, exact file/line changes are acceptable.
14. Do not remove `.git` or overwrite the project's Git history when updating a local checkout.
15. Do not infer that an old source archive is newer merely because its filename sounds newer.

---

# 4. Current milestone: UI first

The user does **not** want to pause and implement every backend mechanic right now.

The current milestone is:

```text
Make every player-facing menu visually coherent,
navigable, and functionally clickable,
even when some backend actions are still W.I.P.
```

After that milestone:

```text
Run server
    ↓
implement gameplay systems incrementally
    ↓
restart/update as needed
    ↓
balance
    ↓
multiplayer QA
```

This means a UI action can legitimately display an honest W.I.P. state for a backend feature, but it should not be a broken click, dead slot, accidental duplicate menu, or unfinished navigation path unless that is intentional.

---

# 5. Current project architecture

Important current classes:

```text
KarmaIsCurrency.java
    Mod entrypoint / registration

KarmaService.java
    Karma, Infamy, progression, decay, player state hooks

SocialCurrencyService.java
    Reputation + Influence conversion/redemption layer

SocialTokenService.java
    Physical Influence token creation, tagging, counting and removal

KarmaStatusMenu.java
    Main unified Karma UI
    Tabs: Blessings/Curses, Titles, Social Currency

ShrineMenu.java
    Normal Light/Dark/Neutral shrine root UI

MonumentMenu.java
    Monument/Worldheart root UI

KarmaShopMenu.java
    Shop UI

KarmaShopCatalog.java
    Shop purchase logic

ShrineCompassMenu.java
    Shrine compass target selector

ShrineCompass.java
    Physical compass creation/targeting

ShrineManager.java
    Shrine discovery/persistence/runtime lookup

ShrineSite.java
    Persisted shrine site data + protection bounds codecs

ShrineState.java
    SavedData persistence for shrine sites

ShrineType.java
    Light / Dark / Neutral / Monument

CurseBlessingRegistry.java
    Curse/Blessing definitions

CurseBlessingService.java
    Persistent curse/blessing slots, permissions, timers and state

KarmaEvents.java
    Karma/social event hooks and periodic maintenance

KarmaCommands.java
    /karma admin/debug commands

DarkShrineService.java
    Dark contracts and hostile-kill tracking

LightShrineService.java
    Older/dedicated Light blessing/trial service
```

There are also some older/parallel menu classes in the source tree:

```text
KarmaConversionMenu.java
LightShrineMenu.java
DarkShrineMenu.java
DarkCursesMenu.java
```

These are **not automatically the current architecture**. Do not revive or redirect player navigation to them without a specific reason. The current player-facing Karma UI is centered on `KarmaStatusMenu`.

---

# 6. Economy model

The intended conceptual economy is:

```text
Karma
= alignment / stance / progression

Reputation
= Light social currency

Infamy
= Dark social currency

Influence Tokens
= transferable physical social-currency item
```

Karma is not supposed to be spent in shops or offerings.

Current stance thresholds:

```text
Karma >= +100  → Light
-99..+99       → Neutral
Karma <= -100  → Dark
```

Current Karma cap:

```text
±1000
```

Personal Karma progression thresholds:

```text
100 / 300 / 600 / 1000
```

Starting alignment is selected with:

```text
/karma choose light
/karma choose dark
```

which sets the initial Karma to +100 or -100.

---

# 7. Social currency model

Social gain modifiers are intended to affect social currency, not Karma.

## Alignment modifier

```text
Aligned  → 125%
Neutral  → 100%
Opposed  → 75%
```

## Same-activity diminishing returns

Rolling 30-minute window:

```text
1–5   → 100%
6–10  → 75%
11–15 → 50%
16+   → 35%
```

The current source tracks diminishing-return state in persistent player state.

## Default event values

The current clean-slate configuration uses approximately:

| Event | Karma | Base social |
|---|---:|---:|
| 12 successful villager trades | +1 | +2 Reputation |
| Zombie villager cure | +4 | +5 Reputation |
| Villager kill | -5 | +1 Infamy |
| Ender Dragon | +30 | +80 social base, reduced by nearby-player count |
| Wither | +30 | +80 social base, same general nearby-player treatment |
| Player kill | stance-dependent | configured player-kill social amount + victim scaling |
| Raid Hero | 0 | +25 neutral-origin Influence |
| Mercy Trial | +25 in current table | +5 Reputation |
| Protection Trial | +50 in current table | +10 Reputation |

Raid-start economy rewards and the old Village Chaos economy event are currently removed.

The exact current behavior lives in `ShrineConfig`, `KarmaService`, and `KarmaEvents`; check those before changing numbers.

---

# 8. Influence Tokens

Physical Influence is currently represented by **Echo Shards** with custom item data.

`SocialTokenService` is the authority for physical tokens.

Each token contains:

```text
KarmaSocialToken = true
KarmaSocialTokenValue = 1
KarmaSocialTokenOrigin = LIGHT / NEUTRAL / DARK
```

Physical token origins are preserved when the item is transferred.

The current source no longer uses the old Influence bank. Tokens remain physical inventory items.

Current stack size:

```text
64
```

The usable origin is determined by the player's current stance.

Light and Dark players can use their aligned tokens plus Neutral tokens under the current redemption rules. Neutral players use Neutral-origin tokens.

## World Progression

World Progression uses the server-wide submitted Influence pool. These are the shared stage thresholds:

| Stage | Total Influence required |
|---:|---:|
| 0 | 0 |
| 1 | 1,024 |
| 2 | 3,072 |
| 3 | 7,168 |
| 4 | 13,312 |
| 5 | 22,528 |

Stage 5 is the cap. Further submitted Influence remains in the community pool but does not create another stage. Personal Karma title bands remain separate and use the Karma thresholds above.

---

# 9. IMPORTANT ECONOMY DOCUMENTATION MISMATCH

There is a current code/documentation inconsistency that future agents MUST NOT accidentally amplify.

Some older/current documentation says:

```text
100 social → 85 Influence
```

However, the **current `SocialCurrencyService` implementation** returns:

```java
public static int materializationPercent() {
    return 100;
}
```

and the current config defaults are also:

```text
social.materializationPercent=100
```

Therefore, the current code materializes social currency **1:1**.

Separately, the new Social Currency UI's **dematerialization/offering path** currently applies the intended 15% offering tax:

```text
32 gross minimum
85% net payout
```

Do not silently change either rate while working on UI. This needs a later explicit economy-balancing decision.

---

# 10. Current UI architecture

## Normal shrine root

`ShrineMenu` is the player-facing root for:

```text
Light Shrine
Dark Shrine
Neutral Shrine
```

It uses a vanilla 9×6 chest menu.

Top row is decorative and non-clickable.

Current shrine identity blocks:

```text
Light   → Gold Block
Dark    → Redstone Block
Neutral → Iron Block
```

Current main navigation slots:

```text
slot 22 → Karma
slot 29 → Shrine Compass
slot 31 → Shrine Info (currently intentionally inert)
slot 33 → Shop
slot 40 → Trials / Contracts activity button for Light/Dark
```

Current player-facing normal shrine flow:

```text
Shrine
 ├─ Karma
 ├─ Shrine Compass
 ├─ Shrine Info (inert)
 ├─ Shop
 └─ Trials / Contracts (currently W.I.P.)
```

The old idea of putting separate Blessing/Curse buttons on the root should not be reintroduced unless explicitly requested. The current design puts the alignment feature in the Karma interface.

---

# 11. Monument root UI

`MonumentMenu` is used instead of `ShrineMenu` when the shrine type is Monument.

Current slots:

```text
slot 22 → Karma
slot 29 → Shrine Compass
slot 31 → Shrine Info (inert)
slot 33 → Shop
slot 40 → combined Trials / Contracts W.I.P. button
slot 53 → Close
```

Top row is all Netherite Blocks and is decorative/non-clickable.

There is **no separate Monument Offerings button anymore**.

Offerings were deliberately folded into the Social Currency interface.

---

# 12. KarmaStatusMenu — current tabs

`KarmaStatusMenu` is the unified Karma interface.

Current top-row tab strip is shifted left:

```text
slot 1 → End Crystal → Blessings/Curses
slot 3 → Armor Trim Smithing Template → Titles
slot 5 → Ghast Tear → Social Currency
```

There is **NO Offering/Totem tab anymore**.

The old standalone Offering menu was removed from the player-facing architecture.

Navigation is intended to be:

```text
Shrine
 ↓
Karma
 ↓
Tab
 ↓
Back
 ↓
Shrine
```

The separate `KarmaOfferingMenu` should not be recreated unless the user explicitly asks for it.

---

# 13. Blessings/Curses tab

The first Karma tab is called based on the current shrine/context.

Light:

```text
Blessings
```

Dark:

```text
Curses
```

Monument:

```text
Blessings <-> Curses
```

Monument controls use:

```text
row 2, column 4 → mode indicator
row 2, column 6 → mode toggle
```

Current Monument mode indicators:

```text
Wind Charge  → Blessings / Light
Blaze Powder → Curses / Dark

Gold Ingot → Light
Redstone   → Dark
```

Alignment entries use only:

```text
rows 4–5
columns 2–8
```

That is a 2×7 entry area.

The UI can already render the registry entries, active stacks, slot counts, and tier-like presentation.

### Important current state

Blessings/Curses are **not finished gameplay purchases**.

Current click behavior is mostly informational:

- some Blessings show concrete provisional costs/effects,
- other Blessings say their final cost/effect is still being finalized,
- Curses currently say their purchase cost/effect handling is being finalized.

Do not pretend this is a finished curse system yet.

The future curse architecture is meant to support permanent modifier packages, not just potion effects.

---

# 14. Titles tab

Current Titles tab is a placeholder/preview.

It currently shows:

```text
Current progression stage
Personal Karma thresholds: 100 / 300 / 600 / 1000
Final title names are W.I.P.
```

The actual title system is still missing.

Future UI should eventually support:

```text
title list
requirements
locked/unlocked state
current selected title
selection/confirmation
```

Do not invent final title names or bonuses without the user's direction.

---

# 15. Social Currency tab — CURRENT FINAL UI DIRECTION

This is the most recently redesigned UI and must be treated as the current target.

There are two modes:

```text
Materialize
Dematerialize
```

The old Bolt Armor Trim confirmation item is **removed**.

---

## 15A. Materialize mode

Row 4, columns 2–8:

```text
-16 | -4 | -1 | CENTER | +1 | +4 | +16
```

Current slot numbers:

```text
28 29 30 31 32 33 34
```

The center slot (`31`) is the payout slot.

### Initial state

When entering Materialize mode:

```text
CENTER = empty
```

There is no fake Echo Shard sitting in the middle just to represent a value.

### Batch buttons

```text
+1
+4
+16
```

take the selected social currency from the player's balance and create physical Influence in the center.

The resulting physical payout is **free to take**.

After the player takes the payout, the same selected batch is automatically replenished, as long as enough social currency remains.

Example:

```text
Player has 100 Reputation.

Clicks +16.
→ 16 Influence appears in center.

Player takes the 16.
→ center is refilled with another 16.

Repeat until fewer than 16 remain.
```

The final refill should be limited to the actual remaining balance rather than creating more than the player owns.

### Decrease buttons

The `-16`, `-4`, `-1` buttons adjust the next materialization/replenishment batch.

### Source toggle

The source toggle switches between:

```text
Reputation
Infamy
```

Do not allow a currency switch while a payout is pending.

### Convert All

The Diamond/Redstone control toggles Convert All.

```text
OFF → Diamond
ON  → Redstone
```

When Convert All is active, the center becomes an **enchanted/glinting Ghast Tear**.

Clicking that center item converts the player's entire selected social balance and hands the resulting physical Influence to the player.

After Convert All completes, All mode resets.

---

## 15B. Dematerialize mode

The same row 4 area becomes an item-input tray:

```text
columns 2–8 = 7 input slots
```

Players place accepted offering items directly into these slots.

Accepted items are defined by:

```text
config/karmaiscurrency-offerings.properties
```

Current default values include:

```text
diamond=1
diamond_block=3
netherite_scrap=1
netherite_ingot=4
ancient_debris=2
elytra=20
trident=10
mace=20
heavy_core=15
nether_star=20
beacon=25
conduit=20
heart_of_the_sea=10
shulker_shell=2
echo_shard=2
enchanted_golden_apple=7
dragon_egg=50
dragon_head=20
wither_skeleton_skull=3
player_head=5
```

These values are placeholders/balance values and are configurable.

### Minimum

The total gross offering value must be at least:

```text
32 social value
```

### Tax

Current UI implementation:

```text
Gross value
× 85%
= net Reputation/Infamy
```

Equivalent to a 15% offering tax.

The UI displays both gross and net values.

### Controls

Current dematerialization controls include:

```text
currency source toggle
Sea Lantern → Dematerialize mode
Nether Star → Offer Items / Need More Value
Barrier      → Clear Offerings
```

Closing the menu returns inserted items to the player.

Do not allow item loss during mode switching, tab switching, Back, or menu close.

---

# 16. Important social UI implementation details

`KarmaStatusMenu` currently uses custom `Slot` instances for the Social Currency tray.

When `redeemMode == true`, the row 4 slots are real input slots whose placement is constrained to configured offering items and excludes tagged Influence tokens.

When Materialize is active, the center slot is a free-to-take payout slot.

Because this is a normal chest menu, **click interception matters**. When modifying this UI, carefully preserve the distinction between:

```text
Display-only slot click
Inventory input slot
Free-to-take payout slot
Player inventory slots
```

A previous major source of bugs was treating a menu as a separate sub-menu and forgetting to rebuild all of its click handlers. The current architecture deliberately keeps Social Currency inside `KarmaStatusMenu` so it inherits the same navigation/click model.

Do not introduce a new submenu just to add one feature unless there is a strong reason.

---

# 17. Shop UI

`KarmaShopMenu` uses a 9×6 chest layout.

Current categories are:

```text
COMMON  → Light/Neutral shared shop, Reputation
VILLAIN → Dark shop, Infamy
```

Tabs:

```text
slot 2 → Light/Common
slot 6 → Dark
```

Reward slots:

```text
11, 13, 15, 29, 31, 33
```

Pagination:

```text
47 → previous
51 → next
49 → back
```

Current restrictions prevent a Light/Neutral shrine from switching to Dark and vice versa. Monument is allowed to see both tabs through the current UI architecture.

### Known current Monument shop gap

`ShopCategory` includes Monument support, but `KarmaShopCatalog` currently rejects Monument purchases (`canAfford` returns false / spend throws for Monument).

`KarmaShopMenu` also currently routes a Monument to the Common category.

Therefore the **intended separate Monument Influence-token shop is not fully implemented yet**.

This should be treated as unfinished functionality, not as a reason to rewrite the whole shop architecture.

---

# 18. Shrine Compass

The Compass root button gives the player a physical Shrine Compass, subject to the current cooldown.

Current cooldown:

```text
10 minutes
```

The dedicated compass menu lets the player choose:

```text
Nearest
Light
Dark
Neutral
Monument
```

The actual compass item stores its target and can retarget to generated or predicted shrine positions.

The Compass UI should remain a dedicated selector rather than being mixed into the Karma UI.

---

# 19. Shrine discovery / worldgen

Shrine generation is intentionally considered stable.

Normal shrine generation:

```text
ring distance: 1000 blocks
ring spread: 100 blocks
8 normal candidates / ring
20 rings
160 normal candidates
```

Monument generation has its own ring placement configuration.

Do **not** replace the ring placement implementation with another library or a new worldgen algorithm without explicit approval.

The project previously suffered lagback/performance issues when moving too aggressively toward third-party structure-placement systems.

---

# 20. Monument generation / foundation

The desired Monument presentation is:

```text
reasonably flat terrain preference
Monument about 3 blocks above terrain
intentional stone/stone-brick support pillars
pillars follow Monument footprint
weathered/mossy variants possible
no giant artificial platform
no giant artificial cavity
no generic beard_thin adaptation as the final visual solution
```

This was still a future worldgen/foundation task in the project handoff.

Normal shrine worldgen must not be changed just to solve Monument appearance.

---

# 21. Persistence / protection crash that was fixed

A server crash was traced to `ShrineSite` protection bounds using an artificial:

```text
[-128, 128]
```

Y-range codec.

A Monument can be tall enough to generate a relative Y value around:

```text
155
```

and the old codec rejected it during SavedData serialization.

The current fix uses unrestricted integer codecs for the relevant Y bounds.

Do not reintroduce the `intRange(-128, 128)` restriction for Monument protection Y values.

Do not "fix" this by clamping the value to 128; that would change the protection footprint.

---

# 22. Crafting crash that was fixed

A previous mixin crashed any ordinary crafting-grid/container change because it called:

```java
recipe.value()
```

while `recipe == null`.

The fix was to return when no recipe exists before dereferencing it.

The crash appeared even while moving ordinary logs/items around because crafting recalculation runs during container changes.

The current `CraftingMenuMixin.java` must keep the null guard.

When testing future builds, ordinary crafting should be tested first because this bug used to make almost all inventory interaction appear broken.

The user has confirmed that the crafting fix works in their local server run.

---

# 23. Blessing/Curse backend status

`CurseBlessingRegistry` currently contains registry-style definitions for Blessings and Curses.

`CurseBlessingService` currently provides:

```text
persistent blessing slots
persistent curse slots
stack counts
blessing expiration
blessing cooldowns
Light/Dark shrine permission checks
Devotion / Devil commitment state
```

However, the final Curse system is not implemented.

### Intended future Curse design

Curses should be permanent, powerful modifier packages with meaningful drawbacks.

The concept is a large modifier pool including things such as:

```text
Max Health
Max Absorption
Movement Speed
Attack Damage
Attack Speed
Armor
Armor Toughness
Luck
Jump Strength
Gravity
Fall Damage
Mining Efficiency
Block Interaction Range
Entity Interaction Range
Step Height
Scale
Damage dealt/taken
Projectile damage
Fire/fall/void modifiers
```

plus status effects and custom systems such as:

```text
Reputation gain/loss
Infamy gain/loss
Karma gain/loss
Shrine rewards
Shop discounts
Offering value
PvP/PvE modifiers
Shrine access restrictions
Day/night modifiers
Dimension modifiers
Cannot trade
Cannot sleep
Cannot sprint
etc.
```

**Do not assume all of these can be implemented as a vanilla `AttributeModifier`.**

Separate them into:

```text
Minecraft Attribute
Vanilla Status Effect
Custom event/gameplay mechanic
```

The current Major Arcana concepts are brainstorming, not finalized content.

---

# 24. Blessing backend status

Blessings are intended to be:

```text
temporary
beneficial
no drawback
```

Some provisional concepts already exist:

```text
Watcher
Smite
Devotion
Swiftness
Giants
Fortune
Miner
Deep
Mountain
Night
Smith
Vanguard
Protection
Abundance
```

Only some currently have concrete UI cost/effect text.

Do not turn every placeholder into a fake finished mechanic merely to make the UI look complete. Honest W.I.P. presentation is acceptable until the user locks the backend design.

---

# 25. Trials and Contracts

These are intentionally backburner systems.

The project has backend/service code for:

```text
Mercy Trial
Protection Trial
Hunt Contract
Raid Contract definition
```

but the player-facing root UI currently treats Trials/Contracts as W.I.P. / unavailable.

Do not invent a new selection workflow yet unless the user explicitly asks for it.

---

# 26. Titles / progression

Progression currently uses:

```text
100
300
600
1000
```

and `KarmaService.progressionStage(player)` exposes the current stage.

The actual public title list and title-selection behavior are not finalized.

Treat Titles as a UI milestone first, backend later.

---

# 27. Purification / commitments

The current blessing/curse service contains a permanent commitment concept:

```text
Devotion
The Devil
```

There is also a clear-commitment method intended for a future purification mechanic.

Purification is not finished.

Do not invent a final purification price or behavior without explicit user direction.

---

# 28. Rotation system

Planned later:

```text
Shops
Trials
Blessings
Contracts
Curses
```

rotate every 12 hours.

Target boundaries:

```text
12 AM
12 PM
```

Desired behavior:

```text
deterministic
server-wide
configurable timezone
```

There is no completed universal rotation system yet.

Do not implement it merely to finish the current UI milestone.

---

# 29. Current config files

Primary server config:

```text
config/karmaiscurrency.properties
```

Offering values:

```text
config/karmaiscurrency-offerings.properties
```

Offering file format:

```text
minecraft:item_id=social_value_per_item
```

The runtime offering UI reads this configuration rather than a hardcoded item list.

---

# 30. Current useful commands

Main commands include:

```text
/karma
/karma choose light
/karma choose dark
/karma reputation ...
/karma infamy ...
/karma debug ...
/karma reload
```

There are shrine/debug command paths as well.

Use the actual command registration in `KarmaCommands.java` / `ShrineCommands.java` as the authority if a syntax question arises.

A previous standalone Offering debug GUI path was removed with the old Offering menu architecture. Do not re-add it by default.

---

# 31. Current known unfinished UI/features

This is the practical checklist.

## UI still needing work

### Highest priority

- [ ] Finish the visual/design pass for Blessings entries.
- [ ] Finish the visual/design pass for Curses entries.
- [ ] Make Titles a proper screen rather than a one-item placeholder.
- [ ] Finish UI copy for Social Currency now that the new materialize/dematerialize model is in place.
- [ ] Finish Monument shop UI/selection presentation.
- [ ] Perform a complete click/navigation pass through all screens.

### Root navigation cleanup

- [ ] Decide whether Light/Dark activity buttons should stay visibly W.I.P. or be presented differently.
- [ ] Keep Shrine Info intentionally inert until its actual design exists.
- [ ] Ensure Back always returns exactly one logical level.
- [ ] Verify no old Offerings button remains anywhere.
- [ ] Verify no old Bolt Armor Trim confirmation remains anywhere.

### Social Currency QA

- [ ] Enter Materialize → center must start empty.
- [ ] +1/+4/+16 create the selected amount in the center.
- [ ] Taking the payout replenishes the same batch when funds remain.
- [ ] Partial final batch cannot overdraw the player's social balance.
- [ ] Convert All creates a glinting Ghast Tear center control.
- [ ] Clicking Convert All hands the full physical result to the player.
- [ ] Dematerialize row 4 slots accept only configured offering items.
- [ ] Minimum gross value is 32.
- [ ] 15% tax is visible.
- [ ] Payout is correct after tax.
- [ ] Clear returns all inserted items.
- [ ] Closing the menu returns all pending items/payouts.
- [ ] Currency switching is blocked when there is a pending payout or offering tray content.
- [ ] Tab switching is blocked when doing so would lose active transaction state.
- [ ] Shift-clicking and drag placement behave safely.
- [ ] No container click crashes occur.

---

# 32. Known backend gaps after UI milestone

These should generally be done after the UI is locked:

```text
Curse purchase/effect engine
Blessing final purchase/effect engine
Title persistence/selection
Trials final reward behavior
Contracts final UI/backend
Monument Influence-token shop
Universal 12-hour rotation
Neutral Shrine design/mechanics
Purification mechanic
Wither damage-share verification against live behavior
Economy balancing
Economy simulator
Exploit QA
Multiplayer/persistence QA
```

---

# 33. Important current code gaps/mismatches to keep in mind

## Monument shop

Current source contains Monument shop concepts but `KarmaShopCatalog` still rejects actual Monument purchases.

## Materialization percentage

Current code is hardcoded to 100% despite historical 85% documentation.

## Legacy menus still exist in the tree

Do not assume every menu class is actively used.

## Old documentation may describe an old Offering menu

That menu is no longer the current player-facing architecture.

## Some docs mention an Influence bank

The current source has removed the bank and uses physical inventory tokens.

## Current source contains both newer and older blessing/trial services

Do not create a third parallel system.

---

# 34. UI implementation style

The project uses server-side vanilla chest menus.

Most menu classes follow this pattern:

```java
extends KarmaMenu
```

with:

```java
SimpleContainer display
SimpleMenuProvider
refresh()
clicked()
quickMoveStack()
canDragTo()
```

Display slots are normally intercepted so players cannot simply steal decorative items.

When real input slots are needed, use explicit `Slot` behavior rather than pretending the display item itself is interactive.

This distinction is particularly important in `KarmaStatusMenu` Social Currency mode.

---

# 35. What NOT to do

Do not:

```text
rewrite ShrineManager because a UI button is wrong

replace the ring worldgen

reintroduce the removed KarmaOfferingMenu

reintroduce the Bolt Armor Trim confirmation

create a second SocialCurrencyService

create another token item type

create a second Karma menu for Offerings

turn Karma back into a spendable currency

make curses temporary potion effects just because they are easier

invent final curse costs without the user's approval

claim a build works from static inspection

claim a server test occurred without a real server test
```

---

# 36. Safe workflow for future agents

When asked to make a change:

### Step 1 — Locate the current code

Use the latest supplied ZIP/source, not GitHub.

### Step 2 — Trace the existing flow

For UI bugs, trace:

```text
root button
→ open(...)
→ menu constructor
→ refresh()
→ clicked()
→ handler
→ backend service
```

### Step 3 — Change the smallest surface

Prefer one menu/service over a broad rewrite.

### Step 4 — Check compile-impacting API assumptions

Minecraft/Fabric 26.2 mappings are sensitive to method names and constructor shapes.

### Step 5 — Clean build locally

The user's development machine is Java 25.

Use:

```text
./gradlew clean build
```

or the user's normal IntelliJ Gradle run configuration.

For server testing:

```text
./gradlew clean runServer
```

Do not use `UP-TO-DATE` as proof that a changed file compiled after a source mismatch. Clean builds are especially useful after mixin changes.

### Step 6 — Test the exact user path

Example:

```text
Monument
→ Karma
→ Social Currency
→ +16
→ take payout
→ wait for refill
→ switch Dematerialize
→ insert offering
→ Offer Items
→ receive taxed social currency
```

### Step 7 — Report verification honestly

State exactly:

```text
changed files
verified build or not
verified server path or not
remaining known issues
```

---

# 37. Testing priorities

Every UI change should be tested in this order:

```text
1. Open menu
2. Click every visible button
3. Back navigation
4. Close menu
5. Inventory shift-click/drag behavior
6. Re-open menu
7. Server restart/persistence if state changed
8. Exact intended gameplay path
```

For Social Currency specifically, test both:

```text
Reputation
Infamy
```

and all three materialization batch sizes:

```text
1 / 4 / 16
```

Also test balances smaller than the requested batch.

Example:

```text
7 Reputation + +16
```

must never produce 16 tokens.

---

# 38. Recent critical fixes

## Crafting crash

Fixed a null `RecipeHolder` dereference in the `CraftingMenuMixin` Recovery Compass recipe suppression hook.

## Shrine SavedData crash

Fixed Monument protection Y serialization failing on values such as 155 due to `[-128,128]` codecs.

## Old Offering menu

Removed the standalone player-facing Offering submenu in favor of Social Currency dematerialization.

## Social Currency UI

Current redesign includes:

```text
empty materialization center
+1 / +4 / +16 payout creation
automatic payout refill
Convert All Ghast Tear control
7-slot dematerialization tray
32 gross minimum
15% offering tax
```

---

# 39. Current recommended next work

The user's chosen strategy is explicitly **UI first**.

Therefore the next useful sequence is:

```text
1. Finish Blessings/Curses screen presentation
2. Finish Titles screen presentation
3. Finish Social Currency wording/edge-case interactions
4. Finish Monument Shop presentation
5. Run complete UI navigation QA
6. Remove/retire any visibly duplicated legacy UI
7. Declare UI milestone complete
8. Move to backend gameplay implementation while the server runs
```

Do **not** jump directly into building the entire curse backend unless the user asks for it.

---

# 40. Final handoff summary

The project already has substantial infrastructure:

```text
Karma state
Social currencies
Physical tokens
Shrine discovery
Shrine persistence
Protection
Shrine compass
Shops
Basic Blessing/Curse framework
Trials/Contracts framework
Server events
Config system
Debug commands
```

The current bottleneck is not basic infrastructure. It is turning the player-facing UI into a clean, consistent, complete interface before expanding gameplay content.

The most important recent design decision is:

> **Offerings are no longer a separate menu. They are the Social Currency Dematerialize mode.**

The most important current engineering rule is:

> **Use the latest source as truth, modify minimally, and never claim a build succeeded unless it actually did in the correct Java 25 environment.**

The most important immediate development goal is:

> **Finish the UI milestone first. Backend completion comes afterward.**
