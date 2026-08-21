<#
.SYNOPSIS
    Deletes objects from an S3 bucket, by specific key(s), by prefix, or all of them.

.DESCRIPTION
    Requires exactly one of -Key, -Prefix, or -All to be specified — running
    with none of them is refused deliberately, to prevent an unfiltered call
    from deleting every object in the bucket by accident. -All still requires
    the flag explicitly, so a full-bucket delete is always an intentional choice.

    Before deletion, the resolved list of target object keys is printed to the
    console so it can be reviewed prior to confirming. -All confirms once for
    the whole operation; -Key and -Prefix confirm per object.

.PARAMETER BucketName
    Name of the S3 bucket to delete objects from.

.PARAMETER Key
    One or more specific object keys to delete.

.PARAMETER Prefix
    Delete all objects whose key starts with this prefix (e.g. "logs/2025/").

.PARAMETER All
    Delete every object in the bucket. Mutually exclusive with -Key and -Prefix.
    Confirms once for the whole operation (naming the object count) rather than
    per object, since prompting per-object is impractical at bucket scale.

.PARAMETER Region
    AWS region override. Defaults to $env:AWS_DEFAULT_REGION.

.EXAMPLE
    .\Remove-S3BucketObjects.ps1 -BucketName my-bucket -Key "file1.txt","file2.txt"

.EXAMPLE
    .\Remove-S3BucketObjects.ps1 -BucketName my-bucket -Prefix "logs/2025/" -WhatIf

.EXAMPLE
    .\Remove-S3BucketObjects.ps1 -BucketName my-bucket -All -WhatIf

.EXAMPLE
    .\Remove-S3BucketObjects.ps1 -BucketName my-bucket -All
#>

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param (
    [Parameter(Mandatory = $true)]
    [string]$BucketName,

    [Parameter(Mandatory = $false)]
    [string[]]$Key,

    [Parameter(Mandatory = $false)]
    [string]$Prefix,

    [Parameter(Mandatory = $false)]
    [switch]$All,

    [Parameter(Mandatory = $false)]
    [string]$Region = $env:AWS_DEFAULT_REGION
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module AWS.Tools.S3

# Deliberate safety gate: exactly one of -Key, -Prefix, -All must be given.
# No selector at all means "delete everything" by accident, which this
# script refuses. Combining -All with -Key/-Prefix is refused too, since
# it's ambiguous which scope actually applies.
$selectorCount = @($Key, $Prefix, $All.IsPresent) | Where-Object { $_ } | Measure-Object | Select-Object -ExpandProperty Count
if ($selectorCount -eq 0) {
    throw "Specify -Key, -Prefix, or -All to select what to delete. Refusing to run unfiltered."
}
if ($selectorCount -gt 1) {
    throw "-Key, -Prefix, and -All are mutually exclusive. Specify exactly one."
}

# Resolve the target key list.
if ($All) {
    $targets = @(Get-S3Object -BucketName $BucketName -Region $Region |
        Select-Object -ExpandProperty Key)
}
elseif ($Prefix) {
    $targets = @(Get-S3Object -BucketName $BucketName -Prefix $Prefix -Region $Region |
        Select-Object -ExpandProperty Key)
}
else {
    $targets = $Key
}

if (-not $targets) {
    Write-Warning "No matching objects found in bucket '$BucketName'."
    return
}

# Show exactly what's about to be deleted before any confirmation prompt.
Write-Host "The following object(s) will be deleted:"
$targets | ForEach-Object { Write-Host "  $_" }

if ($All) {
    # Single bulk confirmation naming the count, instead of one prompt per
    # object — per-object prompting is impractical at full-bucket scale.
    if ($PSCmdlet.ShouldProcess($BucketName, "Delete ALL $($targets.Count) object(s)")) {
        foreach ($objectKey in $targets) {
            Remove-S3Object -BucketName $BucketName -Key $objectKey -Region $Region -Force | Out-Null
            Write-Verbose "Deleted: $BucketName/$objectKey"
        }
    }
}
else {
    foreach ($objectKey in $targets) {
        if ($PSCmdlet.ShouldProcess("$BucketName/$objectKey", "Delete S3 object")) {
            Remove-S3Object -BucketName $BucketName -Key $objectKey -Region $Region -Force | Out-Null
            Write-Verbose "Deleted: $BucketName/$objectKey"
        }
    }
}