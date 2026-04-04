# ============================================================
# Get-DBMVersion.ps1
# Reports the current deployed version for every environment
# in a DBmaestro project, matching what the UI displays.
# ============================================================

param(
    [string]$ProjectName = "DEMO_AS_ORACLE",
    [string]$Server      = "WIN-HM6PVCVCPCB:8017",
    [string]$UserName    = "poc@dbmaestro.com",
    [string]$Password    = "CJg8b8T5L97LQqsXA2ojjCFWAMTXntIo",
    [string]$AgentJar    = "C:\Program Files (x86)\DBmaestro\DOP Server\Agent\DBmaestroAgent.jar",
    [string]$TempDir     = "$env:TEMP\DBMVersionCheck"
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
    # Capture stderr (agent writes output there) + stdout
    $result = & java -jar $AgentJar @allArgs 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $errLine = $result | Where-Object { $_ -match "SEVERE|Error" } | Select-Object -First 1
        throw "DBmaestroAgent failed (exit $exitCode): $errLine"
    }
    return $result
}

function Read-JsonFile([string]$Path) {
    if (-not (Test-Path $Path)) { throw "Expected output file not found: $Path" }
    return Get-Content $Path -Raw | ConvertFrom-Json
}


# ---- setup ---------------------------------------------------------

if (-not (Test-Path $TempDir)) { New-Item -ItemType Directory -Path $TempDir | Out-Null }

Write-Host ""
Write-Host "=========================================="
Write-Host "  DBmaestro Environment Version Report"
Write-Host "  Project : $ProjectName"
Write-Host "  Server  : $Server"
Write-Host "=========================================="

# ---- Step 1: fetch all project packages ----------------------------
Write-Host "`n[1/3] Fetching all packages for project '$ProjectName'..."
$packagesFile = "$TempDir\packages.json"
Invoke-DBMAgent @("-GetPackages", "-ProjectName", $ProjectName, "-FilePath", $packagesFile) | Out-Null
$allPackages = Read-JsonFile $packagesFile

# ---- Step 2: fetch project data (to discover environments) ---------
Write-Host "[2/3] Fetching project data to discover environments..."
$projectDataFile = "$TempDir\project_data.json"
Invoke-DBMAgent @("-GetProjectData", "-ProjectName", $ProjectName, "-FilePath", $projectDataFile) | Out-Null
$projectData = Read-JsonFile $projectDataFile

# ---- Step 3: per-environment version lookup ------------------------
Write-Host "[3/3] Querying deployed version per environment..."
Write-Host ""

$results = @()

foreach ($envType in $projectData.EnvironmentTypes) {
    foreach ($env in $envType.Environments) {
        $envName     = $env.Name
        $envTypeName = $envType.Name

        # Safe file name
        $safeEnvName    = $envName -replace '[^a-zA-Z0-9_-]', '_'
        $envPackagesFile = "$TempDir\env_$safeEnvName.json"

        try {
            Invoke-DBMAgent @(
                "-GetEnvPackages",
                "-ProjectName", $ProjectName,
                "-EnvName",     $envName,
                "-FilePath",    $envPackagesFile
            ) | Out-Null

            $envPackages = Read-JsonFile $envPackagesFile

            # GetEnvPackages returns packages in descending deployment order.
            # Current version = first entry where EnvDeployed is not null.
            $deployed = $envPackages |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_.EnvDeployed) }

            if (-not $deployed) {
                $results += [PSCustomObject]@{
                    EnvironmentType = $envTypeName
                    Environment     = $envName
                    CurrentVersion  = "(no packages deployed)"
                    DeployedAt      = ""
                }
                continue
            }

            $current         = $deployed | Select-Object -First 1
            $baseVersionName = $current.VersionName

            # Find the full version name (may include a revision suffix like "V3 (Rev. 1)")
            # from GetPackages: active, non-temporary, non-adhoc package whose name starts
            # with the base version name, sorted by Id descending (highest = newest revision)
            $fullPkg = $allPackages |
                Where-Object {
                    $_.Name          -like "$baseVersionName*" -and
                    $_.IsEnabled     -eq   $true               -and
                    $_.State         -eq   1                    -and
                    $_.IsTemporary   -eq   $false               -and
                    $_.IsAdhocPackage -eq  $false
                } |
                Sort-Object Id -Descending |
                Select-Object -First 1

            $fullVersionName = if ($fullPkg) { $fullPkg.Name } else { $baseVersionName }

            # Normalise the date string: replace Unicode narrow no-break space (U+202F)
            # that Java inserts between time and AM/PM with a regular space.
            $deployedAt = $current.EnvDeployed -replace [char]0x202F, ' '

            $results += [PSCustomObject]@{
                EnvironmentType = $envTypeName
                Environment     = $envName
                CurrentVersion  = $fullVersionName
                DeployedAt      = $deployedAt
            }
        }
        catch {
            $results += [PSCustomObject]@{
                EnvironmentType = $envTypeName
                Environment     = $envName
                CurrentVersion  = "ERROR: $_"
                DeployedAt      = ""
            }
        }
    }
}

# ---- Output --------------------------------------------------------
$results | Format-Table -AutoSize

# Also show a clean summary line per environment type
Write-Host "--- Summary ---"
foreach ($r in $results) {
    Write-Host ("  [{0}] {1,-20} =>  Ver {2}  (deployed: {3})" -f `
        $r.EnvironmentType, $r.Environment, $r.CurrentVersion, $r.DeployedAt)
}
Write-Host ""
