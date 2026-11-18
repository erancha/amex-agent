# 0006. A guest on loan-simulator's EC2 instance, behind its own CloudFront distribution

- Status: Accepted
- Date: 2026-11-16

## Context

The stack ran only on a workstation under Docker Compose, so only a caller on that machine could
reach it, and Google sign-in accepts only `localhost` or a public hostname. loan-simulator runs
one ARM EC2 instance behind CloudFront, from a CloudFormation template every Docker Compose repo
shares, which other stacks join as guests. Compose runs on the deployer's machine against the
instance's Docker daemon, so nothing this stack bind-mounted (the nginx configuration, the
callers registry, the statements folder) exists on the instance.

## Decision

`deploy.sh --ec2` deploys the shared template as a guest of loan-simulator's stack and runs the
same Compose stack on that instance.

- The guest stack opens the edge's port, 8020, on the host's security group to CloudFront's
  servers only, and adds a CloudFront distribution of its own, whose `https` address is the
  only way in. TLS ends at CloudFront; the hop to the instance is plain HTTP to the edge.
- The Agent Card advertises that address, and it is the statements page's address too.
- The images are built on the deployer's machine for ARM, the edge's with its nginx
  configuration built in, and loaded into the instance's daemon over SSH.
- The callers registry reaches each replica as a Compose secret, which Compose writes into the
  container through the Docker API on both targets.
- On the instance the replicas share a named `statements` volume, loaded through the
  statements page.

## Consequences

- No instance, address or security group of its own to pay for; the guest's whole footprint is
  a port and a distribution, and loan-simulator's stack must be deployed first.
- The stack shares a 2 GB instance with other stacks; its memory limits add up to 1.6 GB.
- A caller on the same instance fetches the card at `http://host.docker.internal:8020` but
  calls the advertised address, through CloudFront. CloudFront ends a response that sends
  nothing for 30 seconds, and an A2A stream sends nothing while the model categorizes a month's
  unseen merchants.
- The instance's statements are apart from the workstation's, on a disk with no snapshots.
