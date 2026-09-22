variable "bucket_name" {
  description = "Globally unique S3 bucket name"
  type        = string
}

variable "enable_versioning" {
  description = "Enable object versioning on this bucket"
  type        = bool
  default     = false
}

variable "enable_encryption" {
  description = "Enable default server-side encryption (SSE-S3/AES256)"
  type        = bool
  default     = true
}

variable "block_public_access" {
  description = "If true, blocks all public access (the safe default). Set false only for buckets meant to be public, e.g. static websites."
  type        = bool
  default     = true
}

variable "enable_website" {
  description = "Configure this bucket for static website hosting"
  type        = bool
  default     = false
}

variable "tags" {
  description = "Tags applied to the bucket"
  type        = map(string)
  default     = {}
}
