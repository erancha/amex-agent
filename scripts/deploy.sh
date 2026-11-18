#!/usr/bin/env bash
#
# Deploy amex-agent: build the images and start Redis, two agent replicas and the nginx edge in
# Docker Compose, then return once the edge answers the agent's health check and print the values
# a caller's operator registers this agent with.
#
#   ./scripts/deploy.sh          on this machine's Docker
#   ./scripts/deploy.sh --ec2    on an EC2 instance, behind a CloudFront distribution of its own
#                                (created on the first run)
#   -h, --help                   show this help
#
# The agent refuses to start when APP_AUTH_CLIENT_ID, APP_LLM_MODEL or APP_LLM_API_KEY is blank,
# or, while access is required, when no A2A caller is registered, so the script checks them first
# and names every setting that is blank with its settings file. Every run starts with
# ./scripts/init-env.sh, which creates a missing settings file and keeps the existing ones, so a
# first run creates them all and then stops on the settings left to fill in. Register each caller
# with ./scripts/tools/register-caller.sh. Stop or inspect the stack with
# ./scripts/docker-helper.sh (--stop / --logs / --ps), which takes --ec2 too.
#
# On this machine the edge is published on the loopback interface at EDGE_HOST_PORT of
# stack-params.sh (http://localhost:8020: the statements page, the Agent Card and the A2A
# endpoint), and the replicas mount the statements folder APP_STATEMENTS_DIR.
#
# --ec2 also needs the access key pair and region in scripts/lib/aws-config.sh, and deploys to
# the account its credentials belong to. The stack is the instance's host, unless EC2_HOST_STACK
# of params.sh names another Docker Compose repo's host stack, deployed first from its repo: then
# it is a guest on that stack's instance. It creates or updates the CloudFormation stack of
# scripts/template-ec2.yaml (the git branch selects it: amex-agent on main, amex-agent-fb on any
# other branch), which opens EC2_ORIGIN_PORT on the instance to CloudFront only and puts a
# CloudFront distribution in front of it. It then builds the images here for ARM, loads them
# into the instance's Docker daemon over SSH, and runs Compose against that daemon. The settings
# files and the callers registry stay on this machine; each container's own settings, secrets
# included, reach the instance as part of the container's configuration, which Docker keeps on
# the instance's encrypted disk. The Agent Card advertises the CloudFront address there, and the
# statements live in the statements volume on the instance, loaded through the statements page.
# Register the printed address with Google once (docs/sign-in.md).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

TARGET=local
case "${1:-}" in
  -h|--help) print_help; exit 0 ;;
  --ec2)     TARGET=ec2 ;;
  "")        ;;
  *)         print_help >&2; exit 2 ;;
esac

"$SCRIPT_DIR/init-env.sh"
load_settings
collect_missing_settings stack-params.sh APP_AGENT_URL APP_STATEMENTS_DIR EDGE_HOST_PORT
collect_missing_settings secrets.sh APP_LLM_API_KEY
collect_missing_settings params.sh APP_AUTH_CLIENT_ID APP_LLM_MODEL
require_collected_settings
require_callers_file
require_docker

# The caller's operator received the secret once, from register-caller.sh.
print_target_handover() {  # page-url
  print_handover "each A2A caller's operator" \
    "the one register-caller.sh printed when it registered that caller" "$1"
}

deploy_local() {
  local base="http://localhost:$EDGE_HOST_PORT"
  echo "Building and starting Redis, the agent replicas and the edge..."
  compose up -d --build
  wait_for_http "$base/actuator/health" \
    || { echo "the agent did not answer on $base; see ./scripts/docker-helper.sh --logs" >&2
         exit 1; }
  echo "amex-agent answers at $base (card: $base/.well-known/agent-card.json)."
  print_target_handover "$base"
  echo "  stop it or tail logs:  ./scripts/docker-helper.sh --stop | --logs"
}

deploy_ec2() {
  load_aws_config
  resolve_ec2_stack
  deploy_ec2_stack
  resolve_ec2_host
  COMPOSE_FILES+=("$EC2_COMPOSE_FILE")

  echo "Building the images for linux/arm64 on this machine..."
  compose build
  prepare_ec2_host
  echo "Loading the images into the instance's Docker daemon..."
  # shellcheck disable=SC2046  # one image name per word, none with spaces
  docker save $(ec2_built_images) | gzip | ssh_ec2 "$EC2_HOST_USER@$EC2_HOST" 'gunzip | docker load'

  open_ec2_docker
  echo "Starting Redis, the agent replicas and the edge on the instance..."
  compose up -d --no-build --remove-orphans
  # A deploy leaves the previous build's layers behind; the instance's disk is small.
  docker image prune -f >/dev/null
  wait_for_http "$EC2_URL/actuator/health" \
    || { echo "the agent did not answer on $EC2_URL/actuator/health;" \
              "see ./scripts/docker-helper.sh --ec2 --logs" >&2
         exit 1; }
  echo "amex-agent answers at $EC2_URL (card: $EC2_URL/.well-known/agent-card.json)."
  print_target_handover "$EC2_URL"
  echo "  Register it with Google once: $EC2_URL as an authorized JavaScript origin of the"
  echo "  agent's OAuth client (docs/sign-in.md)."
  echo "  stop it or tail logs:  ./scripts/docker-helper.sh --ec2 --stop | --logs"
}

"deploy_$TARGET"
