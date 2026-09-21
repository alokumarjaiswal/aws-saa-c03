<#
.SYNOPSIS
    Uploads a local file or folder to an S3 bucket.

.DESCRIPTION
    Accepts a local file or folder path. A single file is uploaded as one
    object; a folder is uploaded recursively, preserving its relative
    directory structure as the object key structure in the bucket.

    Every object is uploaded unconditionally on each run — existing objects
    with the same key are overwritten, and there is no comparison against
    what's already in the bucket to skip unchanged files.

    Before upload, the resolved file count is confirmed once for the whole
    operation rather than per file, since a folder upload can involve many
    files and per-file prompting would be impractical at that scale.

.PARAMETER BucketName
    Name of the destination S3 bucket.

.PARAMETER Path
    Local path to a file or folder to upload.

.PARAMETER KeyPrefix
    Optional prefix applied to every uploaded object's key (e.g. "backups/2026/").

.PARAMETER Region
    AWS region override. Defaults to $env:AWS_DEFAULT_REGION.

.EXAMPLE
    .\Write-S3Objects.ps1 -BucketName my-bucket -Path .\report.csv -WhatIf

.EXAMPLE
    .\Write-S3Objects.ps1 -BucketName my-bucket -Path .\report.csv

.EXAMPLE
    .\Write-S3Objects.ps1 -BucketName my-bucket -Path .\data-folder -KeyPrefix "backups/2026/"
#>

[CmdletBinding(SupportsShouldProcess = $true)]
param (
    [Parameter(Mandatory = $true)]
    [string]$BucketName,

    [Parameter(Mandatory = $true)]
    [string]$Path,

    [Parameter(Mandatory = $false)]
    [string]$KeyPrefix,

    [Parameter(Mandatory = $false)]
    [string]$Region = $env:AWS_DEFAULT_REGION
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module AWS.Tools.S3

# Local path validation — this is our own check, not an AWS error, since
# there's no API call to make yet if the source path doesn't exist locally.
if (Test-Path -Path $Path -PathType Leaf) {
    $files = @(Get-Item -Path $Path)
    $baseDir = (Resolve-Path -Path (Split-Path -Path $Path -Parent)).Path.TrimEnd('\', '/')
}
elseif (Test-Path -Path $Path -PathType Container) {
    $files = Get-ChildItem -Path $Path -Recurse -File
    $baseDir = (Resolve-Path -Path $Path).Path.TrimEnd('\', '/')
}
else {
    throw "Path '$Path' does not exist."
}

if (-not $files) {
    Write-Warning "No files found at '$Path'."
    return
}

# Build local-path-to-destination-key pairs, preserving relative folder
# structure and applying the optional prefix.
$uploadList = foreach ($file in $files) {
    $relativeKey = $file.FullName.Substring($baseDir.Length).TrimStart('\', '/') -replace '\\', '/'
    $key = if ($KeyPrefix) { "$($KeyPrefix.TrimEnd('/'))/$relativeKey" } else { $relativeKey }
    [PSCustomObject]@{ LocalPath = $file.FullName; Key = $key }
}

if ($PSCmdlet.ShouldProcess($BucketName, "Upload $($uploadList.Count) object(s)")) {
    foreach ($item in $uploadList) {
        Write-S3Object -BucketName $BucketName -File $item.LocalPath -Key $item.Key -Region $Region | Out-Null
        Write-Verbose "Uploaded: $($item.LocalPath) -> s3://$BucketName/$($item.Key)"
    }
    Write-Host "Uploaded $($uploadList.Count) object(s) to bucket '$BucketName'."
}