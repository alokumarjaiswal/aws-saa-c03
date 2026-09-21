<#
.SYNOPSIS
    Creates one or more S3 buckets.

.DESCRIPTION
    Accepts one or more bucket names and creates each as a new S3 bucket
    in the specified region. Bucket names must already comply with S3's
    naming rules (globally unique, lowercase, DNS-compliant, 3-63 characters);
    this script does not pre-validate names locally, so any naming violation
    surfaces as S3's own error rather than a custom one from this script.

    Before creation, the resolved list of target bucket names is printed to
    the console for review.

.PARAMETER BucketName
    One or more bucket names to create.

.PARAMETER Region
    AWS region override. Defaults to $env:AWS_DEFAULT_REGION.

.EXAMPLE
    .\New-S3Buckets.ps1 -BucketName my-bucket -WhatIf

.EXAMPLE
    .\New-S3Buckets.ps1 -BucketName my-bucket

.EXAMPLE
    .\New-S3Buckets.ps1 -BucketName my-bucket-1,my-bucket-2

.EXAMPLE
    .\New-S3Buckets.ps1 -BucketName my-bucket -Region us-east-1
#>

[CmdletBinding(SupportsShouldProcess = $true)]
param (
    [Parameter(Mandatory = $true)]
    [string[]]$BucketName,

    [Parameter(Mandatory = $false)]
    [string]$Region = $env:AWS_DEFAULT_REGION
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module AWS.Tools.S3

foreach ($bucket in $BucketName) {
    if ($PSCmdlet.ShouldProcess($bucket, "Create S3 bucket")) {
        New-S3Bucket -BucketName $bucket -Region $Region | Out-Null
        Write-Host "Created bucket: $bucket"
    }
}