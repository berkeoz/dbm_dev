# ============================================================
# Get-DBMStatus.ps1
# For every environment in a DBmaestro project, reports:
#   - Current deployed version  (matches what the UI shows)
#   - Packages already deployed to that environment
#   - Packages available (not yet deployed) to that environment
#
# Usage:
#   .\Get-DBMStatus.ps1
#   .\Get-DBMStatus.ps1 -ProjectName "OTHER_PROJECT"
# ============================================================

param(
    [string]$ProjectName = "DEMO_AS_ORACLE",
    [string]$Server      = "WIN-HM6PVCVCPCB:8017",
    [string]$UserName    = "poc@dbmaestro.com",
    [string]$Password    = "CJg8b8T5L97LQqsXA2ojjCFWAMTXntIo",
    [string]$AgentJar    = "C:\Program Files (x86)\DBmaestro\DOP Server\Agent\DBmaestroAgent.jar",
    [string]$TempDir     = "$env:TEMP\DBMStatus"
)

# =====================================================================
# HELPERS
# =====================================================================

function Invoke-DBMAgent {
    param([string[]]$CmdArgs)
    $allArgs = $CmdArgs + @(
        "-Server",   $Server,
        "-AuthType", "DBmaestroAccount",
        "-UserName", $UserName,
        "-Password", $Password
    )
    $result = & java -jar $AgentJar @allArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        $errLine = $result | Where-Object { $_ -match "SEVERE|Error" } | Select-Object -First 1
        throw "DBmaestroAgent failed (exit $LASTEXITCODE): $errLine"
    }
    return $result
}

function Read-JsonFile([string]$Path) {
    if (-not (Test-Path $Path)) { throw "Expected output file not found: $Path" }
    return Get-Content $Path -Raw | ConvertFrom-Json
}

# Java inserts a Unicode narrow no-break space (U+202F) between time and AM/PM.
# Replace it with a regular space for clean display.
function Format-DBMDate([string]$dateStr) {
    if ([string]::IsNullOrWhiteSpace($dateStr)) { return "" }
    return $dateStr -replace [char]0x202F, ' '
}

# Given a base version name (e.g. "V3"), return the full name including any
# active revision suffix (e.g. "V3 (Rev. 1)") by looking in $allPackages.
# Falls back to the base name if no active revision exists.
function Resolve-FullVersionName([string]$baseName, [array]$allPackages) {
    $match = $allPackages |
        Where-Object {
            $_.Name           -like "$baseName*" -and
            $_.IsEnabled      -eq   $true         -and
            $_.State          -eq   1             -and
            $_.IsTemporary    -eq   $false         -and
            $_.IsAdhocPackage -eq   $false
        } |
        Sort-Object Id -Descending |
        Select-Object -First 1

    if ($match) { return $match.Name }
    return $baseName
}

# =====================================================================
# SETUP
# =====================================================================

if (-not (Test-Path $TempDir)) { New-Item -ItemType Directory -Path $TempDir | Out-Null }

Write-Host ""
Write-Host "============================================================"
Write-Host "  DBmaestro Environment Status Report"
Write-Host "  Project : $ProjectName"
Write-Host "  Server  : $Server"
Write-Host "============================================================"

# =====================================================================
# STEP 1 - Fetch project-level data (done once, shared across all envs)
# =====================================================================

Write-Host "`n[1/3] Fetching all packages for project '$ProjectName'..."
$packagesFile = "$TempDir\packages.json"
Invoke-DBMAgent @("-GetPackages", "-ProjectName", $ProjectName, "-FilePath", $packagesFile) | Out-Null
$allPackages = Read-JsonFile $packagesFile

# Packages available to deploy to Release Source: State=0, enabled, not temporary, not adhoc.
$availableForRS = $allPackages |
    Where-Object {
        $_.State          -eq   0      -and
        $_.IsEnabled      -eq   $true  -and
        $_.IsTemporary    -eq   $false -and
        $_.IsAdhocPackage -eq   $false
    } |
    Sort-Object Id   # ascending = natural pipeline order

Write-Host "[2/3] Fetching project data to discover environments..."
$projectDataFile = "$TempDir\project_data.json"
Invoke-DBMAgent @("-GetProjectData", "-ProjectName", $ProjectName, "-FilePath", $projectDataFile) | Out-Null
$projectData = Read-JsonFile $projectDataFile

# =====================================================================
# STEP 2 - Per-environment report
# =====================================================================

Write-Host "[3/3] Querying per-environment package status..."

# Accumulate summary rows for the final table
$summary = @()

foreach ($envType in $projectData.EnvironmentTypes) {
    foreach ($env in $envType.Environments) {

        $envName     = $env.Name
        $envTypeName = $envType.Name
        $isRS        = $envType.IsRMSource

        $safeEnvName     = $envName -replace '[^a-zA-Z0-9_-]', '_'
        $envPackagesFile = "$TempDir\env_$safeEnvName.json"

        Write-Host ""
        Write-Host "============================================================"
        Write-Host "  Environment : $envName"
        Write-Host "  Type        : $envTypeName"
        Write-Host "============================================================"

        try {
            Invoke-DBMAgent @(
                "-GetEnvPackages",
                "-ProjectName", $ProjectName,
                "-EnvName",     $envName,
                "-FilePath",    $envPackagesFile
            ) | Out-Null

            $envPackages = Read-JsonFile $envPackagesFile

            # ----------------------------------------------------------
            # CURRENT VERSION
            # GetEnvPackages returns packages in descending deployment
            # order. Current version = first entry with a non-null
            # EnvDeployed. Cross-reference GetPackages to get the full
            # name including any revision suffix (e.g. "V3 (Rev. 1)").
            # ----------------------------------------------------------
            $deployedRows = $envPackages |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_.EnvDeployed) }

            if ($deployedRows) {
                $current         = $deployedRows | Select-Object -First 1
                $currentVersion  = Resolve-FullVersionName $current.VersionName $allPackages
                $currentDate     = Format-DBMDate $current.EnvDeployed
            }
            else {
                $currentVersion  = "(no packages deployed)"
                $currentDate     = ""
            }

            Write-Host ""
            Write-Host ("  CURRENT VERSION : {0}   (deployed: {1})" -f $currentVersion, $currentDate)

            # ----------------------------------------------------------
            # DEPLOYED PACKAGES
            # All entries where EnvDeployed is not null, most recent first.
            # ----------------------------------------------------------
            Write-Host ""
            Write-Host "  --- DEPLOYED (already applied to this environment) ---"

            if ($deployedRows) {
                $deployedTable = $deployedRows | ForEach-Object {
                    [PSCustomObject]@{
                        Package     = Resolve-FullVersionName $_.VersionName $allPackages
                        Type        = $_.VersionType
                        RSDeployed  = Format-DBMDate $_.RSDeployed
                        EnvDeployed = Format-DBMDate $_.EnvDeployed
                    }
                }
                $deployedTable | Format-Table -AutoSize
            }
            else {
                Write-Host "    (none)"
                Write-Host ""
            }

            # ----------------------------------------------------------
            # AVAILABLE PACKAGES
            # Release Source : State=0 packages from GetPackages
            #                  (defined but not yet deployed to RS)
            # Downstream envs: entries in GetEnvPackages where RSDeployed
            #                  is set but EnvDeployed is null
            #                  (in RS but not yet promoted here)
            # ----------------------------------------------------------
            Write-Host "  --- AVAILABLE (can be deployed to this environment) ---"

            if ($isRS) {
                if ($availableForRS) {
                    $availTable = $availableForRS | ForEach-Object {
                        [PSCustomObject]@{
                            Package = $_.Name
                            Type    = if ($_.IsAdhocPackage) { "AdHoc" } else { "Regular" }
                            Scripts = $_.Scripts.Count
                            Status  = "Not deployed to RS"
                        }
                    }
                    $availTable | Format-Table -AutoSize
                }
                else {
                    Write-Host "    (none - RS is at the latest package)"
                    Write-Host ""
                }
            }
            else {
                $availableRows = $envPackages |
                    Where-Object {
                        -not [string]::IsNullOrWhiteSpace($_.RSDeployed) -and
                        [string]::IsNullOrWhiteSpace($_.EnvDeployed)
                    }

                if ($availableRows) {
                    $availTable = $availableRows | ForEach-Object {
                        [PSCustomObject]@{
                            Package    = Resolve-FullVersionName $_.VersionName $allPackages
                            Type       = $_.VersionType
                            RSDeployed = Format-DBMDate $_.RSDeployed
                            Status     = "Deployed to RS, not yet here"
                        }
                    }
                    $availTable | Format-Table -AutoSize
                }
                else {
                    Write-Host "    (none - environment is in sync with Release Source)"
                    Write-Host ""
                }
            }

            # Accumulate for summary
            $summary += [PSCustomObject]@{
                Type           = $envTypeName
                Environment    = $envName
                CurrentVersion = $currentVersion
                DeployedAt     = $currentDate
                DeployedCount  = ($deployedRows | Measure-Object).Count
                AvailableCount = if ($isRS) {
                                     ($availableForRS | Measure-Object).Count
                                 } else {
                                     ($envPackages | Where-Object {
                                         -not [string]::IsNullOrWhiteSpace($_.RSDeployed) -and
                                         [string]::IsNullOrWhiteSpace($_.EnvDeployed)
                                     } | Measure-Object).Count
                                 }
            }
        }
        catch {
            Write-Host "  ERROR: $_" -ForegroundColor Red
            $summary += [PSCustomObject]@{
                Type           = $envTypeName
                Environment    = $envName
                CurrentVersion = "ERROR"
                DeployedAt     = ""
                DeployedCount  = 0
                AvailableCount = 0
            }
        }
    }
}

# =====================================================================
# SUMMARY TABLE
# =====================================================================

Write-Host ""
Write-Host "============================================================"
Write-Host "  SUMMARY"
Write-Host "============================================================"
$summary | Format-Table -AutoSize

Write-Host "============================================================"
Write-Host "  Done."
Write-Host "============================================================"
Write-Host ""
