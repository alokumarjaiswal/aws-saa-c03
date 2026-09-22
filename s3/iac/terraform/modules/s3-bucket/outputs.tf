output "bucket_arn" {
  value = aws_s3_bucket.this.arn
}

output "bucket_id" {
  value = aws_s3_bucket.this.id
}

output "website_endpoint" {
  description = "Only set when enable_website = true; null otherwise"
  value       = var.enable_website ? aws_s3_bucket_website_configuration.this[0].website_endpoint : null
}
