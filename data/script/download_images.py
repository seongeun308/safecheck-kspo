"""data.csv의 이미지URL + 이미지파일이름 컬럼으로 images/ 폴더에 이미지를 내려받는다.

이미 받은 파일은 건너뛰므로, 재실행하면 누락분만 보충된다.
사용법: python3 download_images.py
"""
import csv, os, ssl, urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # data/
CSV = os.path.join(BASE, "defects.csv")
OUT = os.path.join(BASE, "images")

# data.csv는 CP949, data_utf8.csv는 UTF-8 — 어느 쪽이 넘어와도 읽히게 한다.
def read_rows(path):
    for enc in ("utf-8-sig", "cp949"):
        try:
            with open(path, encoding=enc) as f:
                return list(csv.DictReader(f))
        except UnicodeDecodeError:
            continue
    raise SystemExit(f"인코딩을 판별할 수 없음: {path}")

# 서버가 http -> https로 301 리다이렉트하는데 인증서 검증이 실패해서 검증을 끈다.
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

def fetch(row):
    name = row["이미지파일이름"].strip()
    url = row["이미지URL"].strip().rstrip("/") + "/" + name
    path = os.path.join(OUT, name)
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return ("skip", name, "")
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=30, context=ctx) as resp:
            data = resp.read()
        if not data:
            return ("fail", name, "empty body")
        with open(path, "wb") as f:
            f.write(data)
        return ("ok", name, len(data))
    except Exception as e:
        return ("fail", name, repr(e))

def main():
    os.makedirs(OUT, exist_ok=True)
    rows = read_rows(CSV)
    with ThreadPoolExecutor(max_workers=8) as ex:
        results = list(ex.map(fetch, rows))

    counts = {k: sum(1 for r in results if r[0] == k) for k in ("ok", "skip", "fail")}
    print(f"total={len(results)} ok={counts['ok']} skipped={counts['skip']} failed={counts['fail']}")
    for status, name, detail in results:
        if status == "fail":
            print("FAIL:", name, detail)

if __name__ == "__main__":
    main()
