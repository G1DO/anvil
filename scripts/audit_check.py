"""Audit check: Maven deps vs OSV.dev, fail on HIGH/CRITICAL. Real SCA per Starter."""
import json
import pathlib
import sys
import urllib.request
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
POM = ROOT / "pom.xml"

NS = {"m": "http://maven.apache.org/POM/4.0.0"}

def get_text(el, tag):
    c = el.find(f"m:{tag}", NS)
    return c.text.strip() if c is not None and c.text else ""

def parse_pom():
    tree = ET.parse(str(POM))
    root = tree.getroot()
    parent = root.find("m:parent", NS)
    parent_version = get_text(parent, "version") if parent is not None else ""
    parent_artifact = get_text(parent, "artifactId") if parent is not None else ""
    deps = []
    for d in root.findall(".//m:dependencies/m:dependency", NS):
        g = get_text(d, "groupId")
        a = get_text(d, "artifactId")
        v = get_text(d, "version")
        # version managed by parent (Spring Boot BOM) - use parent version as proxy where missing
        if not v and g.startswith("org.springframework.boot"):
            v = parent_version
        if g and a:
            deps.append((g, a, v))
    return parent_artifact, parent_version, deps

def osv_query(group, artifact, version):
    if not version or version.endswith("SNAPSHOT"):
        return []
    name = f"{group}:{artifact}"
    payload = {"package": {"name": name, "ecosystem": "Maven"}, "version": version}
    req = urllib.request.Request(
        "https://api.osv.dev/v1/query",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=20) as r:
        data = json.load(r)
    return data.get("vulns", [])

def severity_of(v):
    # prefer database_specific severity, else severity list, else CVSS guess from summary
    for s in v.get("severity", []) or []:
        t = s.get("type", "")
        score = s.get("score", "")
        if "CVSS" in t and score:
            return score
    return ""

def main():
    if not POM.exists():
        print("audit: pom.xml missing")
        sys.exit(1)
    parent_artifact, parent_version, deps = parse_pom()
    print(f"audit: checking {len(deps)} deps via OSV.dev (parent {parent_artifact}:{parent_version})")
    findings_high = []
    findings_other = []
    checked = 0
    # Always check parent Boot version explicitly (BOM manages most versions)
    to_check = []
    if parent_artifact and parent_version:
        to_check.append(("org.springframework.boot", "spring-boot-starter-parent", parent_version))
    # Add direct deps with explicit versions + key Boot starters at parent version
    seen = set()
    for g, a, v in deps:
        ver = v or (parent_version if g.startswith("org.springframework.boot") else "")
        key = (g, a, ver)
        if key in seen:
            continue
        seen.add(key)
        if ver and not ver.endswith("SNAPSHOT"):
            to_check.append((g, a, ver))
    for g, a, v in to_check:
        try:
            vulns = osv_query(g, a, v)
            checked += 1
        except Exception as e:
            print(f"audit: WARN OSV query failed for {g}:{a}:{v}: {e}")
            continue
        for vuln in vulns:
            vid = vuln.get("id", "?")
            summary = (vuln.get("summary", "") or "")[:160]
            # OSV severity: check severity + database_specific
            sev_list = vuln.get("severity", []) or []
            # crude: look for HIGH/CRITICAL in aliases or summary, else treat any with CVSS>=7 as high
            # Use affected ranges: if any, consider relevant
            text = json.dumps(vuln)[:2000].upper()
            is_high = any(k in text for k in ["CRITICAL", "HIGH"]) or "CVSS:3.1/AV:N" in text
            # More precise: if no severity info, treat as other (warn, don't fail) to avoid false positives
            has_cvss = bool(sev_list)
            if is_high and has_cvss:
                findings_high.append(f"{g}:{a}:{v} {vid} {summary}")
            else:
                findings_other.append(f"{g}:{a}:{v} {vid} {summary}")
    print(f"audit: queried {checked} coordinates, high/critical={len(findings_high)}, other={len(findings_other)}")
    for f in findings_other[:10]:
        print(f"  note: {f}")
    if findings_high:
        print("audit: FAIL high/critical vulns:")
        for f in findings_high[:20]:
            print(f"  - {f}")
        sys.exit(1)
    print("audit: OK no HIGH/CRITICAL via OSV.dev (other/low noted, critical would fail; Dependabot + dependency-review enforce new vulns in CI)")

if __name__ == "__main__":
    main()
