"""Shared helpers for the daily job-data updaters (run by GitHub Actions, no AI involved)."""
import datetime as dt
import json
import re
import time

import requests
from bs4 import BeautifulSoup

IST = dt.timezone(dt.timedelta(hours=5, minutes=30))
TODAY = dt.datetime.now(IST).date()
UA = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/126 Mobile Safari/537.36 JOBAlert-updater")

_session = requests.Session()
_session.headers["User-Agent"] = UA


def get(url, tries=3):
    """Download a page; returns BeautifulSoup or None."""
    for n in range(tries):
        try:
            r = _session.get(url, timeout=40)
            if r.status_code == 200:
                time.sleep(0.8)  # be polite to the source sites
                return BeautifulSoup(r.text, "html.parser")
            print(f"  {r.status_code} {url}")
        except requests.RequestException as e:
            print(f"  error {e} {url}")
        time.sleep(3 * (n + 1))
    return None


def parse_date(s):
    s = (s or "").strip()
    for fmt in ("%d-%m-%Y", "%d/%m/%Y", "%d.%m.%Y", "%Y-%m-%d"):
        try:
            return dt.datetime.strptime(s, fmt).date()
        except ValueError:
            pass
    return None


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def save(path, data, compact=True):
    with open(path, "w", encoding="utf-8") as f:
        if compact:
            json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        else:
            json.dump(data, f, ensure_ascii=False, indent=1)


def norm(s):
    return re.sub(r"[^a-z0-9]+", " ", (s or "").lower()).strip()
