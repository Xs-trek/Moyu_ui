param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$sourcePath = Join-Path $RepositoryRoot 'moyu_notify.png'
if (-not (Test-Path -LiteralPath $sourcePath)) {
    throw "Missing visual baseline: $sourcePath"
}

$source = [System.Drawing.Bitmap]::FromFile($sourcePath)

function Write-MaskPng {
    param(
        [string]$Path,
        [int]$Width,
        [int]$Height,
        [System.Drawing.Rectangle]$SourceRect,
        [System.Drawing.Rectangle]$DestinationRect,
        [int]$DilationRadius,
        [System.Drawing.Color]$Ink
    )

    $sample = New-Object System.Drawing.Bitmap $Width, $Height, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($sample)
    $graphics.Clear([System.Drawing.Color]::White)
    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $graphics.DrawImage($source, $DestinationRect, $SourceRect, [System.Drawing.GraphicsUnit]::Pixel)
    $graphics.Dispose()

    [int[]]$alpha = [int[]]::new($Width * $Height)
    for ($y = 0; $y -lt $Height; $y++) {
        for ($x = 0; $x -lt $Width; $x++) {
            $pixel = $sample.GetPixel($x, $y)
            $luminance = [int](0.2126 * $pixel.R + 0.7152 * $pixel.G + 0.0722 * $pixel.B)
            $alpha[$y * $Width + $x] = [Math]::Max(0, [Math]::Min(255, [int]((245 - $luminance) * 255 / 245)))
        }
    }
    $sample.Dispose()

    $output = New-Object System.Drawing.Bitmap $Width, $Height, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt $Height; $y++) {
        for ($x = 0; $x -lt $Width; $x++) {
            $maximum = 0
            for ($dy = -$DilationRadius; $dy -le $DilationRadius; $dy++) {
                $sy = $y + $dy
                if ($sy -lt 0 -or $sy -ge $Height) { continue }
                for ($dx = -$DilationRadius; $dx -le $DilationRadius; $dx++) {
                    $sx = $x + $dx
                    if ($sx -lt 0 -or $sx -ge $Width) { continue }
                    $maximum = [Math]::Max($maximum, $alpha[$sy * $Width + $sx])
                }
            }
            $output.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($maximum, $Ink.R, $Ink.G, $Ink.B))
        }
    }

    $directory = Split-Path -Parent $Path
    [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    $output.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $output.Dispose()
}

$densities = [ordered]@{ mdpi = 1.0; hdpi = 1.5; xhdpi = 2.0; xxhdpi = 3.0; xxxhdpi = 4.0 }
$fullSource = New-Object System.Drawing.Rectangle 0, 0, $source.Width, $source.Height
$notificationSource = New-Object System.Drawing.Rectangle 350, 570, 570, 340

foreach ($entry in $densities.GetEnumerator()) {
    $density = [double]$entry.Value
    $launcherSize = [int](108 * $density)
    $launcherArt = [int]($launcherSize * 0.68)
    $launcherOffset = [int](($launcherSize - $launcherArt) / 2)
    $launcherDestination = New-Object System.Drawing.Rectangle $launcherOffset, $launcherOffset, $launcherArt, $launcherArt
    $launcherPath = Join-Path $RepositoryRoot "android/app/src/main/res/drawable-$($entry.Key)/ic_launcher_foreground.png"
    Write-MaskPng -Path $launcherPath -Width $launcherSize -Height $launcherSize -SourceRect $fullSource -DestinationRect $launcherDestination -DilationRadius ([Math]::Max(1, [int][Math]::Round(0.45 * $density))) -Ink ([System.Drawing.Color]::FromArgb(17, 17, 17))

    $notificationSize = [int](24 * $density)
    $notificationWidth = [int](22 * $density)
    $notificationHeight = [int][Math]::Round($notificationWidth * $notificationSource.Height / $notificationSource.Width)
    $notificationDestination = New-Object System.Drawing.Rectangle ([int](($notificationSize - $notificationWidth) / 2)), ([int](($notificationSize - $notificationHeight) / 2)), $notificationWidth, $notificationHeight
    $notificationPath = Join-Path $RepositoryRoot "android/app/src/main/res/drawable-$($entry.Key)/ic_notification.png"
    Write-MaskPng -Path $notificationPath -Width $notificationSize -Height $notificationSize -SourceRect $notificationSource -DestinationRect $notificationDestination -DilationRadius ([Math]::Max(1, [int][Math]::Round(0.45 * $density))) -Ink ([System.Drawing.Color]::White
    )
}

$source.Dispose()
Write-Output 'Generated launcher foregrounds and notification alpha masks from moyu_notify.png.'
