# Refresh Docker Login
`aws ecr-public get-login-password --region us-east-1 | docker login --username AWS --password-stdin public.ecr.aws/sumologic`

# Local Publish
`sbt docker:publishLocal`
