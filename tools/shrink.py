"""Shorten the top-level names of a minified Forge face (esbuild keeps them long).
Usage: python3 shrink.py in.min.js out.min.js
Hooks, settings and anything the watch looks up by name stay as they are."""
import re, sys, itertools

KEEP = {"subscribe", "SOUND", "HOLD", "IDLE", "init", "enter", "exit", "button", "ble_rx", "ble_connect",
        "ble_disconnect", "a_short", "a_long", "l_short", "l_long", "tilt", "tap", "clock_minute",
        "clock_hour", "clock_second"}

src = open(sys.argv[1]).read()

def tokens(s):
    """yield (kind, text): kind is 'id', 'str' or 'other'"""
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if c in "'\"":
            j = i + 1
            while s[j] != c:
                j += 2 if s[j] == "\\" else 1
            yield "str", s[i:j + 1]; i = j + 1
        elif c.isalpha() or c in "_$":
            m = re.match(r"[A-Za-z_$][\w$]*", s[i:]); yield "id", m.group(0); i += len(m.group(0))
        elif c.isdigit():
            m = re.match(r"[0-9.eExXa-fA-F]+", s[i:]); yield "other", m.group(0); i += len(m.group(0))
        else:
            yield "other", c; i += 1

toks = list(tokens(src))
# top-level declarations: depth-0 "var a=..,b=.." and "function name"
decl, depth, prev_id = [], 0, None
k = 0
while k < len(toks):
    kind, t = toks[k]
    if kind == "other" and t in "{([": depth += 1
    elif kind == "other" and t in "})]": depth -= 1
    elif kind == "id" and depth == 0 and t in ("var", "const", "function"):
        if t == "function":
            j = k + 1
            while toks[j][0] != "id": j += 1
            decl.append(toks[j][1])
        else:
            # names in a var list at depth 0: identifier right after var or after a depth-0 comma
            j, d = k + 1, 0
            expect = True
            while j < len(toks):
                kk, tt = toks[j]
                if kk == "other" and tt in "{([": d += 1
                elif kk == "other" and tt in "})]": d -= 1
                elif kk == "other" and tt == ";" and d == 0: break
                if kk == "id" and expect and d == 0: decl.append(tt); expect = False
                elif kk == "other" and tt == "," and d == 0: expect = True
                elif kk == "id" and d == 0 and tt == "function" : break
                j += 1
    k += 1

used = {t for kind, t in toks if kind == "id"}
names = (a + b for a, b in itertools.chain(((x, "") for x in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_$"),
                                           ((x, y) for x in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_$"
                                            for y in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_$0123456789")))
reserved = {"do", "if", "in", "for", "new", "try", "var", "case", "else", "this", "void", "with", "NaN"}
# most used names get the shortest replacements
count = {}
for kind, t in toks:
    if kind == "id": count[t] = count.get(t, 0) + 1
mapping = {}
for name in sorted(set(decl) - KEEP, key=lambda x: -count[x]):
    for cand in names:
        if cand not in used and cand not in reserved:
            if len(cand) < len(name): mapping[name] = cand
            break

out, prev = [], None
for kind, t in toks:
    if kind == "id" and t in mapping and prev != ".":
        t = mapping[t]
    out.append(t)
    if kind != "other" or t.strip(): prev = t if kind == "other" else None
open(sys.argv[2], "w").write("".join(out))
print("renamed", len(mapping), "names:", len(src), "->", len("".join(out)), "bytes")
