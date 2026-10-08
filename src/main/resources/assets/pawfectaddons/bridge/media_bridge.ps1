param(
    [string]$WorkDir = $env:TEMP,
    [int]$IntervalMs = 250,
    [int]$ParentPid = 0
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Add-Type -AssemblyName System.Runtime.WindowsRuntime

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and
    $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($operation, $resultType, [int]$timeoutMs = 4000) {
    $task = $asTaskGeneric.MakeGenericMethod($resultType).Invoke($null, @($operation))
    if (-not $task.Wait($timeoutMs)) { return $null }
    return $task.Result
}

$managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media, ContentType = WindowsRuntime]
$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
$boolType = [bool]

$manager = Await ($managerType::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
if ($null -eq $manager) {
    Write-Output '{"state":"error","message":"no manager"}'
    exit 1
}

$commandFile = Join-Path $WorkDir 'pawfect_media_command.txt'
# Only one bridge serves a folder: the newest writes its id here, and any older one that sees
# someone else's id stops, as does a bridge whose game has closed. A bridge left behind by a
# crashed game would otherwise fight the new one for commands and artwork.
$ownerFile = Join-Path $WorkDir 'pawfect_media_bridge.pid'
[System.IO.File]::WriteAllText($ownerFile, [string]$PID)
$thumbSlots = @((Join-Path $WorkDir 'pawfect_media_thumb_a.img'), (Join-Path $WorkDir 'pawfect_media_thumb_b.img'))
$thumbSource = Join-Path $WorkDir 'MediaThumb.cs'
$thumbDll = Join-Path $WorkDir 'PawfectMediaThumb3.dll'
$thumbReady = $false
$thumbState = 'not attempted'

if (Test-Path $thumbSource) {
    try {
        $stale = $true
        if (Test-Path $thumbDll) {
            $stale = (Get-Item $thumbSource).LastWriteTimeUtc -gt (Get-Item $thumbDll).LastWriteTimeUtc
            if ($stale) { try { Remove-Item $thumbDll -Force } catch { $stale = $false } }
        }
        if ($stale) {
            $net = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319'
            $csc = Join-Path $net 'csc.exe'
            $meta = Join-Path $env:WINDIR 'System32\WinMetadata'
            if (Test-Path $csc) {
                $arguments = @(
                    '-nologo', '-target:library', ('-out:' + $thumbDll),
                    ('-reference:' + (Join-Path $meta 'Windows.Media.winmd')),
                    ('-reference:' + (Join-Path $meta 'Windows.Storage.winmd')),
                    ('-reference:' + (Join-Path $meta 'Windows.Foundation.winmd')),
                    ('-reference:' + (Join-Path $net 'System.Runtime.dll')),
                    $thumbSource
                )
                & $csc @arguments | Out-Null
            }
        }
        if (Test-Path $thumbDll) {
            Add-Type -Path $thumbDll
            $thumbReady = $true
        }
    } catch {
        $thumbReady = $false
    }
}

function Now-Ms { return [long]([DateTime]::UtcNow.Ticks / 10000) }

function Still-Wanted {
    try {
        if ([System.IO.File]::ReadAllText($ownerFile).Trim() -ne [string]$PID) { return $false }
    } catch { }
    if ($ParentPid -gt 0) {
        try {
            if ([System.Diagnostics.Process]::GetProcessById($ParentPid).HasExited) { return $false }
        } catch { return $false }
    }
    return $true
}

function Invoke-Command-File($session) {
    if (-not (Test-Path $commandFile)) { return }
    $command = ''
    try {
        $command = [System.IO.File]::ReadAllText($commandFile).Trim()
        [System.IO.File]::Delete($commandFile)
    } catch {
        return
    }
    if ($command -eq '' -or $null -eq $session) { return }
    # Sent and left to run: waiting on the reply held up every update for seconds.
    try {
        switch ($command.ToUpperInvariant()) {
            'PLAY'   { $null = $session.TryPlayAsync() }
            'PAUSE'  { $null = $session.TryPauseAsync() }
            'TOGGLE' { $null = $session.TryTogglePlayPauseAsync() }
            'NEXT'   { $null = $session.TrySkipNextAsync() }
            'PREV'   { $null = $session.TrySkipPreviousAsync() }
        }
    } catch { }
}

function Test-Background($session) {
    try {
        if (-not $session.SourceAppUserModelId.EndsWith('.exe', [StringComparison]::OrdinalIgnoreCase)) { return $false }
        $props = Await ($session.TryGetMediaPropertiesAsync()) $propsType 1000
        return ($null -eq $props) -or [string]::IsNullOrWhiteSpace($props.Artist)
    } catch { return $false }
}

$chosen = $null
function Pick-Session {
    $sessions = @($manager.GetSessions())
    if ($sessions.Count -eq 0) { return $null }

    $playing = @($sessions | Where-Object {
        try { $_.GetPlaybackInfo().PlaybackStatus.ToString() -eq 'Playing' } catch { $false }
    })
    # An app's own video, like a clip preview in a WebView2 window, shows up as a session from a
    # bare .exe with no artist. Real players come first; those only when nothing else plays.
    if ($playing.Count -gt 1) {
        $real = @($playing | Where-Object { -not (Test-Background $_) })
        if ($real.Count -gt 0) { $playing = $real }
    }
    $stillThere = $null
    if ($null -ne $script:chosen) {
        $stillThere = $sessions | Where-Object { [object]::ReferenceEquals($_, $script:chosen) } | Select-Object -First 1
    }

    if ($playing.Count -gt 0) {
        if ($null -ne $stillThere -and ($playing | Where-Object { [object]::ReferenceEquals($_, $stillThere) })) { return $stillThere }
        $current = $manager.GetCurrentSession()
        if ($null -ne $current -and ($playing | Where-Object { [object]::ReferenceEquals($_, $current) })) { return $current }
        return $playing[0]
    }
    if ($null -ne $stillThere) { return $stillThere }
    $current = $manager.GetCurrentSession()
    if ($null -ne $current) { return $current }
    return $sessions[0]
}

$slot = 0
$lastHash = ''
$artKey = ''
$trackKey = ''
$trackChangedAt = 0
$nextThumbCheck = 0
$pendingTarget = ''
$pendingKey = ''
$lastProps = $null
$lastPropsApp = ''
$nextOwnerCheck = 0

while ($true) {
    if ((Now-Ms) -ge $nextOwnerCheck) {
        if (-not (Still-Wanted)) { exit 0 }
        $nextOwnerCheck = (Now-Ms) + 2000
    }
    try {
        $session = Pick-Session
        $chosen = $session
        Invoke-Command-File $session

        if ($null -eq $session) {
            Write-Output '{"state":"none"}'
        } else {
            $properties = Await ($session.TryGetMediaPropertiesAsync()) $propsType 1500
            $timeline = $session.GetTimelineProperties()
            $playback = $session.GetPlaybackInfo()
            $app = $session.SourceAppUserModelId

            # A player that is slow to answer, often right after a skip, keeps its last details
            # instead of blanking the card.
            if ($null -eq $properties -and $lastPropsApp -eq $app) {
                $properties = $lastProps
            } elseif ($null -ne $properties) {
                $lastProps = $properties
                $lastPropsApp = $app
            }

            $title = ''
            $artist = ''
            $album = ''
            if ($null -ne $properties) {
                $title = $properties.Title
                $artist = $properties.Artist
                $album = $properties.AlbumTitle
            }

            $now = Now-Ms
            $key = "$app|$title|$artist|$album"
            if ($key -ne $trackKey) {
                $trackKey = $key
                $trackChangedAt = $now
                $nextThumbCheck = $now
            }

            $thumbChanged = $false
            $thumbPath = ''
            # The artwork is fetched on its own thread and picked up on a later pass, so a slow
            # player never holds the card up.
            if ($thumbReady) {
                $saved = $null
                try { $saved = [PawfectMedia]::PollThumb() } catch { }
                if ($null -ne $saved -and $pendingKey -eq $key) {
                    $thumbState = $saved
                    if ($saved -like 'ok:*') {
                        $hash = $saved.Substring(3)
                        if ($hash -ne $lastHash) {
                            $lastHash = $hash
                            $slot = 1 - $slot
                            $thumbChanged = $true
                            $thumbPath = $pendingTarget
                        }
                        $artKey = $key
                    } elseif ($artKey -ne $key -and ($now - $trackChangedAt) -gt 2500) {
                        $lastHash = ''
                        $artKey = $key
                        $thumbChanged = $true
                    }
                }
                if ($now -ge $nextThumbCheck -and -not [PawfectMedia]::Busy) {
                    $pendingTarget = $thumbSlots[1 - $slot]
                    $pendingKey = $key
                    try {
                        [PawfectMedia]::StartThumb($pendingTarget, $app, $title) | Out-Null
                    } catch {
                        $thumbState = 'error: ' + $_.Exception.Message
                    }
                    $sinceChange = $now - $trackChangedAt
                    $nextThumbCheck = $now + $(if ($sinceChange -lt 4000) { 700 } else { 3000 })
                }
            }

            # The position is as of LastUpdatedTime, which a browser can leave many seconds old;
            # the game works out the live position from it.
            $updated = 0
            try { $updated = $timeline.LastUpdatedTime.ToUnixTimeMilliseconds() } catch { }

            $controls = $playback.Controls
            $payload = [PSCustomObject]@{
                state     = 'ok'
                title     = $title
                artist    = $artist
                album     = $album
                app       = $app
                status    = $playback.PlaybackStatus.ToString()
                position  = [math]::Round($timeline.Position.TotalSeconds, 2)
                duration  = [math]::Round($timeline.EndTime.TotalSeconds, 2)
                updated   = $updated
                thumb     = $thumbChanged
                thumbPath = $thumbPath
                thumbState = $thumbState
                thumbReady = $thumbReady
                canPlay   = [bool]$controls.IsPlayEnabled
                canPause  = [bool]$controls.IsPauseEnabled
                canNext   = [bool]$controls.IsNextEnabled
                canPrev   = [bool]$controls.IsPreviousEnabled
            }
            Write-Output ($payload | ConvertTo-Json -Compress)
        }
    } catch {
        Write-Output '{"state":"none"}'
    }

    [Console]::Out.Flush()
    Start-Sleep -Milliseconds $IntervalMs
}
