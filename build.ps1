# Builds Flashback Skin Timeline on Windows without installing anything.
# A temporary Java 25 is downloaded into .build-temp\ and deleted when the build finishes.
# Run it with build.bat (double-click).

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # makes downloads much faster in Windows PowerShell
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$McVersion     = '26.3'
$LoaderVersion = '0.20.1'
$ModVersion    = '1.1.0+mc26.3'
$FlashbackFile = 'Flashback-0.43.6-for-MC26.3.jar'
$FlashbackUrl  = 'https://cdn.modrinth.com/data/4das1Fjq/versions/cV1pa0YV/Flashback-0.43.6-for-MC26.3.jar'
$FlashbackSha1 = '4FD7D2D677332F58B00107B27615379EADC6CFCB'

$Root = $PSScriptRoot
$Work = Join-Path $Root '.build-temp'
$Deps = Join-Path $Work 'deps'
$Out  = Join-Path $Work 'classes'

function Step($text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Download($url, $file) { Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing }
# javac argument files treat backslashes as escapes, so use forward slashes and quotes
function ArgPath($path) { '"' + ($path -replace '\\', '/') + '"' }

try {
    if (Test-Path $Work) { Remove-Item $Work -Recurse -Force }
    New-Item -ItemType Directory -Force -Path (Join-Path $Deps 'libs'), $Out | Out-Null

    # ---- Temporary Java 25 ----------------------------------------------------------------------
    Step 'Downloading a temporary Java 25 (about 200 MB, deleted afterwards)'
    $jdkZip = Join-Path $Work 'jdk.zip'
    Download 'https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse' $jdkZip
    Write-Host 'Unpacking Java...'
    $jdkDir = Join-Path $Work 'jdk'
    New-Item -ItemType Directory -Force -Path $jdkDir | Out-Null
    if (Get-Command tar.exe -ErrorAction SilentlyContinue) {
        & tar.exe -xf $jdkZip -C $jdkDir
        if ($LASTEXITCODE -ne 0) { throw 'Unpacking Java failed.' }
    } else {
        Expand-Archive -Path $jdkZip -DestinationPath $jdkDir -Force
    }
    $javac = Get-ChildItem -Path $jdkDir -Recurse -Filter 'javac.exe' | Select-Object -First 1
    if (-not $javac) { throw 'javac.exe not found in the downloaded Java.' }
    $jdkBin = $javac.DirectoryName
    $jarExe = Join-Path $jdkBin 'jar.exe'

    # ---- Minecraft 26.3 -------------------------------------------------------------------------
    Step "Downloading Minecraft $McVersion game files from Mojang"
    $manifest = Invoke-RestMethod -Uri 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json' -UseBasicParsing
    $entry = $manifest.versions | Where-Object { $_.id -eq $McVersion } | Select-Object -First 1
    if (-not $entry) { throw "Minecraft $McVersion not found in Mojang's version list." }
    $version = Invoke-RestMethod -Uri $entry.url -UseBasicParsing
    Download $version.downloads.client.url (Join-Path $Deps 'minecraft-client.jar')

    $urls = @($version.libraries | ForEach-Object { $_.downloads.artifact.url } | Where-Object { $_ } | Sort-Object -Unique)
    $i = 0
    foreach ($url in $urls) {
        $i++
        Write-Host "  library $i / $($urls.Count)"
        Download $url (Join-Path $Deps "libs\$i.jar")
    }

    # ---- Fabric Loader + Mixin -----------------------------------------------------------------
    Step 'Downloading Fabric Loader and Mixin'
    Download "https://maven.fabricmc.net/net/fabricmc/fabric-loader/$LoaderVersion/fabric-loader-$LoaderVersion.jar" (Join-Path $Deps 'fabric-loader.jar')
    $mixinMeta = Invoke-WebRequest -Uri 'https://maven.fabricmc.net/net/fabricmc/sponge-mixin/maven-metadata.xml' -UseBasicParsing
    $mixinVersion = ([xml]$mixinMeta.Content).metadata.versioning.release
    Download "https://maven.fabricmc.net/net/fabricmc/sponge-mixin/$mixinVersion/sponge-mixin-$mixinVersion.jar" (Join-Path $Deps 'sponge-mixin.jar')

    # ---- Flashback (compile only, not bundled) --------------------------------------------------
    Step 'Getting Flashback 0.43.6'
    $flashback = Join-Path $Deps $FlashbackFile
    $local = Get-ChildItem -Path (Join-Path $Root 'libs') -Filter 'Flashback*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($local) {
        Write-Host "Using $($local.Name) from the libs folder"
        Copy-Item $local.FullName $flashback
    } else {
        Download $FlashbackUrl $flashback
        if ((Get-FileHash -Path $flashback -Algorithm SHA1).Hash -ne $FlashbackSha1) { throw 'The downloaded Flashback jar is not the expected 0.43.6 file.' }
    }

    # ---- Compile ----------------------------------------------------------------------------------
    Step 'Compiling the add-on'
    $jars = Get-ChildItem -Path $Deps -Recurse -Filter '*.jar' | ForEach-Object { $_.FullName -replace '\\', '/' }
    $sources = Get-ChildItem -Path (Join-Path $Root 'src\main\java') -Recurse -Filter '*.java'
    $argLines = @(
        '--release', '25', '-proc:none', '-encoding', 'UTF-8', '-nowarn',
        '-d', (ArgPath $Out),
        '-cp', ('"' + ($jars -join ';') + '"')
    ) + ($sources | ForEach-Object { ArgPath $_.FullName })
    $argFile = Join-Path $Work 'javac-args.txt'
    [IO.File]::WriteAllLines($argFile, [string[]]$argLines)
    & (Join-Path $jdkBin 'javac.exe') "@$argFile"
    if ($LASTEXITCODE -ne 0) { throw 'Compiling failed. Copy the errors above and send them to Claude.' }

    # ---- Package ----------------------------------------------------------------------------------
    Step 'Packing the jar'
    Copy-Item -Path (Join-Path $Root 'src\main\resources\*') -Destination $Out -Recurse -Force
    $modJson = Join-Path $Out 'fabric.mod.json'
    [IO.File]::WriteAllText($modJson, [IO.File]::ReadAllText($modJson).Replace('${version}', $ModVersion))
    $result = Join-Path $Root "flashback-skin-timeline-$ModVersion.jar"
    if (Test-Path $result) { Remove-Item $result -Force }
    & $jarExe --create --file $result -C $Out .
    if ($LASTEXITCODE -ne 0) { throw 'Packing the jar failed.' }

    Step 'Cleaning up'
    Remove-Item $Work -Recurse -Force

    Write-Host ''
    Write-Host 'DONE!' -ForegroundColor Green
    Write-Host "Your add-on: $result" -ForegroundColor Green
    Write-Host 'Put it in your Minecraft 26.3 mods folder next to Flashback.'
}
catch {
    Write-Host ''
    Write-Host 'BUILD FAILED:' -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host 'Take a screenshot of this window and send it to Claude.'
    if (Test-Path $Work) { Remove-Item $Work -Recurse -Force -ErrorAction SilentlyContinue }
    exit 1
}
