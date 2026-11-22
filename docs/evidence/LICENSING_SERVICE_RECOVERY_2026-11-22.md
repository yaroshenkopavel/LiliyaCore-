# Licensing Service recovery investigation вЂ” 2026-11-22

## Repository and intended topology
- Discovered dedicated backend: `yaroshenkopavel/LiliyaLicensingService-` (canonical `main`).
- Operations reference: `docs/OPERATIONS.md`; deployed helper: `C:\LiliyaServer\bin\startup\Start-LiliyaLicensingService.ps1`.
- Required components: PostgreSQL 17 (`postgresql-x64-17-liliya`), OpenBao TLS on 8200, Licensing HTTPS on 8443. OpenBao Transit signing and PostgreSQL authoritative licensing state must not be bypassed.

## Read-only environment inventory
- `postgresql-x64-17-liliya`: Running / Automatic; port `127.0.0.1:5432` listening.
- `postgresql-x64-16`: Stopped / Manual (rollback only).
- Scheduled task `Liliya Licensing Service Startup`: Disabled; last task result 1 (2026-10-30).
- Deployed startup helper, source checkout, Licensing CA, PostgreSQL/activation-writer/OpenBao runtime token/TLS DPAPI secret files exist. Secret contents were not logged or exported.
- Initial Licensing port 8443 and OpenBao port 8200 not listening.

## Startup attempt
- Launched deployed Licensing startup helper with no configuration or database changes.
- OpenBao `bao.exe` became reachable at `127.0.0.1:8200` over HTTPS. Using its pinned CA and `curl --ssl-no-revoke --cacert`, `/v1/sys/seal-status` returned `initialized=true`, `sealed=false`, `version=2.6.2`.
- Licensing HTTPS port 8443 was **not listening** at last observation. The startup helper job had not returned at last poll. Do not claim backend ready.
- PowerShell `Invoke-RestMethod` without explicit pinned CA failed TLS trust; this does not establish that OpenBao itself is unhealthy.

## Pending
1. Diagnose why Licensing startup helper waits after OpenBao readiness; inspect launcher child-process and stdout/pipe handling, preserving service and secret boundaries.
2. Establish Licensing 8443 and verify `/health/ready` with pinned Licensing CA.
3. Use owner-authorized activation issuance CLI and genuine device-bound code, then execute physical first-chat test with real TLS and license verification.
## Follow-up isolation (same date)
- Re-ran the deployed OpenBao launcher independently: `OPENBAO_STARTUP_READY`, exit 0, both 8200/8201 PASS.
- Verified `/v1/sys/seal-status` with pinned `C:\LiliyaServer\config\openbao\tls\ca.crt`: initialized=true, sealed=false.
- Retried deployed Licensing launcher; returned exit 1: `Licensing Service exited before RUNTIME_READY`.
- Service stdout: `BOOTSTRAP_REJECTED`, `INTERNAL_FAILURE`, `detail=NONE`; stderr: `LICENSING_BOOTSTRAP_FAILURE_TYPE=java.lang.IllegalStateException`. No Java listener on 8443.
- Source inspection places the caught exception in `LicensingProductionService.create(...)`, before `service.start()`. This narrows the failure to production composition, not runtime listener readiness. Exact underlying cause not yet established.
- Existing local Licensing Service source checkout has an **uncommitted diagnostic change** in `issuer-deployment/.../LicensingDeploymentMain.kt` that prints exception class only. Preserve it and do not overwrite without provenance review.
- No license code issued, no database changes, no production key material exported.
