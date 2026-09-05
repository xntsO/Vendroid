[CmdletBinding()]
param(
    [string]$Ref = 'codex/release-2-gpt-stabilization',
    [switch]$Wait,
    [string]$RunId
)

$ErrorActionPreference = 'Stop'
$repository = 'xntsO/Vendroid'

function Invoke-GitHub {
    param([string[]]$Arguments)
    $output = & gh @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "GitHub CLI failed: gh $($Arguments -join ' ')"
    }
    return $output
}

Get-Command gh -ErrorAction Stop | Out-Null
if ($RunId) {
    if ($RunId -notmatch '^\d+$') { throw 'RunId must be a numeric GitHub Actions run ID.' }
} else {
    $validationId = [Guid]::NewGuid().ToString('N')
    Invoke-GitHub @('workflow', 'run', 'build-test-debug.yml', '--repo', $repository,
        '--ref', $Ref, '-f', "validation_id=$validationId") | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $runs = Invoke-GitHub @('run', 'list', '--repo', $repository,
            '--workflow', 'build-test-debug.yml', '--event', 'workflow_dispatch',
            '--branch', $Ref, '--limit', '30', '--json', 'databaseId,displayTitle') | ConvertFrom-Json
        $match = $runs | Where-Object { $_.displayTitle -eq "Build and test $validationId" } | Select-Object -First 1
        if ($match) { $RunId = [string]$match.databaseId; break }
        Start-Sleep -Seconds 3
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $RunId) {
        throw "Dispatch accepted, but run lookup timed out. Find 'Build and test $validationId' in GitHub Actions."
    }
}

Write-Host "Validation runs on GitHub Actions. No local build or USB write is performed."
Write-Host "https://github.com/$repository/actions/runs/$RunId"
if ($Wait) {
    & gh run watch $RunId --repo $repository --exit-status
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
Invoke-GitHub @('run', 'view', $RunId, '--repo', $repository)
Write-Host "Download evidence: gh run download $RunId --repo $repository --dir validation-$RunId"
