# shader-tuning/build-pack.ps1
# Packages the work-copy GLSL tree into a "-tuned" pack in the live shaderpacks
# dir for A/B testing against the untouched stock zip (stock stays selectable).
# Usage:  .\build-pack.ps1                 -> ComplementaryUnbound_r5.7.1-tuned.zip
#         .\build-pack.ps1 -Name foo       -> foo.zip
#
# NOTE: builds entries with forward-slash names via .NET ZipArchive. Do NOT use
# Compress-Archive here -- PowerShell 5.1's Compress-Archive writes backslash
# separators, producing a zip Iris cannot read.
param([string]$Name = "ComplementaryUnbound_r5.7.1-tuned")

$src = "C:\Users\Ryan-PC\Desktop\MC Stuff\shader-tuning\work\ComplementaryUnbound_r5.7.1"
$dst = "C:\Users\Ryan-PC\AppData\Roaming\PrismLauncher\instances\Deer Diary\minecraft\shaderpacks\$Name.zip"

if (-not (Test-Path $src)) { throw "work copy not found: $src" }
if (Test-Path $dst) { Remove-Item $dst -Force }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$zip = [System.IO.Compression.ZipFile]::Open($dst, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $base = $src.TrimEnd('\') + '\'
    Get-ChildItem $src -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($base.Length) -replace '\\', '/'
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $_.FullName, $rel, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $zip.Dispose()
}
Write-Output ("Built {0}.zip ({1:N0} KB) -> shaderpacks/" -f $Name, ((Get-Item $dst).Length / 1KB))
