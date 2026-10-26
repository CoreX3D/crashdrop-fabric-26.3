# Release 0.1.0 validation

The release artifact was built for Minecraft 26.3 with JDK 25, Fabric Loader 0.19.5 and Fabric API 0.164.0+26.3. Compatibility was also exercised with Fabric API 0.165.0+26.3.

## Automated checks

- **20 JUnit tests:** multiplier distribution and bounds, payout arithmetic, fractional accounting, configuration normalization, and limits. No failures or errors.
- **15 server GameTests:** 14 CrashDrop scenarios and the built-in Fabric test. The scenarios cover a shared round, crash-boundary cash-out, duplicate/stale requests, escrow, persistent data, full inventories, component preservation, disconnects, shutdown refunds, and fractional remainders. All passed.
- **Native Minecraft client:** actual terminal opening, item movement, bet and cash-out buttons, repeated requests, smooth display interpolation, successful payout, loss, saved fractions, bounded history, and compact layout. All recorded checks passed.

On **2026-10-26**, the complete native client checks were rerun **without IC2, Upgrader, or the compatibility addon**. Opening the real terminal, moving items, placing and cashing out bets, payouts, crash handling, fractions, history, and compact layout all passed. The same run also passed all 20 JUnit and 15 server GameTests.

An earlier native compatibility run included IC2 alpha.13, Upgrader with compatibility addon 0.1.7, and other profile mods; it also passed. These runs establish both standalone operation and coexistence. The production dependency metadata and code audit agree: CrashDrop has no IC2 or Upgrader dependency, imports, reflective calls, or bundled JARs. Its two IC2 blacklist IDs are ordinary optional item identifiers.

## Artifact audit

`crashdrop-fabric-26.3-0.1.0.jar`

- Size: **63,280 bytes**.
- SHA-256: `bef29f8c5d6e8f702df4f870de2b6571b3577071121ecf2dcb69289d419741b5`.
- All 24 production classes matched the compiled output.
- No test classes, nested JARs, duplicate ZIP entries, or malformed JSON resources.
- English and Russian translations both contain 30 keys, with no missing referenced keys.

## Screenshots

The four PNGs in `screenshots/` show the rising multiplier, successful cash-out, accumulated fractional remainder, and crash. They are unedited 1280×720 captures from the actual Minecraft 26.3 client, GUI scale 2, with the production screen and normal multiplier growth. The scenes were arranged in a disposable test world; item input, bets, payouts, and crashes were handled by the game.
