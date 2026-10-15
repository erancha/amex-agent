#!/usr/bin/env python3
"""Proves a running amex-agent speaks the contract in docs/a2a-contract.md, from the client side.

Checks, in order: the Agent Card advertises the skill and streaming; a streaming task for the
covered month completes with the expected number of transactions; a task for an uncovered month
ends in input-required with the statement-missing reason; continuing it asks again; cancelling
the waiting task succeeds while cancelling the completed one is refused; a wrong token is
refused with 401.

Usage: check_agent.py <base-url> <token> <covered-month YYYY-MM> <expected-count> <uncovered-month>
Exit status 0 when every check passes; the first failing check is printed and exits 1.
"""
import asyncio
import sys
from contextlib import aclosing

import httpx
from a2a.client import A2ACardResolver, ClientConfig, ClientFactory
from a2a.helpers import get_data_parts, new_data_part, new_message
from a2a.types import CancelTaskRequest, Role, SendMessageRequest, TaskState
from a2a.utils.errors import TaskNotCancelableError

SKILL = "monthly-transactions"


def check(condition: bool, label: str) -> None:
    print(("PASS: " if condition else "FAIL: ") + label)
    if not condition:
        sys.exit(1)


# States after which the task produces no further events until the client acts.
STOP_STATES = {TaskState.TASK_STATE_COMPLETED, TaskState.TASK_STATE_FAILED,
               TaskState.TASK_STATE_CANCELED, TaskState.TASK_STATE_REJECTED,
               TaskState.TASK_STATE_INPUT_REQUIRED, TaskState.TASK_STATE_AUTH_REQUIRED}


async def stream(client, message):
    """Collects (last status update, artifact rows) from one streamed request.

    Reading stops at the first final or interrupted status, as the Java client does, rather than
    relying on the server to end the stream there.
    """
    last_status = None
    rows = []
    events = client.send_message(SendMessageRequest(message=message))
    async with aclosing(events):
        async for chunk in events:
            if chunk.HasField("artifact_update"):
                for data in get_data_parts(chunk.artifact_update.artifact.parts):
                    rows.extend(data["transactions"])
            elif chunk.HasField("status_update"):
                last_status = chunk.status_update
                if last_status.status.state in STOP_STATES:
                    break
    return last_status, rows


def user_message(data, **ids):
    return new_message([new_data_part(data)], role=Role.ROLE_USER, **ids)


async def main(base_url, token, covered, expected_count, uncovered):
    auth = {"Authorization": f"Bearer {token}"}
    async with httpx.AsyncClient(headers=auth, timeout=60) as http:
        card = await A2ACardResolver(http, base_url).get_agent_card()
        check(any(s.id == SKILL for s in card.skills), f"card advertises {SKILL}")
        check(card.capabilities.streaming, "card advertises streaming")
        client = ClientFactory(ClientConfig(streaming=True, httpx_client=http)).create(card)

        completed, rows = await stream(client, user_message({"month": covered}))
        check(completed is not None
              and completed.status.state == TaskState.TASK_STATE_COMPLETED,
              f"{covered} completes")
        check(len(rows) == int(expected_count), f"{covered} streams {expected_count} transactions")
        check(all(r["category"] for r in rows), "every transaction carries a category")

        waiting, _ = await stream(client, user_message({"month": uncovered}))
        check(waiting is not None
              and waiting.status.state == TaskState.TASK_STATE_INPUT_REQUIRED,
              f"{uncovered} asks for input")
        reasons = get_data_parts(waiting.status.message.parts)
        check(any(d.get("reason") == "statement-missing" for d in reasons),
              "reason is statement-missing")

        resumed, _ = await stream(client, user_message(
            {"action": "continue"}, task_id=waiting.task_id, context_id=waiting.context_id))
        check(resumed is not None
              and resumed.status.state == TaskState.TASK_STATE_INPUT_REQUIRED,
              "continue without the file asks again")

        canceled = await client.cancel_task(CancelTaskRequest(id=waiting.task_id))
        check(canceled.status.state == TaskState.TASK_STATE_CANCELED,
              "cancel of the waiting task succeeds")

        try:
            await client.cancel_task(CancelTaskRequest(id=completed.task_id))
            refused = False
        except TaskNotCancelableError:
            refused = True
        check(refused, "cancel of the completed task is refused")

    wrong = {"Authorization": "Bearer wrong", "A2A-Version": "1.0"}
    async with httpx.AsyncClient(headers=wrong, timeout=10) as http:
        r = await http.post(base_url + "/", json={"jsonrpc": "2.0", "id": 1, "method": "GetTask",
                                                   "params": {"id": "x"}})
        check(r.status_code == 401, "wrong token is 401")
    print("All checks passed.")


if __name__ == "__main__":
    if len(sys.argv) != 6:
        sys.exit(__doc__)
    asyncio.run(main(*sys.argv[1:]))
