param(
    [string]$WorkDir = $env:TEMP,
    [int]$IntervalMs = 250
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

function Await($operation, $resultType) {
    $task = $asTaskGeneric.MakeGenericMethod($resultType).Invoke($null, @($operation))
    if (-not $task.Wait(4000)) { return $null }
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
$thumbFile = Join-Path $WorkDir 'pawfect_media_thumb.img'
$thumbSource = Join-Path $WorkDir 'MediaThumb.cs'
$thumbDll = Join-Path $WorkDir 'PawfectMediaThumb.dll'
$lastThumbKey = ''
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
    try {
        switch ($command.ToUpperInvariant()) {
            'PLAY'   { Await ($session.TryPlayAsync()) $boolType | Out-Null }
            'PAUSE'  { Await ($session.TryPauseAsync()) $boolType | Out-Null }
            'TOGGLE' { Await ($session.TryTogglePlayPauseAsync()) $boolType | Out-Null }
            'NEXT'   { Await ($session.TrySkipNextAsync()) $boolType | Out-Null }
            'PREV'   { Await ($session.TrySkipPreviousAsync()) $boolType | Out-Null }
        }
    } catch { }
}

while ($true) {
    try {
        $session = $manager.GetCurrentSession()
        Invoke-Command-File $session

        if ($null -eq $session) {
            Write-Output '{"state":"none"}'
        } else {
            $properties = Await ($session.TryGetMediaPropertiesAsync()) $propsType
            $timeline = $session.GetTimelineProperties()
            $playback = $session.GetPlaybackInfo()

            $title = ''
            $artist = ''
            $album = ''
            if ($null -ne $properties) {
                $title = $properties.Title
                $artist = $properties.Artist
                $album = $properties.AlbumTitle
            }

            $key = "$title|$artist|$album"
            $newThumb = $false
            if ($thumbReady -and ($key -ne $lastThumbKey -or -not (Test-Path $thumbFile))) {
                $lastThumbKey = $key
                try {
                    $saved = [PawfectMedia]::SaveThumb($thumbFile)
                    $thumbState = $saved
                    if ($saved -like 'ok:*') { $newThumb = $true }
                } catch {
                    $thumbState = 'error: ' + $_.Exception.Message
                }
            }

            $controls = $playback.Controls
            $payload = [PSCustomObject]@{
                state     = 'ok'
                title     = $title
                artist    = $artist
                album     = $album
                app       = $session.SourceAppUserModelId
                status    = $playback.PlaybackStatus.ToString()
                position  = [math]::Round($timeline.Position.TotalSeconds, 2)
                duration  = [math]::Round($timeline.EndTime.TotalSeconds, 2)
                thumb     = $newThumb
                thumbPath = $thumbFile
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
