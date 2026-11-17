# Deployment Workflow

## Overview

This document describes the current deployment workflow on the AWS stack, with CloudFront, S3, and EC2. Currently, there is no GitHub Actions pipeline running, but expect that to change soon. When it does, this document will be updated. 

**The architecture is not finalized yet. Expect that this document may go out of date with Terraform deployments or GitHub Actions pipelines.**

## Deployment Architecture

Updates to the frontend/backend are performed through EC2. Currently, the EC2 instance has an IAM role that allows it to update the S3 files. When the S3 Bucket is updated with new frontend files, CloudFront automatically serves them upon browser refresh. The backend Spring Boot architecture is running in a Docker container on EC2, which needs to be rebuilt and restarted manually. 

**All deploys are manual at the moment.**

### Accessing EC2

Access to the EC2 Instance is done by logging into the AWS Console with the login details provided via Teams. Upon first login, IAM user passwords are required to be changed. In the interest of obscurity, specific usernames and EC2 access processes are kept out of source control. 

IAM users have restricted permissions and cannot spin up new instances, edit files, or configure networking setup. Despite this, users can access EC2 and run all required deployment commands.

### Required Commands

On the EC2 instance, this GitHub repository is located at `~/ReservationSystem-SDMesaCollegeBT`. If the repository is not immediately visible, check that the user is correct (i.e., not `root`). 

#### Update Frontend

1. Change directory to the repository with `cd ~/ReservationSystem-SDMesaCollegeBT`
2. Update to the most current version of the repository with `git pull`.
3. Sync updated frontend files with `aws s3 sync ./frontend s3://bt-reservation`.

No further action is required at this point, as CloudFront will automatically serve the updated files.

#### Update Backend

Updating the backend is more complex than updating the frontend. The Spring Boot server is running in a Docker instance, which requires rebuilding and restarting to enable.

docker build -t reservation-backend backend/.
docker stop <container-name> && docker rm <container-name>
docker run -d -p 8080:8080 --env-file /etc/reservation-backend.env reservation-backend
