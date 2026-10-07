# Android release versioning policy

Status: **P1 release contract — accepted baseline promoted**

## Current physically accepted baseline

Application source:

`d484b2ac07be8650f240fb550f211e34b7718243`

Source tree:

`6c1ed58a31b3baf40bc6ec04db45c2fd11fe3809`

Installed signed APK SHA-256:

`03fb47498a56be0e8f30fec188fe5599f466c2fd3aa8e4404b7f96808076f185`

Accepted package version:

- versionCode: `26100601`
- versionName: `0.2.0`

Accepted unsigned APK SHA-256:

`01760b3855ca35bd4b4f8762757d232347b1b16c8138b9d1a4796d640eb47972`

Production signer certificate SHA-256:

`f6d891075948966eec3518bfbc5dc920971c822a7277d0c904d33eab29eba2af`

Canonical v3 lineage SHA-256:

`5332ba6d128deb0647be1d89b0fecea53560fc60d1c70cacce8bbb09d4d1c43b`

The historical `1 / 0.1` release remains valid historical evidence but is no longer the latest physically accepted baseline.

## Current repository version state

Canonical source of build version:

`android-app/version.properties`

After physical acceptance, an accepted-main state is represented by:

- `versionCode == previousAcceptedVersionCode`
- `versionName == previousAcceptedVersionName`

A future successor candidate must set a strictly larger `versionCode` while leaving the previous-accepted fields bound to the latest physically accepted release until the candidate itself passes signed physical acceptance.

There is currently no newer Android release candidate beyond `26100601 / 0.2.0`.

## Rules

1. `versionCode` uses `YYMMDDRR`; `RR` is in `01..99` and the date must be valid.
2. `versionName` uses numeric `MAJOR.MINOR.PATCH`.
3. `previousAcceptedVersionCode` and `previousAcceptedVersionName` identify the latest physically accepted baseline, not merely the previous Git commit.
4. On accepted main, current version may equal the previous-accepted baseline, but both code and name must match exactly.
5. A successor candidate must have `versionCode > previousAcceptedVersionCode`.
6. Do not advance the previous-accepted fields until the candidate's signed APK has passed update-in-place and physical runtime acceptance.
7. Every accepted release evidence must bind version, application source/tree, unsigned APK SHA-256, signed APK SHA-256, production signer certificate SHA-256 and v3 lineage SHA-256.
8. Production update acceptance must use normal Android upgrade semantics. Do not use `-d`, uninstall or clear-data to make an invalid transition pass.
9. A rollback is a separately designed recovery operation. Android version monotonicity must not be bypassed as an ordinary rollback mechanism.
10. Exact-SHA physical evidence never silently transfers to another source or artifact identity.

## CI

`Production Android App Host CI` verifies:

- canonical version fields parse correctly;
- current `versionCode` is never lower than the accepted baseline;
- if current equals accepted, `versionName` must also equal the accepted name and CI reports `ACCEPTED_BASELINE`;
- if current is greater, CI reports `SUCCESSOR_CANDIDATE`;
- the assembled release APK `aapt badging` exactly matches the canonical current version.

Current state:

`26100601 / 0.2.0 == accepted 26100601 / 0.2.0`

Issue #587 is CLOSED GREEN after production signing and physical update-in-place acceptance.
