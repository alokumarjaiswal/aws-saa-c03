# Bucket A: public static website
# - public read, no versioning needed, no encryption toggle relevant to visitors
module "website_bucket" {
  source = "./modules/s3-bucket"

  bucket_name          = "my-demo-website-${data.aws_caller_identity.current.account_id}"
  enable_versioning    = false
  enable_encryption    = false
  block_public_access  = false
  enable_website       = true

  tags = {
    Purpose = "public-website"
  }
}

# Bucket B: private, encrypted, versioned — e.g. for sensitive app data
module "secure_bucket" {
  source = "./modules/s3-bucket"

  bucket_name          = "my-demo-secure-data-${data.aws_caller_identity.current.account_id}"
  enable_versioning    = true
  enable_encryption    = true
  block_public_access  = true
  enable_website       = false

  tags = {
    Purpose = "sensitive-data"
  }
}

# Used only to make bucket names unique to your AWS account (see note below)
data "aws_caller_identity" "current" {}
