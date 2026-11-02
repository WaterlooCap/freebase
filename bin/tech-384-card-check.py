#!/usr/bin/env python3
"""TECH-384 pre-merge check: does every saved card still load and compile on Metabase 64?

Run it twice: against the live 63 app for a baseline, then against 64 on a restored copy of the app
database. Reports card ids and check names only, never names or SQL.

Checks are GET /api/card/:id, GET /api/card/:id/query_metadata and POST /api/dataset/native, which
compiles a query without running it. None of them runs a card or changes a card. --restored-copy adds
a no-op PUT of each card's own dataset_query, and refuses a live host.

    export MB_API_KEY=...
    python3 -I bin/tech-384-card-check.py --base-url https://analytics.waterloocap.com --out baseline.json
    python3 -I bin/tech-384-card-check.py --base-url http://copy:3000 --restored-copy \
        --baseline baseline.json --out copy.json

Passing: exit 0 on the second run, meaning no card fails on 64 that passed on 63.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

LIVE_HOSTS = ("waterloocap.com",)
TIMEOUT_SECONDS = 60


class Client:
    def __init__(self, base_url, api_key):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base_url + path, data=data, method=method, headers={
            "x-api-key": self.api_key, "Content-Type": "application/json", "User-Agent": "tech-384-card-check"})
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            return error.code, None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            return 0, None


def compile_body(card):
    query = {k: v for k, v in (card.get("dataset_query") or {}).items() if k != "parameters"}
    return {**query, "pretty": False}


def check_card(client, card_id, restored_copy):
    failures = {}
    status, card = client.call("GET", f"/api/card/{card_id}")
    if status != 200 or not isinstance(card, dict):
        return {"detail": status}
    status, _ = client.call("GET", f"/api/card/{card_id}/query_metadata")
    if status != 200:
        failures["query_metadata"] = status
    status, _ = client.call("POST", "/api/dataset/native", compile_body(card))
    if status not in (200, 202):
        failures["compile"] = status
    if restored_copy and not card.get("archived"):
        status, _ = client.call("PUT", f"/api/card/{card_id}", {"dataset_query": card.get("dataset_query")})
        if status != 200:
            failures["save"] = status
    return failures


def list_ids(client):
    ids = set()
    for path in ("/api/card", "/api/card?f=archived"):
        status, cards = client.call("GET", path)
        if status != 200 or not isinstance(cards, list):
            sys.exit(f"{path} returned {status}: the listing itself fails, which Launchpad's sync depends on")
        ids.update(card["id"] for card in cards)
    return ids


def regressions(results, baseline):
    """Ids failing here that passed in the baseline, or that the baseline had and this run lost."""
    failing = {card_id for card_id, failed in results.items() if failed}
    passed_before = {card_id for card_id, failed in baseline.items() if not failed}
    return sorted((failing & passed_before) | (set(baseline) - set(results)), key=int)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--baseline")
    parser.add_argument("--restored-copy", action="store_true")
    parser.add_argument("--pause-seconds", type=float, default=0.1)
    args = parser.parse_args()
    api_key = os.environ.get("MB_API_KEY")
    if not api_key:
        sys.exit("set MB_API_KEY")
    host = urlsplit(args.base_url).hostname or ""
    if args.restored_copy and host.endswith(LIVE_HOSTS):
        sys.exit("--restored-copy writes to cards and refuses a live host")

    client = Client(args.base_url, api_key)
    baseline = json.load(open(args.baseline)) if args.baseline else None
    ids = list_ids(client) | ({int(card_id) for card_id in baseline} if baseline else set())
    results = {}
    for card_id in sorted(ids):
        results[str(card_id)] = check_card(client, card_id, args.restored_copy)
        time.sleep(args.pause_seconds)
    with open(args.out, "w") as handle:
        json.dump(results, handle, indent=0, sort_keys=True)

    failing = sorted((card_id for card_id, failed in results.items() if failed), key=int)
    print(f"cards checked: {len(results)}; failing: {len(failing)}")
    for card_id in failing:
        print(card_id, json.dumps(results[card_id]))
    if baseline is None:
        return 0
    lost = regressions(results, baseline)
    print(f"regressions against baseline: {len(lost)}")
    print(" ".join(lost))
    return 1 if lost else 0


if __name__ == "__main__":
    sys.exit(main())
