<#
.SYNOPSIS
    Deletes one or more S3 buckets.

.DESCRIPTION
    Bucket names must be specified explicitly — there is no option to delete
    every bucket in the account. Deleting a bucket is a significant action
    (bucket names are globally unique and may be referenced elsewhere), so
    this script requires deliberate, explicit naming every time.

    By default, a non-empty bucket is refused (S3's native behavior) rather
    than silently emptied. Use -IncludeObjects to empty a bucket's contents
    as part of deleting it.

    Before deletion, the resolved list of target buckets is printed to the
    console for review. Each bucket is confirmed individually.

.PARAMETER BucketName
    One or more bucket names to delete.

.PARAMETER IncludeObjects
    If a bucket is not empty, delete its contents first instead of failing.

.PARAMETER Region
    AWS region override. Defaults to $env:AWS_DEFAULT_REGION.

.EXAMPLE
    .\Remove-S3Buckets.ps1 -BucketName my-bucket -WhatIf

.EXAMPLE
    .\Remove-S3Buckets.ps1 -BucketName my-bucket

.EXAMPLE
    .\Remove-S3Buckets.ps1 -BucketName my-bucket-1,my-bucket-2 -IncludeObjects
#>

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param (
    [Parameter(Mandatory = $true)]
    [string[]]$BucketName,

    [Parameter(Mandatory = $false)]
    [switch]$IncludeObjects,

    [Parameter(Mandatory = $false)]
    [string]$Region = $env:AWS_DEFAULT_REGION
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module AWS.Tools.S3

# Show exactly what's targeted before any confirmation prompt.
Write-Host "The following bucket(s) will be deleted$(if ($IncludeObjects) { ' (including their contents)' }):"
$BucketName | ForEach-Object { Write-Host "  $_" }

foreach ($bucket in $BucketName) {
    if ($PSCmdlet.ShouldProcess($bucket, "Delete S3 bucket")) {
        Remove-S3Bucket -BucketName $bucket -Region $Region -DeleteBucketContent:$IncludeObjects -Force | Out-Null
        Write-Verbose "Deleted bucket: $bucket"
    }
}