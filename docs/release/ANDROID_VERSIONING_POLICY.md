# Android release versioning policy

Status: **P1 release contract**

## Current physically accepted baseline

Application source:

`e542be403a0e8decffccdab5269acf1a24ff6145`

Installed signed APK SHA-256:

`ffb61ad553b73d201ba89eb482f6e3a3e07daefb019d4cf3c5491ec72ab2a5fc`

Accepted package version:

- versionCode: `1`
- versionName: `0.1`

This historical accepted identity must not be rewritten.

## Candidate version

The first monotonic successor candidate follows the canonical date/sequence policy:

- versionCode: `26100601`
- versionName: `0.2.0`

`versionCode=YYMMDDRR`, therefore `26100601` is the first release sequence for 2026-10-06.

Canonical source of build version:

`android-app/version.properties`

## Rules

1. `versionCode` is a positive integer using `YYMMDDRR` and must increase strictly over the last physically accepted release.
2. `versionName` uses numeric `MAJOR.MINOR.PATCH`.
3. `previousAcceptedVersionCode` and `previousAcceptedVersionName` identify the last physically accepted baseline, not merely the previous Git commit.
4. Do not advance the previous-accepted fields until the new signed APK has passed update-in-place and physical acceptance.
5. Every accepted release evidence must bind:
   - versionCode/versionName;
   - application source SHA/tree;
   - unsigned APK SHA-256;
   - signed APK SHA-256;
   - production signer certificate SHA-256;
   - v3 signing lineage SHA-256.
6. Production update acceptance must use the normal Android upgrade path. Do not use `-d`, uninstall, or clear-data to make an invalid version transition pass.
7. A rollback is a separately designed recovery operation. Android versionCode monotonicity must not be bypassed as an ordinary rollback mechanism.
8. Changing version identity creates a new release candidate. Existing physical evidence never silently transfers.

## CI

`Production Android App Host CI` verifies:

- manifest fields parse correctly;
- current versionCode is strictly greater than the previous accepted versionCode;
- previous accepted baseline is still the known physical `1 / 0.1`;
- the assembled release APK `aapt badging` exactly matches the canonical version manifest.

The current transition is therefore:

`1 / 0.1 -> 26100601 / 0.2.0`

Physical update-in-place from the accepted signed version 1 remains required before Issue #587 can be closed.
