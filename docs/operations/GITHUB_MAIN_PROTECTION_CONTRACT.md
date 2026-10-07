# GitHub main protection contract

Status: **P1 GOVERNANCE CONTRACT / APPLY PENDING**

Updated: 2026-10-07

## Purpose

Protect the canonical `main` branches without creating a self-lockout for the owner-operated development model.

## Required policy

For both implementation and Licensing repositories:

- changes reach `main` through pull requests;
- required status checks use strict/up-to-date semantics;
- required approving review count is `0` because this is currently a single-owner repository;
- conversations must be resolved;
- linear history is required;
- force-push is prohibited;
- branch deletion is prohibited;
- administrator bypass remains available as an emergency recovery path until the policy has been exercised successfully.

## Stable required PR checks

Implementation repository `yaroshenkopavel/LiliyaCore-`:

- `Test LiliyaCore`
- `Android App Host exact build gate`

Licensing repository `yaroshenkopavel/LiliyaLicensingService-`:

- `test`
- `guard`

These were selected because they are stable pull-request gates. Push-only or release-only acceptance workflows are intentionally not required by branch protection, because requiring a check that does not run on every PR would deadlock `main`.

## Application

Use `scripts/Apply-GitHubMainProtection.ps1` with a short-lived GitHub token supplied only through the process environment.

Do not commit, echo, log, or pass the token as a command-line argument.

Implementation example:

```powershell
$env:GH_TOKEN = '<owner-provided short-lived token>'
.\scripts\Apply-GitHubMainProtection.ps1 \
    -Repository 'yaroshenkopavel/LiliyaCore-' \
    -RequiredContexts @('Test LiliyaCore','Android App Host exact build gate')
Remove-Item Env:GH_TOKEN
```

Licensing example:

```powershell
$env:GH_TOKEN = '<owner-provided short-lived token>'
.\scripts\Apply-GitHubMainProtection.ps1 \
    -Repository 'yaroshenkopavel/LiliyaLicensingService-' \
    -RequiredContexts @('test','guard')
Remove-Item Env:GH_TOKEN
```

## Acceptance

After application, verify through GitHub that:

1. `main` rejects force-push and deletion;
2. direct non-admin changes cannot bypass the PR/check contract;
3. required checks match the exact stable context names above;
4. a normal test PR can merge after required checks pass;
5. owner/admin emergency recovery remains available.

Do not mark governance GREEN until the settings are visible through a read-back endpoint and a normal PR path has been exercised.
