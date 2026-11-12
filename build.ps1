# Builds GyroCursor.apk with the plain Android SDK tools - no Gradle.
#   resources (aapt2) -> Java (javac) -> dex (d8) -> zip align -> sign (apksigner)
#
# Usage:  powershell -ExecutionPolicy Bypass -File build.ps1
#         powershell -ExecutionPolicy Bypass -File build.ps1 -Install   # also adb install to a USB phone
param([switch]$Install)

$root = $PSScriptRoot

# --- find the SDK and a JDK ---------------------------------------------------
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
    Where-Object { $_ -and (Test-Path "$_\platforms") } | Select-Object -First 1
if (-not $sdk) { throw "Android SDK not found (set ANDROID_HOME)." }

$jdk = @($env:JAVA_HOME, "$env:LOCALAPPDATA\Android\jdk17") |
    Where-Object { $_ -and (Test-Path "$_\bin\javac.exe") } | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk; $javac = "$jdk\bin\javac.exe"; $keytool = "$jdk\bin\keytool.exe" }
else      { $javac = "javac"; $keytool = "keytool" }

$platform = Get-ChildItem "$sdk\platforms" -Directory |
    Sort-Object { [int]($_.Name -replace '\D', '') } | Select-Object -Last 1
$buildTools = Get-ChildItem "$sdk\build-tools" -Directory |
    Sort-Object { [version]($_.Name -replace '-.*', '') } | Select-Object -Last 1
$androidJar = "$($platform.FullName)\android.jar"
$bt = $buildTools.FullName
Write-Host "SDK: $sdk  platform: $($platform.Name)  build-tools: $($buildTools.Name)"

function Exec([string]$step, [scriptblock]$cmd) {
    Write-Host ">> $step"
    & $cmd
    if ($LASTEXITCODE -ne 0) { throw "$step failed (exit $LASTEXITCODE)" }
}

# --- clean ------------------------------------------------------------------
$build = "$root\build"
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force "$build\gen", "$build\classes", "$build\dex" | Out-Null

# --- 1. resources + manifest -> unsigned apk (+ R.java) --------------------
Exec "aapt2 compile" { & "$bt\aapt2.exe" compile --dir "$root\res" -o "$build\res.zip" }
Exec "aapt2 link" {
    & "$bt\aapt2.exe" link -I $androidJar --manifest "$root\AndroidManifest.xml" `
        --java "$build\gen" -o "$build\app.unsigned.apk" "$build\res.zip"
}

# --- 2. Java -> .class --------------------------------------------------------
$sources = Get-ChildItem "$root\src", "$build\gen" -Recurse -Filter *.java | ForEach-Object FullName
Exec "javac" {
    & $javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 `
        -bootclasspath $androidJar -d "$build\classes" $sources
}

# --- 3. .class -> classes.dex -------------------------------------------------
$classes = Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object FullName
Exec "d8" { & "$bt\d8.bat" --release --min-api 26 --lib $androidJar --output "$build\dex" $classes }

# --- 4. put classes.dex into the apk ------------------------------------------
Push-Location "$build\dex"
try { Exec "add dex" { & "$bt\aapt.exe" add "$build\app.unsigned.apk" classes.dex | Out-Null } }
finally { Pop-Location }

# --- 5. align + sign ------------------------------------------------------------
Exec "zipalign" { & "$bt\zipalign.exe" -f -p 4 "$build\app.unsigned.apk" "$build\app.aligned.apk" }

$keystore = "$root\debug.keystore"
if (-not (Test-Path $keystore)) {
    Exec "keytool (new debug key)" {
        & $keytool -genkeypair -keystore $keystore -storepass android -keypass android `
            -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
            -dname "CN=Android Debug,O=Android,C=US" 2>&1 | Out-Null
    }
}
$apk = "$root\GyroCursor.apk"
Exec "apksigner" {
    & "$bt\apksigner.bat" sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
        --out $apk "$build\app.aligned.apk"
}
Remove-Item "$apk.idsig" -ErrorAction SilentlyContinue
Write-Host "Built: $apk ($([math]::Round((Get-Item $apk).Length / 1KB)) KB)"

# --- optional: install on a phone connected over USB ---------------------------
if ($Install) {
    Exec "adb install" { & "$sdk\platform-tools\adb.exe" install -r $apk }
}
