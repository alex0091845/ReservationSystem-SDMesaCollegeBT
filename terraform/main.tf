provider "aws" {
  region = "us-west-1"
}

resource "aws_s3_bucket" "frontend_bucket" {
  bucket = "bt-reservation-tf"
}

resource "aws_s3_bucket_public_access_block" "fronted_bucket_block_public" {
  bucket = aws_s3_bucket.frontend_bucket.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
