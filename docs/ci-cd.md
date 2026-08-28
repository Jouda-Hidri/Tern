# CI/CD

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `CI` (`maven.yml`) | push / PR to main | `mvn verify` on Temurin 17, uploads the surefire reports, then builds the container image to prove a clean checkout is buildable |
| `CD` (`cd.yml`) | a green `CI` run on main | Publishes to Amazon ECR tagged `latest` and `sha-<commit>`, and writes the `kubectl set image` rollout commands to the run summary |
| `DORA Lead Time` (`dora.yml`) | PR merged | Measures first-commit-to-merge lead time |

CD is triggered by `workflow_run`, so it publishes only what CI already proved green rather than
rebuilding and re-testing on a second trigger. Images are tagged with the commit sha so a
deployment can name exactly what it runs; the rollout itself is manual, since there is no
long-lived cluster to deploy to.

CD is the only part that needs an AWS account, and only for publishing. Without one, `CI` still
runs on every push and pull request, and running it locally works unchanged - both options build
the image from source.

`CD` is what breaks. With no `AWS_ROLE_ARN` variable set, `role-to-assume` is empty and the run
stops after a few seconds with:

````
Error: Credentials could not be loaded, please check your action inputs:
Could not load credentials from any providers
````

Set the variable as described below, or delete `.github/workflows/cd.yml` if the red run is just
noise.

## Where CD pushes to, and how it authenticates

`deployment/aws/ecr-oidc.yaml` creates the ECR repository and an IAM role GitHub Actions assumes
through OIDC, so **no AWS access key exists anywhere**. The role's trust policy accepts only a
token whose audience is `sts.amazonaws.com` and whose subject names your repository on
`refs/heads/main`.

This is deployed and in use: stack `tern-ci` in `eu-central-1`, and CD pushes to it on every green
build of main. To set it up in another account:

````
aws configure sso                  # first time
aws sso login --profile <name>     # afterwards, to refresh

aws cloudformation deploy \
  --region eu-central-1 \
  --stack-name tern-ci \
  --template-file deployment/aws/ecr-oidc.yaml \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides GitHubRepository=<owner>/<repo>

aws cloudformation describe-stacks --region eu-central-1 --stack-name tern-ci \
  --query 'Stacks[0].Outputs' --output table
````

If the account already has a GitHub OIDC provider, add `CreateOidcProvider=false` - an account
may only hold one per URL.

Then hand the role ARN from those outputs to GitHub. It is a repository *variable*, not a
secret - a role ARN is not sensitive, and it is worthless without a token from your repository:

````
gh variable set AWS_ROLE_ARN --body 'arn:aws:iam::<account-id>:role/tern-ci-github-actions'
````

A **variable**, not a secret - `cd.yml` reads `vars.AWS_ROLE_ARN`, so a secret of the same name
resolves to empty and reproduces the error above.

Pull what CD published:

````
aws ecr get-login-password --region eu-central-1 \
  | docker login --username AWS --password-stdin <account-id>.dkr.ecr.eu-central-1.amazonaws.com
docker pull --platform linux/amd64 <account-id>.dkr.ecr.eu-central-1.amazonaws.com/tern:latest
````

GitHub's runners are x86_64, so CD publishes `linux/amd64` only - hence `--platform` on arm64
machines. Building locally has no such constraint.

A lifecycle policy keeps the last two builds and expires untagged manifests after a day, which
holds the repository inside the 500MB free tier. Scanning on push is enabled.
