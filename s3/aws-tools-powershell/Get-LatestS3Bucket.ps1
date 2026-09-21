<#
.SYNOPSIS
    Gets the single most recently created S3 bucket.

.DESCRIPTION
    Calls Get-S3Bucket (global-endpoint call, no region required for listing),
    sorts by CreationDate, and returns just the newest one.

.PARAMETER Region
    AWS region override. Defaults to $env:AWS_DEFAULT_REGION.

.EXAMPLE
    .\Get-LatestS3Bucket.ps1
#>

[CmdletBinding()]
param (
    [Parameter(Mandatory = $false)]
    [string]$Region = $env:AWS_DEFAULT_REGION
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module AWS.Tools.S3

$latestBucket = Get-S3Bucket -Region $Region |
    Sort-Object -Property CreationDate -Descending |
    Select-Object -First 1

if (-not $latestBucket) {
    Write-Warning "No buckets found."
    return
}

$location = (Get-S3BucketLocation -BucketName $latestBucket.BucketName).Value
if ([string]::IsNullOrEmpty($location)) {
    $location = 'us-east-1'
}

[PSCustomObject]@{
    BucketName   = $latestBucket.BucketName
    CreationDate = $latestBucket.CreationDate
    BucketRegion = $location
} | Format-Table -AutoSize