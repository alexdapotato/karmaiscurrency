# Shrine Activation / Repair / Protection Update

## Implemented

### Proximity and ring activation
- Uses the existing deterministic ring prediction system as a **local trigger only**.
- When a player changes chunks, the mod checks predicted shrine candidates within **2 chunks**.
- A candidate is inspected only if its chunk is already loaded via `getChunkNow`; the mod does **not** force-load destination chunks.
- Loaded ring candidates continue through the existing `ShrineState` discovery path.
- Shrine interaction still works through the existing right-click path.
- A player touching the hidden vanilla `minecraft:structure_void` shrine core opens the same shrine root UI.

### Repair
- The physical core marker is restored when a shrine enters a player's 2-chunk proximity.
- The lodestone is restored as well.
- Generated ring/Monument shrines are repaired from their existing `StructureStart`/pieces, using only chunks that are already loaded.
- Manual/operator shrines persist their exact structure template ID so their original template can be reused for repair.
- Repair is throttled to once per shrine per 60 seconds to avoid repeated large block writes.
- Existing saved shrines without a structure ID remain compatible; their physical core/lodestone can still be restored, and generated structures can still be located from loaded structure starts.

### Explosion protection
- A Mixin filters protected shrine positions out of the server explosion block-destruction list.
- Protected shrine blocks therefore cannot be destroyed or ignited by explosion block interaction.

### Piston protection
- A Mixin rejects piston movement when a protected shrine block is present in the piston push/destroy list.
- This prevents both pushing and pulling protected shrine blocks.

### Ring discovery API cleanup
- The mod now uses `ServerChunkEvents.CHUNK_LOAD` as the shrine discovery lifecycle trigger.
- The old `CHUNK_GENERATE` registration is removed.
- Existing bounded retry discovery remains in place so a structure start that is not yet attached on the first load pass can still be discovered without force-loading.

## Vanilla-client compatibility

The shrine core remains `minecraft:structure_void`; no new block registry entry is added.

The existing custom shrine ring placement types remain unchanged in this update.

## Files changed

- `src/main/java/akisei/karmacurrency/ShrineManager.java`
- `src/main/java/akisei/karmacurrency/ShrineInteractions.java`
- `src/main/java/akisei/karmacurrency/ShrinePredictionService.java`
- `src/main/java/akisei/karmacurrency/ShrineSite.java`
- `src/main/java/akisei/karmacurrency/ShrineState.java`
- `src/main/java/akisei/karmacurrency/mixin/ServerExplosionMixin.java`
- `src/main/java/akisei/karmacurrency/mixin/PistonStructureResolverMixin.java`
- `src/main/resources/karmaiscurrency.mixins.json`

No shrine NBT files were intentionally modified.

## Build verification

The full Java 25/Fabric build was **not verified in this environment**. The available runtime is Java 21, while the project targets Java 25. The Gradle wrapper also attempted to download Gradle 9.5.1 and network access was unavailable.
