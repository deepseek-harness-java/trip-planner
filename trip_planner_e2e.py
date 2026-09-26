#!/usr/bin/env python3
"""trip-planner E2E：通过业务应用 SSE 代理调用 DSH Agent，验证工具全链路。"""
import json, subprocess, sys

AGENT = "trip-city-assistant"
URL = "http://127.0.0.1:18081/api/assistant/stream"

CASES = [
    ("T1 景点信息", "目的地有哪些景点信息？简洁回答", ["茶马古道", "玻璃栈道"]),
    ("T2 行程规划", "帮我推荐一个一日游的行程，简洁回答", ["茶马古道", "云海观景台"]),
    ("T3 亲子路线", "带老人和小孩出游，帮我推荐一条轻松的路线，简洁回答", ["花海", "索道"]),
    ("T4 拥挤避峰", "哪些景点现在人多？怎么避开人流？简洁回答", ["玻璃栈道", "茶马古道"]),
    ("T5 半日方案", "只有半天时间，帮我规划一个精华路线，简洁回答", ["索道", "半日"]),
]

def ask(message, timeout=170):
    payload = json.dumps({"message": message}, ensure_ascii=False)
    try:
        out = subprocess.run(
            ["curl", "-s", "--noproxy", "*", "-N", "-X", "POST", URL,
             "-H", "Content-Type: application/json", "-d", payload,
             "--max-time", str(timeout)],
            capture_output=True, text=True, timeout=timeout + 10).stdout
    except Exception as e:
        return "", f"curl 异常: {e}"
    text = []
    ev = ""
    for line in out.splitlines():
        line = line.rstrip("\r")
        if line.startswith("event:"):
            ev = line[6:].strip()
        elif line.startswith("data:"):
            s = line[5:].strip()
            if not s or s == "[DONE]" or ev != "chunk":
                continue
            try:
                j = json.loads(s)
                c = j.get("content", "")
                if c:
                    text.append(c)
            except Exception:
                pass
            ev = ""
    return "".join(text), out

def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    cases = CASES if not only else [c for c in CASES if c[0].startswith(only)]
    passed, failed = 0, []
    for name, q, keys in cases:
        reply, raw = ask(q)
        ok = all(k in reply for k in keys)
        print(f"[{'PASS' if ok else 'FAIL'}] {name}\n  Q: {q}\n  A: {reply[:200]}")
        if ok:
            passed += 1
        else:
            failed.append(name)
            if not reply:
                print(f"  raw 首行: {raw.splitlines()[:3] if raw else '(空)'}")
    print(f"\n===== trip-planner E2E: {passed}/{len(cases)} PASS =====")

if __name__ == "__main__":
    main()
