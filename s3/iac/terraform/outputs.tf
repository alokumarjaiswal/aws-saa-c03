output "website_bucket_endpoint" {
  value = module.website_bucket.website_endpoint
}

output "secure_bucket_arn" {
  value = module.secure_bucket.bucket_arn
}
