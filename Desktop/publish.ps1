param(
    [string]$Configuration = "Release",
    [string]$Runtime = "win-x64"
)

$publishProfiles = @(
    @{
        Runtime = "win-x64"
        Name = "Windows x64"
    }
)

if ($Runtime -ne "all") {
    $publishProfiles = $publishProfiles | Where-Object { $_.Runtime -eq $Runtime }
}

foreach ($profile in $publishProfiles) {
    Write-Host "Publishing for $($profile.Name)..."
    
    $outputPath = "publish/$($profile.Runtime)"
    
    dotnet publish RemoteControlServer.csproj `
        --configuration $Configuration `
        --runtime $profile.Runtime `
        --self-contained true `
        --output $outputPath `
        -p:PublishSingleFile=true `
        -p:EnableCompressionInSingleFile=true `
        -p:DebugType=none `
        -p:DebugSymbols=false `
        -p:InvariantGlobalization=true
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Successfully published to $outputPath"
    } else {
        Write-Error "Failed to publish for $($profile.Name)"
    }
}

Write-Host "Publishing complete!"