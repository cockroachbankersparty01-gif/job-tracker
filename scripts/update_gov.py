"""Daily update of data.json (JOB Alert, government jobs).

Reads the FreeJobAlert listing pages, adds new recruitments with their official
notification and apply links, refreshes admit cards / answer keys / results, and
removes items that have closed. Hand-curated entries already in data.json are kept.
"""
import re
import sys

from common import TODAY, get, load, norm, parse_date, save

DATA = sys.argv[1] if len(sys.argv) > 1 else "data.json"
FJA = "https://www.freejobalert.com/"
LISTINGS = ["latest-notifications/", "government-jobs/", "bank-jobs/", "railway-jobs/",
            "police-defence-jobs/", "state-government-jobs/", "teaching-faculty-jobs/",
            "engineering-jobs/", "medical-hospital-jobs/"]
MAX_NEW_ARTICLES = 120      # article pages opened per run (new jobs)
MAX_BACKFILL = 40           # existing jobs still missing an exact notice link
STATES = ["Andaman and Nicobar", "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chandigarh",
          "Chhattisgarh", "Dadra and Nagar Haveli", "Daman and Diu", "Delhi", "Goa", "Gujarat", "Haryana",
          "Himachal Pradesh", "Jammu and Kashmir", "Jharkhand", "Karnataka", "Kerala", "Ladakh",
          "Lakshadweep", "Madhya Pradesh", "Maharashtra", "Manipur", "Meghalaya", "Mizoram", "Nagaland",
          "Odisha", "Puducherry", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana", "Tripura",
          "Uttar Pradesh", "Uttarakhand", "West Bengal"]
IMPORTANT = re.compile(r"\b(psc|ssb|sssb|ssc|upsc|rrb|rrc|ibps|sbi|rbi|nabard|high court|police|bank|railway|"
                       r"army|navy|air force|iaf|bsf|crpf|cisf|itbp|ssb|assam rifles|coast guard|kvs|nvs|"
                       r"ntpc|ongc|iocl|bhel|gail|sail|coal|nhpc|powergrid|drdo|isro|aiims|esic|lic|"
                       r"recruitment board|selection board|commission)\b", re.I)
STRONG = re.compile(r"\b(psc|ssb|sssb|ssc|upsc|rrb|rrc|ibps|high court|police|recruitment board|selection board)\b", re.I)
SKIP = re.compile(r"research|fellow|\bjrf\b|\bsrf\b|project (assistant|associate|scientist)|consultant|"
                  r"retired|professor|young professional|post doctoral|vice.chancellor|principal\b|"
                  r"member\b|chairperson|director\b|registrar|guest|visiting", re.I)


def heading_state(table):
    h = table.find_previous(["h2", "h3", "h4"])
    t = h.get_text(" ", strip=True) if h else ""
    for s in STATES:
        if t.lower().startswith(s.lower()):
            return s
    if t.lower().startswith("jammu"):
        return "Jammu and Kashmir"
    return "All India"


def rows(soup, need="Last Date"):
    """Yield (cells, article_url, state) for every data row of tables whose header has `need`."""
    for t in soup.find_all("table"):
        trs = t.find_all("tr")
        if not trs:
            continue
        hdr = [c.get_text(" ", strip=True) for c in trs[0].find_all(["th", "td"])]
        if need not in " ".join(hdr):
            continue
        st = heading_state(t)
        for tr in trs[1:]:
            cells = [c.get_text(" ", strip=True) for c in tr.find_all(["td", "th"])]
            a = next((x["href"] for x in tr.find_all("a", href=True) if "/articles/" in x["href"]), None)
            if a and len(cells) >= len(hdr) - 1:
                yield dict(zip(hdr, cells)), a, st


def quals(text):
    t = text.lower()
    def has(words):
        return re.search(r"(?<![a-z])(?:" + words + r")(?![a-z])", t)
    q = set()
    if has(r"10th|matric(?:ulation)?|sslc|class 10|8th"): q.add("10")
    if has(r"12th|intermediate|hsc|puc|class 12"): q.add("12")
    if has(r"iti"): q.add("iti")
    if has(r"diploma"): q.add("diploma")
    if has(r"b\.?tech|b\.?e|engineering|m\.?tech|m\.?e"): q.add("engg")
    if has(r"b\.?ed|d\.?el\.?ed|tet|ctet"): q.add("bed")
    if has(r"mbbs|nursing|gnm|anm|b\.?pharm|d\.?pharm|pharmacy|bds|bams|bhms|bums|md|ms/md|ms|dnb|mlt|dmlt|bmlt|bpt|b\.?sc nursing"): q.add("medical")
    if has(r"post graduate|pg|m\.?a|m\.?sc|m\.?com|mba|pgdm|mca|llm|ph\.?d|m\.?phil"): q.add("pg")
    if has(r"any graduate|graduate|degree|bachelors?|b\.?a|b\.?sc|b\.?com|bca|bba|llb|ca|cma|icwa|cs"): q.add("grad")
    return sorted(q) or ["grad"]


def sectors(board, title, heading):
    t = f"{board} {title}".lower()
    def has(words):  # whole words only, so "Karbi" never matches "rbi"
        return re.search(r"\b(?:" + words + r")\b", t)
    s = set()
    if has(r"bank|banking|ibps|sbi|rbi|nabard|sidbi|exim|nhb|financial institutions?"): s.add("bank")
    if has(r"railways?|rrb|rrc|metro|rail"): s.add("railway")
    if has(r"ssc|staff selection commission"): s.add("ssc")
    if has(r"upsc"): s.add("upsc")
    if has(r"police|constable|home guards?|fire ?(?:fighter|man|men|service)|army|navy|air force|iaf|bsf|crpf|"
           r"cisf|itbp|ssb|assam rifles|coast guard|defence|territorial army|agniveer|sub inspector|si"): s.add("police")
    if has(r"teachers?|tgt|pgt|prt|lecturers?|kvs|nvs|schools?|teaching|faculty|professors?"): s.add("teaching")
    if has(r"nurs(?:e|es|ing)|medical|hospital|aiims|esic|health|nhm|pharmacists?|doctors?|residents?|"
           r"paramedics?|lab(?:oratory)? technicians?|mbbs"): s.add("medical")
    if has(r"ltd|limited|corporation|psu|ongc|ntpc|bhel|iocl|gail|sail|coal india|ncl|wcl|secl|mcl|powergrid|"
           r"nlc|bel|hal|bdl|beml|shipyard|port authority|apprentices?|nsic|recpdcl|rites|ircon|wapcos|nbcc"): s.add("psu")
    if has(r"psc|sssb|osssc|ossc|ssb|high court|district|collectorate|vidhan sabha|panchayat|anganwadi|dlsa|"
           r"civil court|judiciary") or heading != "All India":
        s.add("state")
    return sorted(s) or ["psu"]


def posts_of(title):
    m = re.search(r"(\d[\d,]*)\s*Posts?", title, re.I)
    return int(m.group(1).replace(",", "")) if m else 0


SOCIAL = re.compile(r"whatsapp|t\.me|telegram|play\.google|freejobalert\.com|facebook|instagram|youtube|twitter|x\.com")


def article_links(url):
    """Return (notice, apply) from an article's Important Links table."""
    soup = get(url)
    if not soup:
        return None, None
    links = []
    for h in soup.find_all(["h2", "h3"]):
        if "important links" in h.get_text(" ", strip=True).lower():
            t = h.find_next("table")
            if t:
                links = [(a.get_text(" ", strip=True), a["href"]) for a in t.find_all("a", href=True)]
            break
    links = [(t, h) for t, h in links if h.startswith("http") and not SOCIAL.search(h) or "img2.freejobalert" in h]
    notice = next((h for t, h in links if re.search(r"notification|notice|advertisement|advt|circular|pdf", t, re.I)
                   or h.lower().endswith(".pdf")), None)
    apply = next((h for t, h in links if h != notice and re.search(r"apply|application|registration|portal|login|form", t, re.I)), None)
    apply = apply or next((h for t, h in links if h != notice), None)
    return notice, apply


def main():
    d = load(DATA)
    jobs = d["jobs"]
    known = {j.get("fja") or j.get("noticePage") for j in jobs} - {None}
    known_keys = {(norm(j["org"])[:12], j["last"]) for j in jobs}

    # 1) new recruitments
    seen, new = set(), []
    for path in LISTINGS:
        soup = get(FJA + path)
        if not soup:
            continue
        for c, art, st in rows(soup):
            if art in seen or art in known:
                continue
            seen.add(art)
            last = parse_date(c.get("Last Date"))
            if not last or last < TODAY:
                continue
            board = c.get("Recruitment Board", "").strip()
            title = c.get("Exam / Post Name", "").strip()
            posts = posts_of(title)
            if SKIP.search(title) and posts < 25:
                continue
            if posts < 10 and not (STRONG.search(board) and posts != 1):
                continue
            if (norm(board)[:12], last.isoformat()) in known_keys:
                continue
            clean = re.sub(r"\s*[–-]\s*[\d,]+\s*Posts?\s*$", "", title).strip()
            start = parse_date(c.get("Post Date"))
            new.append({
                "t": f"{board} {clean}".strip(), "org": board, "posts": posts or 1,
                "q": quals(c.get("Qualification", "")), "s": sectors(board, title, st),
                "state": st, "loc": st, "start": start.isoformat() if start else "", "startNotice": 1,
                "last": last.isoformat(), "url": art, "src": 1, "fja": art, "noticePage": art, "auto": 1,
            })
    print(f"new recruitments found: {len(new)}")
    for j in new[:MAX_NEW_ARTICLES]:
        notice, apply = article_links(j["fja"])
        if notice:
            j["notice"] = notice
            j.pop("noticePage", None)
        if apply:
            j["apply"] = apply
            j["url"] = apply
            j.pop("src", None)
    jobs.extend(new)

    # 2) existing jobs still without an exact notice link
    todo = sorted([j for j in jobs if not j.get("notice") and (j.get("noticePage") or j.get("fja"))
                   and j["last"] >= TODAY.isoformat()], key=lambda j: j["last"])[:MAX_BACKFILL]
    for j in todo:
        notice, apply = article_links(j.get("noticePage") or j["fja"])
        if notice:
            j["notice"] = notice
            j.pop("noticePage", None)
        if apply and not j.get("apply"):
            j["apply"] = apply

    # 3) drop jobs closed more than 7 days ago
    cutoff = (TODAY.toordinal() - 7)
    d["jobs"] = [j for j in jobs if (parse_date(j["last"]) or TODAY).toordinal() >= cutoff]

    # 4) admit cards, answer keys, results
    for key, path, need, label, keep_days in [
        ("admit", "admit-card/", "Post/Exam Name", "Admit Card", 14),
        ("keys", "answer-key/", "Exam Name", "Answer Key", 14),
        ("results", "exam-results/", "Post/Exam Name", "Result", 30)]:
        soup = get(FJA + path)
        items = d.get(key) or []
        have = {i.get("fja") for i in items} | {norm(i["t"]) for i in items}
        added = 0
        if soup:
            for c, art, st in rows(soup, need=need):
                posted = parse_date(c.get("Post Date"))
                if not posted or (TODAY - posted).days > 3:
                    continue
                board = c.get("Recruitment Board", "").strip()
                exam = (c.get("Post/Exam Name") or c.get("Exam Name") or "").strip()
                if not IMPORTANT.search(f"{board} {exam}") or art in have:
                    continue
                t = f"{board} {exam} {label}".strip()
                if len(t) > 90:
                    t = f"{board} {exam[:60].rsplit(' ', 1)[0]}… {label}"
                if norm(t) in have:
                    continue
                item = {"t": t, "org": board, "s": sectors(board, exam, st), "q": ["10"], "url": art, "fja": art}
                if key == "results":
                    item["date"] = posted.isoformat()
                else:
                    item["tag"] = "Out"
                    item["date"] = posted.isoformat()
                items.insert(0, item)
                have.add(art)
                added += 1
                if added >= 25:
                    break
        # keep recent ones only (hand-curated items without a date are kept)
        d[key] = [i for i in items if not i.get("date") or
                  (TODAY - (parse_date(i["date"]) or TODAY)).days <= keep_days][:80]
        print(f"{key}: +{added}")

    # 5) calendar: drop entries finished more than 14 days ago
    def cal_end(e):
        ds = e.get("dates") or [e.get("end") or e.get("start")]
        return max(parse_date(x) or TODAY for x in ds)
    d["calendar"] = [e for e in d.get("calendar", []) if (TODAY - cal_end(e)).days <= 14]

    d["updated"] = TODAY.isoformat()
    save(DATA, d)
    print(f"jobs now: {len(d['jobs'])}, with exact notice: {sum(1 for j in d['jobs'] if j.get('notice'))}")


if __name__ == "__main__":
    main()
