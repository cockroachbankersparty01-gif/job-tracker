"""Daily update of pro/data.json (PRO Job Alert, private jobs).

Reads the FreshersHunt live-drives board, where every card links straight to the
company's own job posting, adds new openings and removes stale ones.
"""
import re
import sys

from common import TODAY, get, load, parse_date, save

DATA = sys.argv[1] if len(sys.argv) > 1 else "pro/data.json"
SOURCES = ["https://freshershunt.in/off-campus-drive-jobs/"]
CITIES = ["Bengaluru", "Bangalore", "Hyderabad", "Pune", "Chennai", "Mumbai", "Noida", "Gurugram", "Gurgaon",
          "Delhi", "Kolkata", "Ahmedabad", "Kochi", "Coimbatore", "Jaipur", "Indore", "Mangalore", "Mysore",
          "Thiruvananthapuram", "Chandigarh", "Remote"]


def classify(role, company, meta):
    t = f"{role} {company} {meta}".lower()
    def has(words):  # match at the start of a word only
        return re.search(r"(?<![a-z])(?:" + words + r")", t)
    kind = "apprentice" if has(r"\bapprentice") else "intern" if re.search(r"\bintern(ship)?\b", role.lower()) else "job"
    s = set()
    if re.search(r"software|developer|engineer.*(software|cloud|devops|qa|test)|sde|full stack|java|python|web|"
                 r"programmer|quality engineer|test engineer|cloud|devops|salesforce|servicenow|network|noc|database|"
                 r"cyber|security|technical support|desktop|asic|vlsi", t): s.add("it")
    if has(r"data|analytics|machine learning|\bml\b|\bai\b|artificial|scientist"): s.add("data")
    if re.search(r"mechanical|civil|electrical|production|manufactur|plant|design engineer|cad|get\b|"
                 r"graduate engineer|field|maintenance|chemical|mechanical engineering|electrical engineering|quality.*(plant|ptm)|chemist", t): s.add("core")
    if has(r"analyst|consult|associate.*(ops|operations)|process"): s.add("consult")
    if has(r"financ|account|audit|tax|risk|credit|bank|loan|treasury|\bca\b|record to report"): s.add("finance")
    if has(r"sales|business development|relationship|marketing|bd\b"): s.add("sales")
    if has(r"pharma|clinical|medical|health|formulation|research associate"): s.add("health")
    if has(r"customer|voice|support|bpo|service executive|front office|guest"): s.add("support")
    if has(r"design|ui|ux|motion|graphic|draftsman|revit|bim"): s.add("design")
    if not s:
        s.add("it")
    q = set()
    if s & {"it", "data"}: q |= {"cse", "ece", "mca"}
    if "core" in s: q |= {"mech", "civil", "core", "ece"}
    if s & {"finance", "consult"}: q |= {"com", "mba"}
    if "sales" in s: q |= {"mba", "any"}
    if "support" in s: q.add("any")
    if "health" in s: q |= {"pharm", "sci"}
    if "design" in s and "draftsman" in t: q.add("arch")
    m = re.search(r"(\d+)\s*[-–]\s*(\d+)\s*(?:yrs|years)", meta, re.I)
    exp = [int(m.group(1)), int(m.group(2))] if m else [0, 0] if has(r"fresher|entry|0\s*years|intern") else [0, 2]
    loc = next((c for c in CITIES if c.lower() in meta.lower()), meta.split("·")[1].strip() if "·" in meta else "India")
    loc = {"Bangalore": "Bengaluru", "Gurgaon": "Gurugram"}.get(loc, loc)
    return kind, sorted(s), sorted(q) or ["any"], exp, loc


def main():
    d = load(DATA)
    jobs = d["jobs"]
    known = {j["url"] for j in jobs}
    added = 0
    for src in SOURCES:
        soup = get(src)
        if not soup:
            continue
        for a in soup.find_all("a", href=True):
            href = a["href"]
            if not href.startswith("http") or "freshershunt" in href or not re.search(r"apply", a.get_text(), re.I):
                continue
            card = a.parent
            ps = [p.get_text(" ", strip=True) for p in card.find_all("p")]
            if len(ps) < 2 or href in known:
                continue
            role, meta = ps[0], ps[1]
            company = meta.split("·")[0].strip()
            kind, s, q, exp, loc = classify(role, company, meta)
            jobs.append({"t": role, "co": company, "loc": loc, "q": q, "s": s, "exp": exp, "kind": kind,
                         "posted": TODAY.isoformat(), "last": "", "url": href, "src": 0, "sal": "",
                         "batch": "", "note": "", "auto": 1})
            known.add(href)
            added += 1
    # remove closed listings: last date passed, or no deadline and posted more than 45 days ago
    keep = []
    for j in jobs:
        last = parse_date(j.get("last"))
        posted = parse_date(j.get("posted")) or TODAY
        if last and last < TODAY:
            continue
        if not last and (TODAY - posted).days > 45:
            continue
        keep.append(j)
    d["jobs"] = keep
    d["calendar"] = [e for e in d.get("calendar", [])
                     if (TODAY - max(parse_date(x) or TODAY for x in (e.get("dates") or [e.get("end") or e.get("start")]))).days <= 7]
    d["updated"] = TODAY.isoformat()
    save(DATA, d, compact=False)
    print(f"PRO: +{added} new, {len(keep)} total")


if __name__ == "__main__":
    main()
