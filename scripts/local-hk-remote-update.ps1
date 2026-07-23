<#
.SYNOPSIS
Build Docker images locally on Windows and deploy them to the Hong Kong server.

.DESCRIPTION
The remote host never compiles source code. This script builds Linux images with
Docker Desktop, exports and uploads them, loads them remotely, and recreates only
the selected Compose services with --no-build and --no-deps.

.EXAMPLE
.\scripts\local-hk-remote-update.ps1 -UpdateMode frontend

.EXAMPLE
.\scripts\local-hk-remote-update.ps1 both

.EXAMPLE
.\scripts\local-hk-remote-update.ps1 -Target root@149.104.22.152 -UpdateMode backend
#>

[CmdletBinding()]
param(
    [string]$Target,

    [string]$HostName = "149.104.22.152",

    [string]$User = "root",

    [string]$PrivateKeyPath = "C:\Users\Administrator\.ssh\lens",

    [int]$Port = 22,

    [string]$RemoteBasePath = "/root/term/ai-fusion-video",

    [string]$ComposeFile = "docker-compose.yml",

    [ValidateSet("both", "backend", "frontend")]
    [string]$UpdateMode = "both",

    [string]$Platform = "linux/amd64",

    [string]$BackendService = "backend",

    [string]$FrontendService = "frontend",

    [string]$BackendImage = "ai-fusion-video:local",

    [string]$FrontendImage = "ai-fusion-video-web:local",

    [string[]]$BackendBaseImages = @(
        "eclipse-temurin:21-jdk|eclipse-temurin:21-jre",
        "docker.m.daocloud.io/library/eclipse-temurin:21-jdk|docker.m.daocloud.io/library/eclipse-temurin:21-jre",
        "dockerproxy.net/library/eclipse-temurin:21-jdk|dockerproxy.net/library/eclipse-temurin:21-jre",
        "docker.1ms.run/library/eclipse-temurin:21-jdk|docker.1ms.run/library/eclipse-temurin:21-jre"
    ),

    [string]$FrontendApiBaseUrl = "",

    [string[]]$FrontendBaseImages = @(
        "node:20-bookworm-slim",
        "docker.m.daocloud.io/library/node:20-bookworm-slim",
        "dockerproxy.net/library/node:20-bookworm-slim",
        "docker.1ms.run/library/node:20-bookworm-slim"
    ),

    [string]$DockerPath,

    [string]$DockerContext = "desktop-linux",

    [string]$DockerApiVersion = "",

    [ValidateSet("yes", "no", "ask", "accept-new")]
    [string]$StrictHostKeyChecking = "accept-new",

    [bool]$PruneDanglingImages = $false,

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
    return (($Values | ForEach-Object { ConvertTo-BashSingleQuoted $_ }) -join " ")
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

function Invoke-ScpWithRetry {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$StepName,
        [int]$Attempts = 3
    )

    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        Write-Host "==> $StepName (attempt $attempt/$Attempts)"
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & scp @Arguments 2>&1 | ForEach-Object { Write-Host $_ }
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        if ($exitCode -eq 0) { return }
        if ($attempt -lt $Attempts) {
            Write-Host "==> SCP failed with exit code $exitCode; retrying after 5 seconds..."
            Start-Sleep -Seconds 5
        }
    }
    throw "$StepName failed with exit code $exitCode. Check SSH connectivity, remote disk space, key permissions, and whether the server closed the connection during the large upload."
}

function Invoke-RemoteBash {
    param(
        [Parameter(Mandatory = $true)][string]$StepName,
        [Parameter(Mandatory = $true)][string]$Script
    )

    Write-Host "==> $StepName"
    $scriptId = [System.Guid]::NewGuid().ToString("N")
    $localScriptPath = Join-Path ([System.IO.Path]::GetTempPath()) "fusion-local-deploy-$scriptId.sh"
    $remoteScriptPath = "/tmp/fusion-local-deploy-$scriptId.sh"
    $remoteScriptQuoted = ConvertTo-BashSingleQuoted $remoteScriptPath

    try {
        $normalizedScript = $Script -replace "`r`n", "`n" -replace "`r", "`n"
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($localScriptPath, $normalizedScript, $utf8NoBom)

        Invoke-NativeCommand `
            -FilePath "scp" `
            -Arguments ($script:ScpArgs + @($localScriptPath, "${script:RemoteTarget}:$remoteScriptPath")) `
            -StepName "Upload remote deployment step"

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

$knownUpdateModes = @("both", "backend", "frontend")
$targetWasNamed = $MyInvocation.Line -match '(?i)(^|\s)-Target(\s|:)'
if (-not $targetWasNamed -and -not [string]::IsNullOrWhiteSpace($Target)) {
    $positionalMode = $Target.Trim().ToLowerInvariant()
    if ($knownUpdateModes -contains $positionalMode) {
        if ($PSBoundParameters.ContainsKey("UpdateMode") -and $UpdateMode -ne $positionalMode) {
            throw "Conflicting update modes: positional '$positionalMode' and -UpdateMode '$UpdateMode'."
        }
        $UpdateMode = $positionalMode
        $Target = $null
    }
}

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

Assert-Command "ssh"
Assert-Command "scp"

if ([string]::IsNullOrWhiteSpace($DockerPath)) {
    $dockerCommandPath = Get-Command "docker" -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty Source
    if (-not [string]::IsNullOrWhiteSpace($dockerCommandPath) -and
        (Test-Path -LiteralPath $dockerCommandPath -PathType Leaf)) {
        $DockerPath = $dockerCommandPath
    }

    if ([string]::IsNullOrWhiteSpace($DockerPath)) {
        $dockerCandidates = @(
            "C:\Program Files\Docker\Docker\resources\bin\docker.exe",
            "C:\ProgramData\DockerDesktop\version-bin\docker.exe"
        )
        $DockerPath = $dockerCandidates |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
            Select-Object -First 1
    }
}

if ([string]::IsNullOrWhiteSpace($DockerPath)) {
    throw "Docker CLI was not found. Install Docker Desktop or pass -DockerPath C:\path\to\docker.exe."
}
$DockerPath = Resolve-RequiredPath -Path $DockerPath -Label "Docker CLI"
Write-Host "==> Docker CLI: $DockerPath"

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}

$ProjectRoot = Resolve-RequiredPath -Path $ProjectRoot -Label "Project root"
$PrivateKeyPath = Resolve-RequiredPath -Path $PrivateKeyPath -Label "Private key"
$ComposePath = Resolve-RequiredPath -Path (Join-Path $ProjectRoot $ComposeFile) -Label "Compose file"
$BackendContext = Join-Path $ProjectRoot "ai-fusion-video"
$FrontendContext = Join-Path $ProjectRoot "ai-fusion-video-web"

$UpdateBackend = $UpdateMode -eq "both" -or $UpdateMode -eq "backend"
$UpdateFrontend = $UpdateMode -eq "both" -or $UpdateMode -eq "frontend"
$Images = @()
$Services = @()

if ($UpdateBackend) {
    Resolve-RequiredPath -Path $BackendContext -Label "Backend build context" | Out-Null
    $Images += $BackendImage
    $Services += $BackendService
}
if ($UpdateFrontend) {
    Resolve-RequiredPath -Path $FrontendContext -Label "Frontend build context" | Out-Null
    $Images += $FrontendImage
    $Services += $FrontendService
}

$RemoteBasePath = $RemoteBasePath.TrimEnd("/")
$RemoteTarget = "$User@$HostName"
$Timestamp = Get-Date -Format "yyyyMMddHHmmss"
$ProcessId = [System.Diagnostics.Process]::GetCurrentProcess().Id
$BundleName = "ai-fusion-video-$UpdateMode-images-$Timestamp-$ProcessId.tar"
$BundlePath = Join-Path ([System.IO.Path]::GetTempPath()) $BundleName
$RemoteBundlePath = "/tmp/$BundleName"
$RemoteComposePath = "/tmp/ai-fusion-video-compose-$Timestamp-$ProcessId.yml"

$SshArgs = @(
    "-T",
    "-i", $PrivateKeyPath,
    "-p", [string]$Port,
    "-o", "IdentitiesOnly=yes",
    "-o", "BatchMode=yes",
    "-o", "StrictHostKeyChecking=$StrictHostKeyChecking"
    ,"-o", "ServerAliveInterval=30"
    ,"-o", "ServerAliveCountMax=6"
)
$ScpArgs = @(
    "-i", $PrivateKeyPath,
    "-P", [string]$Port,
    "-o", "IdentitiesOnly=yes",
    "-o", "BatchMode=yes",
    "-o", "StrictHostKeyChecking=$StrictHostKeyChecking"
    ,"-o", "ServerAliveInterval=30"
    ,"-o", "ServerAliveCountMax=6"
)
if ($StrictHostKeyChecking -eq "no") {
    $SshArgs += @("-o", "UserKnownHostsFile=/dev/null")
    $ScpArgs += @("-o", "UserKnownHostsFile=/dev/null")
}

$RemoteBaseQuoted = ConvertTo-BashSingleQuoted $RemoteBasePath
$RemoteBundleQuoted = ConvertTo-BashSingleQuoted $RemoteBundlePath
$RemoteComposeQuoted = ConvertTo-BashSingleQuoted $RemoteComposePath
$ComposeFileQuoted = ConvertTo-BashSingleQuoted $ComposeFile
$ServiceArray = New-BashArrayLiteral $Services
$PruneFlag = if ($PruneDanglingImages) { "1" } else { "0" }
$RemoteArtifactsMayExist = $false
$OriginalDockerApiVersion = $env:DOCKER_API_VERSION

try {
    Write-Host "==> Check Docker Desktop context: $DockerContext"
    $apiCandidates = if ([string]::IsNullOrWhiteSpace($DockerApiVersion)) {
        @("", "1.54", "1.52", "1.51", "1.47", "1.44")
    }
    else {
        @($DockerApiVersion)
    }
    $dockerEngineReady = $false
    $lastDockerInfoOutput = @()

    foreach ($apiCandidate in $apiCandidates) {
        if ([string]::IsNullOrWhiteSpace($apiCandidate)) {
            Remove-Item Env:\DOCKER_API_VERSION -ErrorAction SilentlyContinue
            $apiLabel = "automatic"
        }
        else {
            $env:DOCKER_API_VERSION = $apiCandidate
            $apiLabel = $apiCandidate
        }

        Write-Host "==> Try Docker API version: $apiLabel"
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $lastDockerInfoOutput = @(& $DockerPath --context $DockerContext info 2>&1)
            $dockerInfoExitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }

        if ($dockerInfoExitCode -eq 0) {
            $dockerEngineReady = $true
            $lastDockerInfoOutput | ForEach-Object { Write-Host $_ }
            if (-not [string]::IsNullOrWhiteSpace($apiCandidate)) {
                Write-Host "==> Using compatible Docker API version: $apiCandidate"
            }
            break
        }
    }

    if (-not $dockerEngineReady) {
        if ([string]::IsNullOrWhiteSpace($OriginalDockerApiVersion)) {
            Remove-Item Env:\DOCKER_API_VERSION -ErrorAction SilentlyContinue
        }
        else {
            $env:DOCKER_API_VERSION = $OriginalDockerApiVersion
        }
        $lastDockerInfoOutput | ForEach-Object { Write-Host $_ }
        Write-Host "==> Available Docker contexts"
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $DockerPath context ls 2>&1 | ForEach-Object { Write-Host $_ }
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        throw "Cannot connect to Docker Desktop Linux engine using context '$DockerContext', including compatible API versions. Restart Docker Desktop; if needed run wsl --shutdown first."
    }

    if ($UpdateBackend) {
        $backendBuilt = $false
        foreach ($basePair in $BackendBaseImages) {
            $parts = $basePair -split '\|', 2
            if ($parts.Count -ne 2) { continue }
            $jdkImage = $parts[0].Trim(); $jreImage = $parts[1].Trim()
            Write-Host "==> Build backend with base images: $jdkImage / $jreImage"
            $buildArgs = @("--context", $DockerContext, "build", "--platform", $Platform,
                "--build-arg", "BASE_JDK_IMAGE=$jdkImage", "--build-arg", "BASE_JRE_IMAGE=$jreImage",
                "--tag", $BackendImage, $BackendContext)
            $previousErrorActionPreference = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            try { & $DockerPath @buildArgs; $buildExitCode = $LASTEXITCODE }
            finally { $ErrorActionPreference = $previousErrorActionPreference }
            if ($buildExitCode -eq 0) { $backendBuilt = $true; break }
            Write-Host "==> Backend base image source unavailable, trying the next source."
        }
        if (-not $backendBuilt) {
            throw "Unable to build backend image: all configured JDK/JRE base image sources failed. Check Docker Desktop proxy/DNS settings or pass -BackendBaseImages."
        }
    }

    if ($UpdateFrontend) {
        $SelectedFrontendBaseImage = $null
        foreach ($baseImage in $FrontendBaseImages) {
            if ([string]::IsNullOrWhiteSpace($baseImage)) {
                continue
            }
            Write-Host "==> Pull frontend base image: $baseImage"
            $previousErrorActionPreference = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            try {
                & $DockerPath --context $DockerContext pull $baseImage
                $pullExitCode = $LASTEXITCODE
            }
            finally {
                $ErrorActionPreference = $previousErrorActionPreference
            }
            if ($pullExitCode -eq 0) {
                $SelectedFrontendBaseImage = $baseImage
                break
            }
            Write-Host "==> Base image source unavailable, trying the next source."
        }

        if ([string]::IsNullOrWhiteSpace($SelectedFrontendBaseImage)) {
            throw "Unable to pull the Node base image from Docker Hub or configured mirrors. Check Docker Desktop proxy/DNS settings or pass -FrontendBaseImages with an accessible registry."
        }

        Write-Host "==> Frontend base image selected: $SelectedFrontendBaseImage"
        Invoke-NativeCommand `
            -FilePath $DockerPath `
            -Arguments @(
                "--context", $DockerContext,
                "build", "--platform", $Platform,
                "--build-arg", "NODE_IMAGE=$SelectedFrontendBaseImage",
                "--build-arg", "NEXT_PUBLIC_API_BASE_URL=$FrontendApiBaseUrl",
                "--tag", $FrontendImage,
                $FrontendContext
            ) `
            -StepName "Build frontend image locally ($Platform)"
    }

    foreach ($Image in $Images) {
        Invoke-NativeCommand `
            -FilePath $DockerPath `
            -Arguments @("--context", $DockerContext, "image", "inspect", $Image) `
            -StepName "Verify local image $Image"
    }

    Invoke-NativeCommand `
        -FilePath $DockerPath `
        -Arguments (@("--context", $DockerContext, "save", "--output", $BundlePath) + $Images) `
        -StepName "Export local Docker image bundle"

    $BundleSizeBytes = (Get-Item -LiteralPath $BundlePath).Length
    $RequiredRemoteKb = [math]::Ceiling(($BundleSizeBytes * 2.2) / 1KB)
    Write-Host ("==> Image bundle size: {0:N2} GB" -f ($BundleSizeBytes / 1GB))

    $PreflightScript = @'
set -Eeuo pipefail
required_kb=__REQUIRED_KB__
available_kb=$(df -Pk /tmp | awk 'NR==2 {print $4}')
echo "[remote] /tmp available: ${available_kb} KB; required: ${required_kb} KB"
if [ "$available_kb" -lt "$required_kb" ]; then
  echo "[remote] insufficient disk space for image upload and docker load" >&2
  exit 1
fi
command -v docker >/dev/null
docker compose version
mkdir -p __REMOTE_BASE__
'@
    $PreflightScript = $PreflightScript.
        Replace("__REQUIRED_KB__", [string]$RequiredRemoteKb).
        Replace("__REMOTE_BASE__", $RemoteBaseQuoted)
    Invoke-RemoteBash -StepName "Check remote Docker and disk space" -Script $PreflightScript

    Invoke-ScpWithRetry `
        -Arguments ($ScpArgs + @("-C", $BundlePath, "${RemoteTarget}:$RemoteBundlePath")) `
        -StepName "Upload Docker image bundle"
    $RemoteArtifactsMayExist = $true

    Invoke-ScpWithRetry `
        -Arguments ($ScpArgs + @($ComposePath, "${RemoteTarget}:$RemoteComposePath")) `
        -StepName "Upload Docker Compose file"

    $DeployScript = @'
set -Eeuo pipefail

remote_base=__REMOTE_BASE__
bundle=__REMOTE_BUNDLE__
uploaded_compose=__REMOTE_COMPOSE__
compose_file=__COMPOSE_FILE__
services=(__SERVICES__)
prune_dangling=__PRUNE_DANGLING__

cleanup() {
  rm -f "$bundle" "$uploaded_compose"
}
trap cleanup EXIT

mkdir -p "$remote_base"
install -m 600 "$uploaded_compose" "$remote_base/$compose_file"

echo "[remote] load locally built images"
docker load --input "$bundle"

cd "$remote_base"
for service in "${services[@]}"; do
  echo "[remote] recreate $service without building or starting dependencies"
  docker compose -f "$compose_file" up -d --no-build --no-deps --force-recreate "$service"
done

sleep 3
failed=0
for service in "${services[@]}"; do
  container_id=$(docker compose -f "$compose_file" ps -q "$service")
  if [ -z "$container_id" ]; then
    echo "[remote] no container found for service $service" >&2
    failed=1
    continue
  fi
  running=$(docker inspect -f '{{.State.Running}}' "$container_id")
  echo "[remote] service $service running=$running"
  if [ "$running" != "true" ]; then
    docker logs --tail 120 "$container_id" >&2 || true
    failed=1
  fi
done

docker compose -f "$compose_file" ps

if [ "$prune_dangling" = "1" ]; then
  docker image prune -f
fi

if [ "$failed" -ne 0 ]; then
  exit 1
fi
'@
    $DeployScript = $DeployScript.
        Replace("__REMOTE_BASE__", $RemoteBaseQuoted).
        Replace("__REMOTE_BUNDLE__", $RemoteBundleQuoted).
        Replace("__REMOTE_COMPOSE__", $RemoteComposeQuoted).
        Replace("__COMPOSE_FILE__", $ComposeFileQuoted).
        Replace("__SERVICES__", $ServiceArray).
        Replace("__PRUNE_DANGLING__", $PruneFlag)

    Invoke-RemoteBash -StepName "Load images and restart remote services" -Script $DeployScript
    Write-Host "==> Local-build remote update completed. No source compilation ran on the server."
}
finally {
    if ([string]::IsNullOrWhiteSpace($OriginalDockerApiVersion)) {
        Remove-Item Env:\DOCKER_API_VERSION -ErrorAction SilentlyContinue
    }
    else {
        $env:DOCKER_API_VERSION = $OriginalDockerApiVersion
    }
    if (Test-Path -LiteralPath $BundlePath) {
        Remove-Item -LiteralPath $BundlePath -Force
    }
    if ($RemoteArtifactsMayExist) {
        $cleanupCommand = "rm -f $RemoteBundleQuoted $RemoteComposeQuoted"
        & ssh @SshArgs $RemoteTarget $cleanupCommand 2>$null
    }
}
