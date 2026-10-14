#!/bin/bash
# Regenerates the AboutLibraries custom definitions for the Go side of the app (tsbridge.aar):
#   app/config/libraries/go-*.json   one entry per Go module that is linked into the AAR
#   app/config/licenses/go-*.json    one entry per distinct license text
# The AAR itself is gitignored, so the generated files are committed; run this after changing
# tsbridge/go.mod (or the Go toolchain) and commit the result. Needs go and python3.
# Fails if a module's license cannot be identified or is GPL/LGPL/AGPL/MPL.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/config"
mkdir -p "$OUT/libraries" "$OUT/licenses"
rm -f "$OUT"/libraries/go-*.json "$OUT"/licenses/go-*.json

cd "$ROOT/tsbridge"
LIST="$(mktemp)"; trap 'rm -f "$LIST"' EXIT
GOOS=android GOARCH=arm64 CGO_ENABLED=1 go list -deps \
  -f '{{with .Module}}{{.Path}} {{.Version}} {{.Dir}}{{end}}' ./... | sort -u | grep -v '^$' > "$LIST"
# Not reachable from ./... but part of the AAR: gomobile's generated bindings/runtime and the Go runtime.
echo "golang.org/x/mobile $(go list -m -f '{{.Version}} {{.Dir}}' golang.org/x/mobile)" >> "$LIST"
GOROOT_DIR="$(go env GOROOT)"
[ -f "$GOROOT_DIR/LICENSE" ] || GOROOT_DIR="$(dirname "$GOROOT_DIR")"   # Homebrew keeps LICENSE next to libexec
[ -f "$GOROOT_DIR/LICENSE" ] || { echo "gen-go-licenses: Go LICENSE not found" >&2; exit 1; }
echo "go-stdlib $(go env GOVERSION) $GOROOT_DIR" >> "$LIST"

python3 - "$LIST" "$OUT" <<'PY'
import hashlib, json, os, re, sys, glob
listfile, out = sys.argv[1], sys.argv[2]
main_module = "github.com/shibaken/androidterminal/tsbridge"
BAD = [("AGPL", r"GNU AFFERO GENERAL PUBLIC LICENSE"), ("LGPL", r"GNU LESSER GENERAL PUBLIC LICENSE|GNU LIBRARY GENERAL PUBLIC LICENSE"),
       ("GPL", r"GNU GENERAL PUBLIC LICENSE"), ("MPL", r"Mozilla Public License")]
def classify(t):
    for name, pat in BAD:
        if re.search(pat, t, re.I): return name
    if "Apache License" in t and "Version 2.0" in t: return "Apache-2.0"
    if "Permission is hereby granted, free of charge" in t: return "MIT"
    if "Redistribution and use in source and binary forms" in t:
        if re.search(r"Neither the name", t): return "BSD-3-Clause"
        return "BSD-2-Clause"
    if "Permission to use, copy, modify, and/or distribute" in t: return "ISC"
    return None
NAMES = {"Apache-2.0": "Apache License 2.0", "MIT": "MIT License", "BSD-3-Clause": "BSD 3-Clause License",
         "BSD-2-Clause": "BSD 2-Clause License", "ISC": "ISC License"}
pat = re.compile(r"^(LICEN[CS]E|COPYING|NOTICE|PATENTS)([-._].*)?$", re.I)
licenses, libs, errors = {}, [], []
for line in open(listfile):
    path, version, d = line.strip().split(" ", 2)
    if path == main_module: continue
    files = sorted(f for f in os.listdir(d) if pat.match(f) and os.path.isfile(os.path.join(d, f))
                   and not f.upper().startswith("LICENSE-3RD") and not f.endswith((".go", ".sh", ".py")))
    lic = [f for f in files if not re.match(r"^(NOTICE|PATENTS)", f, re.I)]
    if not lic: errors.append(f"{path}: no LICENSE file in {d}"); continue
    kinds = set()
    for f in lic:
        k = classify(open(os.path.join(d, f), encoding="utf-8", errors="replace").read())
        if k is None: errors.append(f"{path}: cannot identify license in {f}")
        elif k in ("GPL", "LGPL", "AGPL", "MPL"): errors.append(f"{path}: FORBIDDEN license {k} in {f}")
        else: kinds.add(k)
    if not kinds: continue
    text = "\n\n".join(open(os.path.join(d, f), encoding="utf-8", errors="replace").read().strip() for f in files) + "\n"
    spdx = "+".join(sorted(kinds))
    h = "go-" + spdx.lower().replace("+", "_") + "-" + hashlib.sha256(text.encode()).hexdigest()[:10]
    licenses[h] = {"hash": h, "name": " + ".join(NAMES[k] for k in sorted(kinds)), "content": text,
                   "url": "https://pkg.go.dev/" + path + "?tab=licenses" if path != "go-stdlib" else "https://go.dev/LICENSE"}
    if path == "go-stdlib":
        name, site, desc = "Go standard library", "https://go.dev", "Go runtime and standard library (linked into tsbridge)"
    elif path == "golang.org/x/mobile":
        name, site, desc = path, "https://pkg.go.dev/golang.org/x/mobile", "gomobile bindings (generated code and runtime in tsbridge)"
    else:
        name, site, desc = path, "https://pkg.go.dev/" + path, "Go module linked into tsbridge"
    libs.append({"uniqueId": "go:" + path, "name": name, "artifactVersion": version, "description": desc,
                 "website": site, "developers": [], "licenses": [h], "openSource": True, "tag": "go"})
if errors:
    print("gen-go-licenses: FAILED", *errors, sep="\n  ", file=sys.stderr); sys.exit(1)
for h, l in licenses.items():
    json.dump(l, open(f"{out}/licenses/{h}.json", "w"), ensure_ascii=False, indent=2); open(f"{out}/licenses/{h}.json", "a").write("\n")
for l in libs:
    fn = "go-" + re.sub(r"[^A-Za-z0-9._-]", "_", l["uniqueId"][3:]) + ".json"
    json.dump(l, open(f"{out}/libraries/{fn}", "w"), ensure_ascii=False, indent=2); open(f"{out}/libraries/{fn}", "a").write("\n")
print(f"gen-go-licenses: {len(libs)} modules, {len(licenses)} distinct license texts")
for l in sorted(libs, key=lambda x: x["uniqueId"]): print("  ", l["uniqueId"], l["artifactVersion"], l["licenses"][0].split("-")[1])
PY
