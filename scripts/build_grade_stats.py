"""
국민체육진흥공단 전국체육시설 안전점검 정보(15107773)의 '체육시설안전정보'
오퍼레이션을 전량 수집해 업종별 종합등급 분포를 집계한다.

서비스는 실행 중에 이 API를 호출하지 않는다. 이 스크립트가 만든 집계 파일만
읽는다. 개별 시설 정보(시설명·주소·좌표)는 결과 파일에 남기지 않는다.

사용법
    # 1) API에서 새로 수집 (원본은 data/raw/에 저장)
    export DATA_GO_KR_KEY='디코딩된 인증키'
    export KSPO_SAFETY_URL='체육시설안전정보 오퍼레이션 요청 주소'
    python3 scripts/build_grade_stats.py

    # 2) 이미 받은 원본으로 다시 집계 (인증키 불필요)
    python3 scripts/build_grade_stats.py --from-raw data/raw/facility_safety_20260922.json

인증키는 공공데이터포털의 '디코딩' 키를 쓴다. requests가 주소 인코딩을 하므로
인코딩된 키를 넣으면 이중 인코딩되어 인증 오류가 난다.
"""

import argparse
import collections
import datetime as dt
import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RAW_DIR = ROOT / "data" / "raw"
OUT_PATH = ROOT / "src" / "main" / "resources" / "data" / "grade_stats.json"

PAGE_SIZE = 1000
MAX_PAGES = 200            # 안전장치. 약 99페이지 예상
SLEEP_SEC = 0.2
RETRIES = 3

ACTIVE_STATUS = "정상운영"

# 공단 등급 체계. 수집한 데이터의 코드-명칭 쌍이 이와 다르면 중단한다.
EXPECTED_GRADES = {"01": "양호", "02": "주의", "03": "사용중지"}


# ----------------------------------------------------------------------
# 수집
# ----------------------------------------------------------------------

def load_dotenv(path: Path) -> None:
    """프로젝트 루트의 .env를 읽는다. 이미 설정된 환경변수가 있으면 그쪽을 우선한다."""
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("'\""))

def fetch_all() -> list[dict]:
    import requests

    load_dotenv(ROOT / ".env")
    key = os.environ.get("DATA_GO_KR_KEY")
    url = os.environ.get("KSPO_SAFETY_URL")
    if not key or not url:
        sys.exit("DATA_GO_KR_KEY, KSPO_SAFETY_URL 환경변수를 설정하세요.")

    rows: list[dict] = []
    total = None

    for page in range(1, MAX_PAGES + 1):
        body = _get_page(requests, url, key, page)

        if total is None:
            total = int(body.get("totalCount", 0))
            print(f"totalCount: {total:,}")

        items = _items(body)
        if not items:
            break
        rows.extend(items)
        print(f"  page {page:3d}: +{len(items):4d}  누적 {len(rows):,}")

        if total and len(rows) >= total:
            break
        time.sleep(SLEEP_SEC)
    else:
        print(f"경고: {MAX_PAGES}페이지에서 중단했습니다. 전량이 아닐 수 있습니다.")

    if total and len(rows) != total:
        print(f"경고: 수집 {len(rows):,}건이 totalCount {total:,}건과 다릅니다.")
    return rows


def _get_page(requests, url: str, key: str, page: int) -> dict:
    params = {
        "serviceKey": key,
        "pageNo": page,
        "numOfRows": PAGE_SIZE,
        "resultType": "json",
    }
    last_error = None
    for attempt in range(1, RETRIES + 1):
        try:
            resp = requests.get(url, params=params, timeout=30)
            resp.raise_for_status()
            data = resp.json()
            header = data["response"]["header"]
            if header.get("resultCode") != "00":
                raise RuntimeError(f"{header.get('resultCode')} {header.get('resultMsg')}")
            return data["response"]["body"]
        except Exception as e:  # noqa: BLE001 — 재시도 후 원인과 함께 중단
            last_error = e
            print(f"  page {page} 실패 ({attempt}/{RETRIES}): {e}")
            time.sleep(2 * attempt)
    sys.exit(f"page {page} 수집 실패: {last_error}")


def _items(body: dict) -> list[dict]:
    """결과가 한 건이면 리스트가 아니라 객체로 오는 경우가 있다."""
    items = (body.get("items") or {}).get("item") or []
    return [items] if isinstance(items, dict) else items


# ----------------------------------------------------------------------
# 집계
# ----------------------------------------------------------------------

def aggregate(rows: list[dict], fetched_at: str) -> dict:
    status = collections.Counter((r.get("faci_stat_nm") or "(없음)") for r in rows)
    active = [r for r in rows if r.get("faci_stat_nm") == ACTIVE_STATUS]

    graded, no_grade = [], 0
    for r in active:
        code = (r.get("schk_tot_grd_cd") or "").strip()
        if code:
            graded.append(r)
        else:
            no_grade += 1

    _verify_grade_codes(graded)

    overall = collections.Counter(r["schk_tot_grd_cd"].strip() for r in graded)

    by_type: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    for r in graded:
        name = (r.get("fcob_nm") or "").strip() or "(업종 미기재)"
        by_type[name][r["schk_tot_grd_cd"].strip()] += 1

    years = collections.Counter(
        str(r.get("schk_visit_ymd") or "")[:4] for r in graded if r.get("schk_visit_ymd")
    )
    valid_years = sorted(y for y in years if y.isdigit())

    return {
        "source": {
            "datasetId": "15107773",
            "datasetName": "국민체육진흥공단_전국체육시설 안전점검 정보",
            "operation": "체육시설안전정보",
            "fetchedAt": fetched_at,
        },
        "filter": {
            "facilityStatus": ACTIVE_STATUS,
            "fetched": len(rows),
            "active": len(active),
            "activeWithoutGrade": no_grade,
            "included": len(graded),
        },
        "grades": [{"code": c, "name": n} for c, n in EXPECTED_GRADES.items()],
        "overall": _dist(overall),
        "byBusinessType": sorted(
            ({"name": name, **_dist(counter)} for name, counter in by_type.items()),
            key=lambda x: -x["total"],
        ),
        "inspectionYears": {
            "min": valid_years[0] if valid_years else None,
            "max": valid_years[-1] if valid_years else None,
            "counts": {y: years[y] for y in valid_years},
        },
        "_report": {"statusCounts": dict(status)},
    }


def _dist(counter: collections.Counter) -> dict:
    total = sum(counter.values())
    return {
        "total": total,
        "counts": {code: counter.get(code, 0) for code in EXPECTED_GRADES},
    }


def _verify_grade_codes(graded: list[dict]) -> None:
    pairs = collections.Counter(
        ((r.get("schk_tot_grd_cd") or "").strip(), (r.get("schk_tot_grd_nm") or "").strip())
        for r in graded
    )
    unexpected = {p: n for p, n in pairs.items() if EXPECTED_GRADES.get(p[0]) != p[1]}
    if unexpected:
        print("등급 코드-명칭 쌍이 예상과 다릅니다:")
        for (code, name), n in sorted(unexpected.items()):
            print(f"  {code!r} {name!r}: {n:,}건")
        sys.exit("EXPECTED_GRADES를 확인한 뒤 다시 실행하세요. 임의로 보정하지 않습니다.")


# ----------------------------------------------------------------------
# 보고
# ----------------------------------------------------------------------

def report(stats: dict) -> None:
    f = stats["filter"]
    print("\n=== 수집·필터 ===")
    print(f"수집 {f['fetched']:,} → 정상운영 {f['active']:,} → 등급 있음 {f['included']:,}"
          f" (등급 없음 {f['activeWithoutGrade']:,})")
    print("운영상태:", stats["_report"]["statusCounts"])

    print("\n=== 전체 등급 분포 ===")
    _print_dist("전체", stats["overall"])

    print(f"\n=== 업종별 ({len(stats['byBusinessType'])}종) ===")
    for row in stats["byBusinessType"]:
        _print_dist(row["name"], row)

    y = stats["inspectionYears"]
    print(f"\n=== 점검 연도 {y['min']}~{y['max']} ===")
    for year, n in y["counts"].items():
        print(f"  {year}: {n:,}")


def _print_dist(label: str, d: dict) -> None:
    total = d["total"] or 1
    parts = "  ".join(
        f"{EXPECTED_GRADES[c]} {n / total:5.1%}" for c, n in d["counts"].items()
    )
    print(f"  {label:14} n={d['total']:6,}  {parts}")


# ----------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--from-raw", type=Path, help="이미 받은 원본 JSON으로 다시 집계")
    args = parser.parse_args()

    if args.from_raw:
        rows = json.loads(args.from_raw.read_text(encoding="utf-8"))
        stem = args.from_raw.stem.rsplit("_", 1)[-1]
        fetched_at = f"{stem[:4]}-{stem[4:6]}-{stem[6:8]}" if stem.isdigit() else "unknown"
    else:
        rows = fetch_all()
        fetched_at = dt.date.today().isoformat()
        RAW_DIR.mkdir(parents=True, exist_ok=True)
        raw_path = RAW_DIR / f"facility_safety_{fetched_at.replace('-', '')}.json"
        raw_path.write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")
        print(f"원본 저장: {raw_path.relative_to(ROOT)}")

    stats = aggregate(rows, fetched_at)
    report(stats)

    # 보고용 필드는 서비스 파일에 넣지 않는다
    stats.pop("_report")
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n집계 저장: {OUT_PATH.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
