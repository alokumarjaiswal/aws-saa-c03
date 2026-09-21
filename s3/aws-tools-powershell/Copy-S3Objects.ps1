<#
.SYNOPSIS
    Downloads S3 object content to local disk.

.DESCRIPTION
    Downloads objects from a bucket, either specific keys, all objects under
    a prefix, or the entire bucket if neither is specified. Downloading does
    not modify or remove anything in the bucket, so no selector is required
    by default.

    Each object is downloaded individually, with its key's folder structure
    recreated under -LocalPath. Existing local files with the same path are
    overwritten without warning.

    A prefix-based or whole-bucket download confirms once for the whole
    operation, naming the object count. Specific-key downloads confirm
    per key, since that list is expected to be short and deliberate.

.PARAMETER BucketName
    Name of the S3 bucket to download from.

.PARAMETER Key
    One or more specific object keys to download. Mutually exclusive with -Prefix.

.PARAMETER Prefix
    Download all objects whose key starts with this prefix. Mutually exclusive
    with -Key. Omit both -Key and -Prefix to download the entire bucket.

.PARAMETER LocalPath
    Local destination folder. Created if it doesn't exist. Each object's key
    structure is recreated as subfolders under this path. Defaults to the
    current directory.

.PARAMETER Region
    AWS region override. Defaults to $env:AWS_DEFAULT_REGION.

.EXAMPLE
    .\Copy-S3Objects.ps1 -BucketName my-bucket -LocalPath .\downloads -WhatIf

.EXAMPLE
    .\Copy-S3Objects.ps1 -BucketName my-bucket -Prefix "logs/2026/" -LocalPath .\downloads

.EXAMPLE
    .\Copy-S3Objects.ps1 -BucketName my-bucket -Key "report.csv","notes.txt" -LocalPath .\downloads
#>

[CmdletBinding(SupportsShouldProcess = $true)]
param (
    [Parameter(Mandatory = $true)]
    [string]$BucketName,

    [Parameter(Mandatory = $false)]
    [string[]]$Key,

    [Parameter(Mandatory = $false)]
    [string]$Prefix,

    [Parameter(Mandatory = $false)]
    [string]$LocalPath = ".",

    [Parameter(Mandatory = $false)]
    [string]$Region = $env:AWS_DEFAULT_REGION
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module AWS.Tools.S3

if ($Key -and $Prefix) {
    throw "-Key and -Prefix are mutually exclusive. Specify at most one."
}

if (-not (Test-Path -Path $LocalPath)) {
    New-Item -ItemType Directory -Path $LocalPath -Force | Out-Null
}

# Downloads one object by key, recreating its key path as subfolders under
# -LocalPath. Used for every mode below — deliberately avoids Copy-S3Object's
# -LocalFolder/-KeyPrefix combination, which was found to mishandle region
# resolution and empty-prefix cases in testing.
function Get-S3ObjectToLocal {
    param ([string]$ObjectKey)

    $destFile = Join-Path -Path $LocalPath -ChildPath ($ObjectKey -replace '/', [IO.Path]::DirectorySeparatorChar)
    $destDir = Split-Path -Path $destFile -Parent
    if ($destDir -and -not (Test-Path -Path $destDir)) {
        New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    }

    Copy-S3Object -BucketName $BucketName -Key $ObjectKey -LocalFile $destFile -Region $Region | Out-Null
    Write-Verbose "Downloaded: s3://$BucketName/$ObjectKey -> $destFile"
}

if ($Key) {
    foreach ($objectKey in $Key) {
        if ($PSCmdlet.ShouldProcess("$BucketName/$objectKey", "Download")) {
            Get-S3ObjectToLocal -ObjectKey $objectKey
        }
    }
    Write-Host "Downloaded $($Key.Count) object(s) from bucket '$BucketName' to '$LocalPath'."
}
else {
    # -Prefix or, if omitted, the whole bucket.
    $matchParams = @{ BucketName = $BucketName; Region = $Region }
    if ($Prefix) { $matchParams['Prefix'] = $Prefix }
    $targets = @(Get-S3Object @matchParams | Select-Object -ExpandProperty Key)

    if (-not $targets) {
        Write-Warning "No matching objects found in bucket '$BucketName'."
        return
    }

    if ($PSCmdlet.ShouldProcess($BucketName, "Download $($targets.Count) object(s) to $LocalPath")) {
        foreach ($objectKey in $targets) {
            Get-S3ObjectToLocal -ObjectKey $objectKey
        }
        Write-Host "Downloaded $($targets.Count) object(s) from bucket '$BucketName' to '$LocalPath'."
    }
}