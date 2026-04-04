# ============================================================
# Get-DBMPackageStatus.ps1
# For every environment in a DBmaestro project, reports:
#   - Packages already deployed to that environment
#   - Packages available (not yet deployed) to that environment
# ============================================================

param(
    [string]$ProjectName = "DEMO_AS_ORACLE",
    [string]$Server      = "WIN-HM6PVCVCPCB:8017",
    [string]$UserName    = "poc@dbmaestro.com",
    [string]$Password    = "CJg8b8T5L97LQqsXA2ojjCFWAMTXntIo",
    [string]$AgentJar    = "C:\Program Files (x86)\DBmaestro\DOP Server\Agent\DBmaestroAgent.jar",
    [string]$TempDir     = "$env:TEMP\DBMPackageStatus"
)

# ---- helpers -------------------------------------------------------

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

# Normalise dates: Java inserts a Unicode narrow no-break space (U+202F) between
# time and AM/PM. Replace it with a regular space for clean display.
function Format-DBMDate([string]$dateStr) {
    if ([string]::IsNullOrWhiteSpace($dateStr)) { return "" }
    return $dateStr -replace [char]0x202F, ' '
}

# Given a base version name (e.g. "V3"), find the latest active revision in
# $GetPackagesData (e.g. "V3 (Rev. 1)").  Falls back to the base name if no
# active revision exists.
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

# ---- setup ---------------------------------------------------------

if (-not (Test-Path $TempDir)) { New-Item -ItemType Directory -Path $TempDir | Out-Null }

Write-Host ""
Write-Host "============================================================"
Write-Host "  DBmaestro Package Status Report"
Write-Host "  Project : $ProjectName"
Write-Host "  Server  : $Server"
Write-Host "============================================================"

# ---- Step 1: fetch all project packages ----------------------------
Write-Host "`n[1/3] Fetching all packages for project '$ProjectName'..."
$packagesFile = "$TempDir\packages.json"
Invoke-DBMAgent @("-GetPackages", "-ProjectName", $ProjectName, "-FilePath", $packagesFile) | Out-Null
$allPackages = Read-JsonFile $packagesFile

# Packages available to deploy to the Release Source are those that are
# defined in the project but not yet active (State=0), enabled, non-temporary,
# non-adhoc.  Build this lookup once so we can reuse it per-environment.
$availableForRS = $allPackages |
    Where-Object {
        $_.State          -eq   0     -and
        $_.IsEnabled      -eq   $true -and
        $_.IsTemporary    -eq   $false -and
        $_.IsAdhocPackage -eq   $false
    } |
    Sort-Object Id  # ascending = natural pipeline order

# ---- Step 2: fetch project data to discover environments -----------
Write-Host "[2/3] Fetching project data to discover environments..."
$projectDataFile = "$TempDir\project_data.json"
Invoke-DBMAgent @("-GetProjectData", "-ProjectName", $ProjectName, "-FilePath", $projectDataFile) | Out-Null
$projectData = Read-JsonFile $projectDataFile

# Build a quick lookup: environment name -> IsRMSource (true = Release Source)
$envIsRS = @{}
foreach ($envType in $projectData.EnvironmentTypes) {
    foreach ($env in $envType.Environments) {
        $envIsRS[$env.Name] = $envType.IsRMSource
    }
}

# ---- Step 3: per-environment package status ------------------------
Write-Host "[3/3] Querying package status per environment..."

foreach ($envType in $projectData.EnvironmentTypes) {
    foreach ($env in $envType.Environments) {

        $envName     = $env.Name
        $envTypeName = $envType.Name
        $isRS        = $envType.IsRMSource

        Write-Host ""
        Write-Host "------------------------------------------------------------"
        Write-Host "  Environment : $envName  (type: $envTypeName)"
        Write-Host "------------------------------------------------------------"

        $safeEnvName     = $envName -replace '[^a-zA-Z0-9_-]', '_'
        $envPackagesFile = "$TempDir\env_$safeEnvName.json"

        try {
            Invoke-DBMAgent @(
                "-GetEnvPackages",
                "-ProjectName", $ProjectName,
                "-EnvName",     $envName,
                "-FilePath",    $envPackagesFile
            ) | Out-Null

            $envPackages = Read-JsonFile $envPackagesFile

            # ---- DEPLOYED packages ----------------------------------------
            # Entries where EnvDeployed is not null, ordered most-recent first.
            $deployedRows = $envPackages |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_.EnvDeployed) }

            Write-Host ""
            Write-Host "  [DEPLOYED - already applied to this environment]"

            if ($deployedRows) {
                $deployedTable = $deployedRows | ForEach-Object {
                    [PSCustomObject]@{
                        Package    = Resolve-FullVersionName $_.VersionName $allPackages
                        Type       = $_.VersionType
                        RSDeployed = Format-DBMDate $_.RSDeployed
                        EnvDeployed= Format-DBMDate $_.EnvDeployed
                    }
                }
                $deployedTable | Format-Table -AutoSize
            }
            else {
                Write-Host "    (none)"
            }

            # ---- AVAILABLE packages ---------------------------------------
            # Two cases depending on environment type:
            #
            #   Release Source  -> packages from GetPackages with State=0
            #                      (defined in the project but not yet deployed to RS)
            #
            #   All other envs  -> entries in GetEnvPackages where RSDeployed is set
            #                      but EnvDeployed is null
            #                      (already in RS, waiting to be promoted here)

            Write-Host "  [AVAILABLE - can be deployed to this environment]"

            if ($isRS) {
                # For Release Source, available packages come from GetPackages
                if ($availableForRS) {
                    $availTable = $availableForRS | ForEach-Object {
                        [PSCustomObject]@{
                            Package  = $_.Name
                            Type     = if ($_.IsAdhocPackage) { "AdHoc" } else { "Regular" }
                            Scripts  = $_.Scripts.Count
                            Status   = "Not deployed to RS"
                        }
                    }
                    $availTable | Format-Table -AutoSize
                }
                else {
                    Write-Host "    (none - RS is at the latest package)"
                }
            }
            else {
                # For downstream environments, available = in RS but not yet here
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
        }
        catch {
            Write-Host "  ERROR querying environment '$envName': $_" -ForegroundColor Red
        }
    }
}

Write-Host ""
Write-Host "============================================================"
Write-Host "  Done."
Write-Host "============================================================"
Write-Host ""
