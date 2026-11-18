#!/usr/bin/env bash
#
# Docker Compose helper for the stack: teardown, logs, status. Bringing it up is deploy.sh's job.
# With --ec2 as the first argument it acts on the stack's EC2 instance instead of this machine.
#
#   ./scripts/docker-helper.sh --stop [--volumes]
#       Stop and remove the stack's containers and network. The Redis volume, which holds the
#       paused tasks, is kept unless --volumes (alias -v) removes it for a clean start; on EC2
#       --volumes also removes the statements volume, with every statement loaded there.
#   ./scripts/docker-helper.sh --logs [-e|--errors] [service...]
#       Follow logs (last 200 lines); -e shows only errors/exceptions. Name services (redis,
#       amex-agent, edge) after the options to show only their logs.
#   ./scripts/docker-helper.sh --ps
#       Show the containers' status.
#   ./scripts/docker-helper.sh --ec2 --delete-stack
#       Delete the EC2 stack of the git branch: its port on the instance and its
#       CloudFront distribution, after asking to type the stack's name. The containers and
#       volumes stay on the instance; remove them first with --ec2 --stop [--volumes].
#   -h, --help
#
# Every argument is checked before anything is loaded or Docker is reached.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

# Its only arguments, checked below, are -v and --volumes.
do_stop() {
  local -a down=(down --remove-orphans)
  [ $# -eq 0 ] || down+=(-v)
  compose "${down[@]}"
}

do_logs() {
  case "${1:-}" in
    -e|--errors)  shift; compose logs -f --tail=200 "$@" | grep -Eai '(error|exception|fatal)' ;;
    *)            compose logs -f --tail=200 "$@" ;;
  esac
}

TARGET=local
if [ "${1:-}" = --ec2 ]; then TARGET=ec2; shift; fi
mode="${1:-}"
case "$mode" in
  -h|--help) print_help; exit 0 ;;
  "")        print_help; exit 1 ;;
esac
shift
case "$mode" in
  --stop)
    for arg in "$@"; do
      case "$arg" in -v|--volumes) ;; *) echo "Unknown --stop option '$arg'" >&2; exit 1 ;; esac
    done ;;
  --ps|--delete-stack)
    [ $# -eq 0 ] || { echo "$mode takes no arguments (got: $*)" >&2; exit 1; } ;;
  --logs) ;;
  *) echo "Unknown command: $mode (expected --stop | --logs | --ps | --delete-stack; see -h)" >&2
     exit 1 ;;
esac
[ "$mode" != --delete-stack ] || [ "$TARGET" = ec2 ] \
  || { echo "--delete-stack needs --ec2 before it" >&2; exit 2; }

load_settings
if [ "$mode" = --delete-stack ]; then
  delete_ec2_stack
  exit 0
fi
if [ "$TARGET" = ec2 ]; then use_ec2; fi
require_docker
case "$mode" in
  --stop) do_stop "$@" ;;
  --logs) do_logs "$@" ;;
  --ps)   compose ps ;;
esac
