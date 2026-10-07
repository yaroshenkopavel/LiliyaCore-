param(
    [Parameter(Mandatory = $true)]
    [string] $Repository,
    [Parameter(Mandatory = $true)]
    [string[]] $RequiredContexts,
    [string] $Branch = 'main'
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($env:GH_TOKEN)) {
    throw 'GH_TOKEN must be provided through the process environment; do not place tokens in source or arguments.'
}

$headers = @{
    Authorization = 'Bearer ' + $env:GH_TOKEN
    Accept = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
    'User-Agent' = 'LiliyaCore-governance-policy'
}

$body = @{
    required_status_checks = @{
        strict = $true
        contexts = $RequiredContexts
    }
    enforce_admins = $false
    required_pull_request_reviews = @{
        dismiss_stale_reviews = $false
        require_code_owner_reviews = $false
        required_approving_review_count = 0
        require_last_push_approval = $false
    }
    restrictions = $null
    required_linear_history = $true
    allow_force_pushes = $false
    allow_deletions = $false
    block_creations = $false
    required_conversation_resolution = $true
    lock_branch = $false
    allow_fork_syncing = $true
} | ConvertTo-Json -Depth 8

$uri = 'https://api.github.com/repos/' + $Repository + '/branches/' + $Branch + '/protection'
$result = Invoke-RestMethod -Method Put -Uri $uri -Headers $headers -ContentType 'application/json' -Body $body

Write-Host ('GOVERNANCE_REPOSITORY=' + $Repository)
Write-Host ('GOVERNANCE_BRANCH=' + $Branch)
Write-Host ('GOVERNANCE_REQUIRED_CONTEXTS=' + ($RequiredContexts -join ','))
Write-Host ('GOVERNANCE_ENFORCE_ADMINS=' + $result.enforce_admins.enabled)
Write-Host ('GOVERNANCE_REQUIRED_APPROVALS=' + $result.required_pull_request_reviews.required_approving_review_count)
Write-Host ('GOVERNANCE_FORCE_PUSH_ALLOWED=' + $result.allow_force_pushes.enabled)
Write-Host ('GOVERNANCE_DELETE_ALLOWED=' + $result.allow_deletions.enabled)
Write-Host ('GOVERNANCE_LINEAR_HISTORY=' + $result.required_linear_history.enabled)
Write-Host ('GOVERNANCE_CONVERSATION_RESOLUTION=' + $result.required_conversation_resolution.enabled)
Write-Host 'GOVERNANCE_APPLY=PASS'
