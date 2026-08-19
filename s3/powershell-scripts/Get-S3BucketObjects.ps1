<#
.SYNOPSIS
    Lists objects in a given S3 bucket.

.DESCRIPTION
    Calls Get-S3Object for the specified bucket, optionally filtered by prefix.

.PARAMETER BucketName
    Name of the S3 bucket to list objects from.

.PARAMETER Prefix
    Optional key prefix to filter results (e.g. "logs/2026/").

.PARAMETER Region
    AWS region override. Defaults to $env:AWS_DEFAULT_REGION.

.EXAMPLE
    .\Get-S3BucketObjects.ps1 -BucketName my-bucket

.EXAMPLE
    .\Get-S3BucketObjects.ps1 -BucketName my-bucket -Prefix "logs/"
#>

[CmdletBinding()]
param (
    [Parameter(Mandatory = $true)]
    [string]$BucketName,

    [Parameter(Mandatory = $false)]
    [string]$Prefix,

    [Parameter(Mandatory = $false)]
    [string]$Region = $env:AWS_DEFAULT_REGION
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module AWS.Tools.S3

$params = @{
    BucketName = $BucketName
    Region     = $Region
}
if ($Prefix) {
    $params['Prefix'] = $Prefix
}

# Get-S3Object handles pagination internally, returning all matching objects
# in one call regardless of bucket size.
$objects = Get-S3Object @params

if (-not $objects) {
    Write-Warning "No objects found in bucket '$BucketName'$(if ($Prefix) { " with prefix '$Prefix'" })."
    return
}

$objects |
    Select-Object Key, Size, LastModified |
    Sort-Object LastModified -Descending |
    Format-Table -AutoSize