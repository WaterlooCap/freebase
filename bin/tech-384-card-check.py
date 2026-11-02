#!/usr/bin/env python3
"""TECH-384 pre-merge check: does every saved card still load and compile on Metabase 64?

Run it twice: read-only against the live 63 app for a baseline, then with --restored-copy against 64 on
a restored copy of the app database. Reports card ids and check names only, never names or SQL.

Default mode is read-only: GET /api/card/:id, GET /api/card/:id/query_metadata, and POST
/api/dataset/native, which compiles a query without running it. Compiling sends each parameter's default
value, or a sample value for its type, so 64's parameter validation is exercised and cards with required
tags are tested. --restored-copy adds a no-op PUT of each card's own dataset_query, and it runs only on a
loopback host; --no-save leaves that out. Cards are never run: a copy has no warehouse to run them on.

The base URL must be https, or http on a loopback host. Redirects and proxies are refused. The runs use
separate keys: MB_LIVE_API_KEY goes only to a live host, and MB_COPY_API_KEY, which must exist only on the
copy, goes only to --restored-copy runs.

    MB_LIVE_API_KEY=... python3 -I bin/tech-384-card-check.py \\
        --base-url https://analytics.waterloocap.com --out baseline.json
    MB_COPY_API_KEY=... python3 -I bin/tech-384-card-check.py \\
        --base-url http://127.0.0.1:3000 --restored-copy --baseline baseline.json --out copy.json

Passing: exit 0 on the second run. A check that fails on 64 and did not fail on 63 is a new failure, and
every new failure must be zero or listed by id with --accept after someone has read that card. Cards that
already fail on 63 are reported separately and do not count.
"""
import argparse
import ipaddress
import json
import os
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

LIVE_HOSTS = ("analytics.waterloocap.com", "metabase-jj7kf.ondigitalocean.app")
TIMEOUT_SECONDS = 60
SAMPLE_VALUES = {
    "number": 1,
    "boolean": True,
    "temporal-unit": "month",
    "date": "past30days",
    "date/single": "2026-01-15",
    "date/range": "2026-01-01~2026-01-31",
    "date/month-year": "2026-01",
    "date/quarter-year": "Q1-2026",
}


class RefuseRedirects(urllib.request.HTTPRedirectHandler):
    """Surface every 3xx as an HTTPError, so the API key is never sent to a redirect target."""

    def redirect_request(self, *_args, **_kwargs):
        return None


def is_loopback(host):
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def is_live(host):
    return host in LIVE_HOSTS


class Client:
    def __init__(self, base_url, api_key):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RefuseRedirects)

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base_url + path, data=data, method=method, headers={
            "x-api-key": self.api_key, "Content-Type": "application/json", "User-Agent": "tech-384-card-check"})
        try:
            with self.opener.open(request, timeout=TIMEOUT_SECONDS) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            return error.code, None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            return 0, None


def filled_value(parameter):
    """The parameter's default, or a sample for its type. A field filter takes a list, except a date or date unit."""
    parameter_type = str(parameter.get("type") or "")
    family = parameter_type.split("/")[0]
    value = parameter.get("default")
    if value in (None, "", []):
        value = SAMPLE_VALUES.get(parameter_type, SAMPLE_VALUES.get(family, "x"))
    is_field_filter = (parameter.get("target") or [None])[0] == "dimension"
    if is_field_filter and family not in ("date", "temporal-unit") and not isinstance(value, list):
        return [value]
    return value


def filled_parameters(card):
    """Each parameter with a value, as a card run would send it."""
    return [{
        "id": parameter.get("id"),
        "type": parameter.get("type"),
        "target": parameter.get("target"),
        "value": filled_value(parameter),
    } for parameter in card.get("parameters") or [] if parameter.get("target")]


def compile_body(card):
    return {**(card.get("dataset_query") or {}), "parameters": filled_parameters(card), "pretty": False}


def check_card(client, card_id, copy_checks):
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
    if copy_checks and not card.get("archived"):
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


def new_failures(results, baseline):
    """{id: [checks]} failing here that did not fail in the baseline, including ids this run lost."""
    found = {}
    for card_id, failed in results.items():
        before = baseline.get(card_id, {})
        fresh = sorted(check for check in failed if check not in before and "detail" not in before)
        if fresh:
            found[card_id] = fresh
    for card_id in set(baseline) - set(results):
        found[card_id] = ["missing"]
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--baseline")
    parser.add_argument("--restored-copy", action="store_true")
    parser.add_argument("--no-save", action="store_true", help="with --restored-copy, leave out the no-op PUT")
    parser.add_argument("--accept", default="", help="comma-separated card ids whose new failures were read and accepted")
    parser.add_argument("--pause-seconds", type=float, default=0.1)
    args = parser.parse_args()
    url = urlsplit(args.base_url)
    host = url.hostname or ""
    if url.scheme != "https" and not (url.scheme == "http" and is_loopback(host)):
        sys.exit("--base-url must be https, or http on a loopback host")
    if args.restored_copy and not is_loopback(host):
        sys.exit("--restored-copy writes to cards and runs only on a loopback host")
    if not args.restored_copy and not is_live(host):
        sys.exit("the live key only goes to a live host; use --restored-copy for a copy")
    key_var = "MB_COPY_API_KEY" if args.restored_copy else "MB_LIVE_API_KEY"
    api_key = os.environ.get(key_var)
    if not api_key:
        sys.exit(f"set {key_var}")
    if api_key == os.environ.get("MB_LIVE_API_KEY") and args.restored_copy:
        sys.exit("MB_COPY_API_KEY is the live key; make one that exists only on the copy")

    client = Client(args.base_url, api_key)
    baseline = json.load(open(args.baseline)) if args.baseline else None
    ids = list_ids(client) | ({int(card_id) for card_id in baseline} if baseline else set())
    results = {}
    for card_id in sorted(ids):
        results[str(card_id)] = check_card(client, card_id, args.restored_copy and not args.no_save)
        time.sleep(args.pause_seconds)
    with open(args.out, "w") as handle:
        json.dump(results, handle, indent=0, sort_keys=True)

    failing = sorted((card_id for card_id, failed in results.items() if failed), key=int)
    print(f"cards checked: {len(results)}; failing: {len(failing)}")
    for card_id in failing:
        print(card_id, json.dumps(results[card_id]))
    if baseline is None:
        return 0
    already = sum(1 for failed in baseline.values() if failed)
    print(f"already failing in the baseline, not counted: {already}")
    accepted = {card_id.strip() for card_id in args.accept.split(",") if card_id.strip()}
    found = new_failures(results, baseline)
    open_ids = sorted(set(found) - accepted, key=int)
    print(f"new failures: {len(found)}; accepted: {len(set(found) & accepted)}; open: {len(open_ids)}")
    for card_id in sorted(found, key=int):
        print(card_id, ",".join(found[card_id]), "accepted" if card_id in accepted else "OPEN")
    return 1 if open_ids else 0


if __name__ == "__main__":
    sys.exit(main())
