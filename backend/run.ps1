$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

$sourceFiles = Get-ChildItem "backend\src" -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }

if ($sourceFiles.Count -eq 0) {
    throw "No Java source files were found in backend\src."
}

New-Item -ItemType Directory -Force "backend\out" | Out-Null
javac -cp "backend\lib\ojdbc6-11.2.0.4.jar" -d "backend\out" $sourceFiles
if ($LASTEXITCODE -ne 0) {
    throw "Java compilation failed."
}

java -cp "backend\out;backend\lib\ojdbc6-11.2.0.4.jar" com.smarttask.main.Main
