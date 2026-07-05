<#
.SYNOPSIS
Deploy the local backend and frontend source folders to a remote Docker host.

.EXAMPLE
.\scripts\remote-update.ps1 `
  -Target root@118.25.150.9 `
  -PrivateKeyPath C:\Users\Administrator\.ssh\lens

.EXAMPLE
.\scripts\remote-update.ps1 `
  -HostName 118.25.150.9 `
  -User root `
  -Port 22 `
  -PrivateKeyPath C:\Users\Administrator\.ssh\lens `
  -RemoteBasePath /root/team/ai-fusion-video `
  -UpdateMode frontend

.EXAMPLE
.\scripts\remote-update.ps1 -UpdateMode frontend

Default incremental deployment keeps Docker images/build cache and reuses pnpm/Maven package caches.

.EXAMPLE
.\scripts\remote-update.ps1 -UpdateMode both -DeployStrategy clean -PruneDockerCache $true

Run a full clean deployment when the remote host is low on disk or Docker cache is corrupted.
#>

[CmdletBinding()]
param(
    [string]$Target,

    [string]$HostName = "118.25.150.9",

    [string]$User = "root",

    [string]$PrivateKeyPath = "C:\Users\Administrator\.ssh\lens",

    [int]$Port = 22,

    [string]$RemoteBasePath = "/root/team/ai-fusion-video",

    [string]$ComposeFile = "docker-compose.yml",

    [ValidateSet("both", "backend", "frontend")]
    [string]$UpdateMode = "both",

    [ValidateSet("incremental", "clean")]
    [string]$DeployStrategy = "incremental",

    [ValidateSet("auto", "archive", "rsync")]
    [string]$TransferMode = "auto",

    [string]$BackendService = "backend",

    [string]$FrontendService = "frontend",

    [string]$BackendContainerName = "fusion-backend",

    [string]$FrontendContainerName = "fusion-frontend",

    [string[]]$BackendImageNames = @(
        "ai-fusion-video:local",
        "ai-fusion-video"
    ),

    [string[]]$FrontendImageNames = @(
        "ai-fusion-video-web:local",
        "ai-fusion-video-web"
    ),

    [bool]$PruneDockerCache = $false,

    [string[]]$ArchiveExcludes = @(
        "ai-fusion-video-web/node_modules",
        "ai-fusion-video-web/.next",
        "ai-fusion-video-web/.turbo",
        "ai-fusion-video/target",
        "ai-fusion-video/.mvn/repository"
    ),

    [ValidateSet("yes", "no", "ask", "accept-new")]
    [string]$StrictHostKeyChecking = "accept-new",

    [string]$ProjectRoot
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Assert-Command {
    param([Parameter(Mandatory = $true)][string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

function Resolve-RequiredPath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $resolved = Resolve-Path -LiteralPath $Path -ErrorAction SilentlyContinue
    if (-not $resolved) {
        throw "$Label does not exist: $Path"
    }
    return $resolved.Path
}

function ConvertTo-BashSingleQuoted {
    param([AllowNull()][string]$Value)

    if ($null -eq $Value) {
        return "''"
    }
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function New-BashArrayLiteral {
    param([string[]]$Values)

    if (-not $Values -or $Values.Count -eq 0) {
        return ""
    }
    return (($Values | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object {
        ConvertTo-BashSingleQuoted $_
    }) -join " ")
}

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$StepName
    )

    Write-Host "==> $StepName"
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$StepName failed with exit code $LASTEXITCODE."
    }
}

function Test-RemoteRsyncAvailable {
    Write-Host "==> Check remote rsync availability"
    & ssh @script:SshArgs $script:RemoteTarget "command -v rsync >/dev/null 2>&1"
    return $LASTEXITCODE -eq 0
}

function Get-RsyncExcludeArgs {
    param([Parameter(Mandatory = $true)][string]$SourceFolder)

    $excludeArgs = @()
    $sourcePrefix = ($SourceFolder.TrimEnd("/", "\") + "/").Replace("\", "/")
    foreach ($Exclude in $script:ArchiveExcludes) {
        if ([string]::IsNullOrWhiteSpace($Exclude)) {
            continue
        }
        $normalized = $Exclude.Replace("\", "/").TrimStart("/")
        if ($normalized.StartsWith($sourcePrefix)) {
            $relativeExclude = $normalized.Substring($sourcePrefix.Length)
            if (-not [string]::IsNullOrWhiteSpace($relativeExclude)) {
                $excludeArgs += "--exclude=$relativeExclude"
            }
        }
    }
    return $excludeArgs
}

function Sync-SourceFoldersWithRsync {
    $sshCommand = "ssh -i `"$script:PrivateKeyPath`" -p $script:Port -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=$script:StrictHostKeyChecking"
    if ($script:StrictHostKeyChecking -eq "no") {
        $sshCommand += " -o UserKnownHostsFile=/dev/null"
    }

    Push-Location -LiteralPath $script:ProjectRoot
    try {
        foreach ($SourceFolder in $script:SourceFolders) {
            $remoteDestination = "${script:RemoteTarget}:$script:RemoteBasePath/$SourceFolder/"
            $rsyncArgs = @(
                "-az",
                "--delete",
                "--stats"
            )
            $rsyncArgs += Get-RsyncExcludeArgs -SourceFolder $SourceFolder
            $rsyncArgs += @(
                "-e", $sshCommand,
                "$SourceFolder/",
                $remoteDestination
            )
            Invoke-NativeCommand -FilePath "rsync" -Arguments $rsyncArgs -StepName "Sync $SourceFolder with rsync"
        }
    }
    finally {
        Pop-Location
    }
}

function Invoke-RemoteBash {
    param(
        [Parameter(Mandatory = $true)][string]$StepName,
        [Parameter(Mandatory = $true)][string]$Script
    )

    Write-Host "==> $StepName"

    $scriptId = [System.Guid]::NewGuid().ToString("N")
    $localScriptPath = Join-Path ([System.IO.Path]::GetTempPath()) "ai-fusion-video-remote-$scriptId.sh"
    $remoteScriptPath = "/tmp/ai-fusion-video-remote-$scriptId.sh"
    $remoteScriptQuoted = ConvertTo-BashSingleQuoted $remoteScriptPath

    try {
        $normalizedScript = $Script -replace "`r`n", "`n" -replace "`r", "`n"
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($localScriptPath, $normalizedScript, $utf8NoBom)

        Invoke-NativeCommand `
            -FilePath "scp" `
            -Arguments ($script:ScpArgs + @($localScriptPath, "${script:RemoteTarget}:$remoteScriptPath")) `
            -StepName "Upload remote step script"

        $remoteCommand = "bash $remoteScriptQuoted; rc=`$?; rm -f $remoteScriptQuoted; exit `$rc"
        & ssh @script:SshArgs $script:RemoteTarget $remoteCommand
        if ($LASTEXITCODE -ne 0) {
            throw "$StepName failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        if (Test-Path -LiteralPath $localScriptPath) {
            Remove-Item -LiteralPath $localScriptPath -Force
        }
    }
}

Assert-Command "ssh"
Assert-Command "scp"

if (-not [string]::IsNullOrWhiteSpace($Target)) {
    $targetPattern = '^(?:(?<user>[^@:\s]+)@)?(?<host>[^:\s]+)(?::(?<port>\d+))?$'
    $targetMatch = [regex]::Match($Target.Trim(), $targetPattern)
    if (-not $targetMatch.Success) {
        throw "Invalid Target '$Target'. Use root@1.2.3.4 or root@example.com:22."
    }
    if ($targetMatch.Groups["user"].Success) {
        $User = $targetMatch.Groups["user"].Value
    }
    $HostName = $targetMatch.Groups["host"].Value
    if ($targetMatch.Groups["port"].Success) {
        $Port = [int]$targetMatch.Groups["port"].Value
    }
}

if ([string]::IsNullOrWhiteSpace($HostName)) {
    throw "Missing remote host. Pass -Target root@SERVER_IP or set the HostName default in this script."
}

if ([string]::IsNullOrWhiteSpace($PrivateKeyPath)) {
    throw "Missing private key path. Pass -PrivateKeyPath C:\path\to\key or set the PrivateKeyPath default in this script."
}

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}

$ProjectRoot = Resolve-RequiredPath -Path $ProjectRoot -Label "Project root"
$PrivateKeyPath = Resolve-RequiredPath -Path $PrivateKeyPath -Label "Private key"

$UpdateBackend = $UpdateMode -eq "both" -or $UpdateMode -eq "backend"
$UpdateFrontend = $UpdateMode -eq "both" -or $UpdateMode -eq "frontend"

$SourceFolders = @()
$SelectedContainerNames = @()
$SelectedImageNames = @()
$SelectedServices = @()

if ($UpdateBackend) {
    $BackendDir = Join-Path $ProjectRoot "ai-fusion-video"
    Resolve-RequiredPath -Path $BackendDir -Label "Backend folder" | Out-Null
    $SourceFolders += "ai-fusion-video"
    if (-not [string]::IsNullOrWhiteSpace($BackendContainerName)) {
        $SelectedContainerNames += $BackendContainerName
    }
    $SelectedImageNames += $BackendImageNames
    $SelectedServices += $BackendService
}

if ($UpdateFrontend) {
    $FrontendDir = Join-Path $ProjectRoot "ai-fusion-video-web"
    Resolve-RequiredPath -Path $FrontendDir -Label "Frontend folder" | Out-Null
    $SourceFolders += "ai-fusion-video-web"
    if (-not [string]::IsNullOrWhiteSpace($FrontendContainerName)) {
        $SelectedContainerNames += $FrontendContainerName
    }
    $SelectedImageNames += $FrontendImageNames
    $SelectedServices += $FrontendService
}

$RemoteBasePath = $RemoteBasePath.TrimEnd("/")
$RemoteTarget = "$User@$HostName"
$Timestamp = Get-Date -Format "yyyyMMddHHmmss"
$ProcessId = [System.Diagnostics.Process]::GetCurrentProcess().Id
$ArchiveName = "ai-fusion-video-$UpdateMode-src-$Timestamp-$ProcessId.tar.gz"
$ArchivePath = Join-Path ([System.IO.Path]::GetTempPath()) $ArchiveName
$RemoteArchivePath = "/tmp/$ArchiveName"

$SshArgs = @(
    "-T",
    "-i", $PrivateKeyPath,
    "-p", [string]$Port,
    "-o", "IdentitiesOnly=yes",
    "-o", "BatchMode=yes",
    "-o", "StrictHostKeyChecking=$StrictHostKeyChecking"
)
$ScpArgs = @(
    "-i", $PrivateKeyPath,
    "-P", [string]$Port,
    "-o", "IdentitiesOnly=yes",
    "-o", "BatchMode=yes",
    "-o", "StrictHostKeyChecking=$StrictHostKeyChecking"
)
if ($StrictHostKeyChecking -eq "no") {
    $SshArgs += @("-o", "UserKnownHostsFile=/dev/null")
    $ScpArgs += @("-o", "UserKnownHostsFile=/dev/null")
}

$LocalRsyncAvailable = $null -ne (Get-Command "rsync" -ErrorAction SilentlyContinue)
$RemoteRsyncAvailable = $false
if (($TransferMode -eq "auto" -or $TransferMode -eq "rsync") -and $LocalRsyncAvailable) {
    $RemoteRsyncAvailable = Test-RemoteRsyncAvailable
}

if ($TransferMode -eq "rsync" -and -not ($LocalRsyncAvailable -and $RemoteRsyncAvailable)) {
    throw "TransferMode=rsync requires rsync on both local machine and remote host."
}

$UseRsync = $TransferMode -eq "rsync" -or ($TransferMode -eq "auto" -and $LocalRsyncAvailable -and $RemoteRsyncAvailable)
if ($UseRsync) {
    Assert-Command "rsync"
    Write-Host "==> Transfer mode: rsync (only changed files will be transferred)"
} else {
    Assert-Command "tar"
    Write-Host "==> Transfer mode: archive (full source archive upload; Docker/package caches are still kept)"
}

$RemoteBaseQuoted = ConvertTo-BashSingleQuoted $RemoteBasePath
$RemoteArchiveQuoted = ConvertTo-BashSingleQuoted $(if ($UseRsync) { "" } else { $RemoteArchivePath })
$ComposeFileQuoted = ConvertTo-BashSingleQuoted $ComposeFile
$SourceFolderArray = New-BashArrayLiteral $SourceFolders
$ContainerArray = New-BashArrayLiteral $SelectedContainerNames
$ImageArray = New-BashArrayLiteral ($SelectedImageNames | Select-Object -Unique)
$ServiceArray = New-BashArrayLiteral $SelectedServices
$CleanDeploy = $DeployStrategy -eq "clean"
$CleanDeployFlag = if ($CleanDeploy) { "1" } else { "0" }
$PruneDockerCacheFlag = if ($PruneDockerCache) { "1" } else { "0" }

$CleanupScript = @'
set -Eeuo pipefail

remote_base=__REMOTE_BASE__
source_dirs=(__SOURCE_DIRS__)
containers=(__CONTAINERS__)
images=(__IMAGES__)
clean_deploy=__CLEAN_DEPLOY__
prune_docker_cache=__PRUNE_DOCKER_CACHE__

echo "[remote] ensure base directory: $remote_base"
mkdir -p "$remote_base"

if [ "$clean_deploy" = "1" ]; then
  echo "[remote] clean deploy: stop/remove selected containers, images, and source folders"

  if [ "${#containers[@]}" -gt 0 ]; then
    echo "[remote] docker stop: ${containers[*]}"
    docker stop "${containers[@]}" 2>/dev/null || true

    echo "[remote] docker rm: ${containers[*]}"
    docker rm "${containers[@]}" 2>/dev/null || true
  fi

  if [ "${#images[@]}" -gt 0 ]; then
    echo "[remote] docker rmi: ${images[*]}"
    docker rmi "${images[@]}" 2>/dev/null || true
  fi

  echo "[remote] remove old source folders"
  for source_dir in "${source_dirs[@]}"; do
    rm -rf "$remote_base/$source_dir"
  done
else
  echo "[remote] incremental deploy: keep existing containers, images, source folders, and build cache"
fi

if [ "$prune_docker_cache" = "1" ]; then
  echo "[remote] filesystem disk usage before prune"
  df -h || true

  echo "[remote] docker disk usage before prune"
  docker system df || true

  echo "[remote] prune docker build cache"
  docker builder prune -af || true
  if docker buildx version >/dev/null 2>&1; then
    docker buildx prune -af || true
  fi

  echo "[remote] prune unused docker images and stopped containers"
  docker image prune -af || true
  docker container prune -f || true

  echo "[remote] docker disk usage after prune"
  docker system df || true

  echo "[remote] filesystem disk usage after prune"
  df -h || true
fi

for source_dir in "${source_dirs[@]}"; do
  mkdir -p "$remote_base/$source_dir"
done
'@
$CleanupScript = $CleanupScript.
    Replace("__REMOTE_BASE__", $RemoteBaseQuoted).
    Replace("__SOURCE_DIRS__", $SourceFolderArray).
    Replace("__CONTAINERS__", $ContainerArray).
    Replace("__IMAGES__", $ImageArray).
    Replace("__CLEAN_DEPLOY__", $CleanDeployFlag).
    Replace("__PRUNE_DOCKER_CACHE__", $PruneDockerCacheFlag)

$DeployScript = @'
set -Eeuo pipefail

remote_base=__REMOTE_BASE__
archive=__REMOTE_ARCHIVE__
compose_file=__COMPOSE_FILE__
services=(__SERVICES__)

if [ -n "$archive" ]; then
  trap 'rm -f "$archive"' EXIT

  echo "[remote] extract source archive"
  mkdir -p "$remote_base"
  tar -xzf "$archive" -C "$remote_base"
else
  echo "[remote] source already synced by rsync"
fi

cd "$remote_base"
if [ ! -f "$compose_file" ]; then
  echo "[remote] missing compose file: $remote_base/$compose_file" >&2
  exit 1
fi

for service in "${services[@]}"; do
  echo "[remote] docker compose up -d --build $service"
  DOCKER_BUILDKIT=1 COMPOSE_DOCKER_CLI_BUILD=1 docker compose -f "$compose_file" up -d --build "$service"
done

echo "[remote] compose status"
docker compose -f "$compose_file" ps
'@
$DeployScript = $DeployScript.
    Replace("__REMOTE_BASE__", $RemoteBaseQuoted).
    Replace("__REMOTE_ARCHIVE__", $RemoteArchiveQuoted).
    Replace("__COMPOSE_FILE__", $ComposeFileQuoted).
    Replace("__SERVICES__", $ServiceArray)

try {
    Invoke-RemoteBash -StepName "Prepare remote deployment directories and cleanup policy" -Script $CleanupScript

    if ($UseRsync) {
        Sync-SourceFoldersWithRsync
    } else {
        $TarArgs = @("-czf", $ArchivePath)
        foreach ($Exclude in $ArchiveExcludes) {
            if (-not [string]::IsNullOrWhiteSpace($Exclude)) {
                $TarArgs += "--exclude=$Exclude"
            }
        }
        $TarArgs += @("-C", $ProjectRoot)
        $TarArgs += $SourceFolders

        Invoke-NativeCommand -FilePath "tar" -Arguments $TarArgs -StepName "Create source archive"
        Invoke-NativeCommand `
            -FilePath "scp" `
            -Arguments ($ScpArgs + @($ArchivePath, "${RemoteTarget}:$RemoteArchivePath")) `
            -StepName "Upload source archive"
    }
    Invoke-RemoteBash -StepName "Build and restart compose services" -Script $DeployScript

    Write-Host "==> Remote update completed."
}
finally {
    if (Test-Path -LiteralPath $ArchivePath) {
        Remove-Item -LiteralPath $ArchivePath -Force
    }
}
