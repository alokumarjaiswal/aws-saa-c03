<#
.SYNOPSIS
    Lists the 5 most recently created S3 buckets.

.DESCRIPTION
    Calls Get-S3Bucket (a global-endpoint call, no region required for listing)
    and returns the top 5 buckets sorted by CreationDate, newest first.

.PARAMETER Region
    AWS region override. Defaults to $env:AWS_DEFAULT_REGION.
    Not strictly required for this script since bucket listing is global,
    but kept for consistency with other scripts in this project.

.EXAMPLE
    .\Get-RecentS3Buckets.ps1
#>

[CmdletBinding()]
param (
    [Parameter(Mandatory = $false)]
    [string]$Region = $env:AWS_DEFAULT_REGION
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module AWS.Tools.S3

$recentBuckets = Get-S3Bucket -Region $Region |
    Sort-Object -Property CreationDate -Descending |
    Select-Object -First 5

if (-not $recentBuckets) {
    Write-Warning "No buckets found."
    return
}

# Get-S3Bucket doesn't populate region, so it's looked up per bucket.
# This costs one extra API call per bucket (fine at 5 buckets; would need
# batching/throttling awareness if this script's -First value grows large).
$result = foreach ($bucket in $recentBuckets) {
    $location = (Get-S3BucketLocation -BucketName $bucket.BucketName).Value

    # S3 returns an empty string for us-east-1 specifically (a historical
    # quirk of the API, not a bug here) — normalize it for readable output.
    if ([string]::IsNullOrEmpty($location)) {
        $location = 'us-east-1'
    }

    [PSCustomObject]@{
        BucketName   = $bucket.BucketName
        CreationDate = $bucket.CreationDate
        BucketRegion = $location
    }
}

$result | Format-Table -AutoSize