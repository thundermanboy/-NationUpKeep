# NationUpkeep 0.6.1 — Java 25 build preparation

**This is an uncompiled development source build. Do not install it on a live server yet.** It has not been built or tested against your exact Apex/Spigot 26.2 + Java 25 environment.

## Current design
- Spigot 26.2 target, Java 25.
- GriefPrevention remains responsible for golden-shovel claim creation and claim storage.
- Vault + a Vault-compatible economy provider (for example EssentialsX Economy) supplies player balances.
- NationUpkeep owns nation membership/roles, alliances/enemies, the shared nation bank, and recurring upkeep.

## New in 0.4.0: claim suspension and recovery groundwork
- When a nation cannot pay upkeep, the plugin snapshots the claim's existing trust permissions to `suspended-claims.yml` BEFORE changing GriefPrevention.
- It then makes the claim publicly accessible for building, containers, and access, so it is temporarily unprotected. Claims are not deleted and ownership is not changed.
- Claim permission-management checks are locked while a claim is suspended, to reduce the risk of someone overwriting the saved trust list.
- When the nation pays on a later upkeep run, the plugin restores the saved permission list and saves the claim through GriefPrevention's datastore.
- **New in 0.5.0:** if a nation is already unpaid, depositing enough money to cover its current upkeep bill immediately settles that bill and triggers claim-permission restoration; it does not wait for the next monthly cycle.
- **New in 0.6.0:** `/nation members` lists members, their roles, and online status. `/nation kick <online-member>` removes a lower-ranked online member and attempts to restore that member’s saved claim permissions first.
- On startup, saved snapshots are reconciled: unpaid nations remain suspended; paid or deleted nations' claims are restored/cleaned up.
- `/nationadmin restoreclaims` attempts recovery from all saved snapshots. `/nationadmin status` reports integration status.
- Disbanding a nation and a member leaving restore affected claim permissions before the nation membership is changed.

### Important limitations
This is a first implementation and is **not production-tested**. GriefPrevention's API differs between its legacy v16 production line and its newer major-version development line. This version uses reflection to reduce hard linkage, but event registration and permission persistence still need runtime verification against the exact GriefPrevention build installed on the server. Server operators should back up the world and the GriefPrevention data folder before testing.

The claim suspension behavior is intentionally public access (not a denial of access): other players can build and use containers in an unpaid nation's claims. This matches the requested "unprotected" behavior but can expose builds and stored items. Test on a disposable copy first.

## Dependencies
- Spigot 26.2 server with Java 25 runtime
- GriefPrevention installed
- Vault and a Vault-compatible economy plugin for deposits/withdrawals

## Build
Use a Java 25 JDK and Maven 3.9+ on a build computer. The POM now refuses to build under Java 21 or 24, and creates `target/NationUpkeep-0.6.1.jar` when packaging succeeds. Run `mvn -B clean verify` from this folder. The Java runtime installed on Apex does not automatically provide a Java 25 compiler on a separate build computer.

A GitHub Actions workflow is included at `.github/workflows/build.yml`. After the source folder is pushed into a GitHub repository, the workflow builds using Temurin Java 25 and uploads the JAR as a workflow artifact. This workflow is a reproducible build route, not proof that this source has already compiled or passed runtime testing.

## Commands
- `/nation create <name>`
- `/nation invite <online-player>` / `/nation accept <name>`
- `/nation leave` / `/nation info [name]` / `/nation members` / `/nation kick <online-member>`
- `/nation promote <online-player> <CO_OWNER|ADMIN|OFFICER|MEMBER>`
- `/nation transfer <online-member>` / `/nation disband`
- `/nation ally <nation>` / `/nation enemy <nation>` / `/nation neutral <nation>`
- `/nation bank` / `/nation deposit <amount>` / `/nation withdraw <amount>`
- `/nationadmin scanclaims` / `/nationadmin blocks <nation> <number>` / `/nationadmin charge`
- `/nationadmin restoreclaims` / `/nationadmin status` / `/nationadmin reload`

## Upkeep formula
`ceil(claimedBlocks / 100 * cost-per-100-blocks)` rounded to cents. Default: `$1` per 100 claimed X/Z blocks per 30-day period.

If automatic claim scanning is enabled (default), upkeep aborts without charging any nation when GriefPrevention is missing or the scan fails. This prevents stale claim totals from causing incorrect charges.


## Floating nation nameplates (0.6.2)

Online nation members receive a small floating nation label above their player username. The label is implemented as an invisible marker armor-stand passenger and is refreshed periodically and after join, respawn, and world changes. It displays the nation name only (no rank). Configure `nameplates.enabled` and `nameplates.format` in `config.yml`; `{name}` is replaced with the nation name. The default format is gold and bold.

Note: client/server behavior for passenger-based labels should be verified on the exact Spigot 26.2 build before production use. Some server plugins that manipulate player passengers or entity visibility may conflict.
