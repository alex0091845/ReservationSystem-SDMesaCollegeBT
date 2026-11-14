provider "aws" {
  region = "us-west-1"

  default_tags {
    tags = {
      Project = "BT-Reservation"
    }
  }
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

resource "aws_vpc" "backend_ec2_vpc" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
}

resource "aws_subnet" "backend_ec2_subnet" {
  vpc_id = aws_vpc.backend_ec2_vpc.id

  cidr_block = "10.0.0.0/24"
}

