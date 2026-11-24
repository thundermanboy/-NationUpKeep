# Changelog

## 0.6.0
- Added `/nation members` to show member roles and online status.
- Added `/nation kick <online-member>` with role hierarchy checks.
- Kicking a member restores their saved GriefPrevention claim permissions before removing them from the nation.
- Added tab completion for online nation members in kick, promote, and transfer commands.

## 0.5.0
- Added automatic settlement of overdue upkeep when a nation bank receives enough funds.
- Added shared upkeep calculation for scheduled charges and overdue settlement.
- Improved invalid upkeep-rate handling.

## 0.4.0
- Added GriefPrevention permission snapshot, suspension, and restoration handling.
- Added admin recovery and status commands.
- Added safer claim-scan failure behavior.

## 0.6.1 - Java 25 build preparation
- Enforced Java 25 and Maven 3.9+ at build time so an accidental Java 21 build fails clearly.
- Added explicit JAR manifest build metadata and deterministic archive timestamp.
- Corrected the startup log version to 0.6.1.
- Added a GitHub Actions workflow for repeatable Java 25 packaging and downloadable build artifacts.

## 0.6.2
- Added floating nation-name labels above online nation members; rank is not displayed.
- Added automatic refresh on join, respawn, world change, membership changes, and periodic passenger recovery.
- Added configurable nameplate enable/format settings and cleanup of plugin-owned armor stands.
- Updated Maven and GitHub Actions artifact version to 0.6.2.
