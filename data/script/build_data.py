"""data/defects.csv → data/inspection_items.json, data/defect_cases.json 생성.

실행: python data/script/build_data.py (프로젝트 루트 기준 경로를 스크립트 위치로 계산하므로 cwd 무관)

원칙
- 원문 데이터를 임의로 보정하지 않는다. 이상값은 콘솔에 보고만 하고 원문을 유지한다.
- 동일 입력에 대해 항상 동일한 출력을 낸다 (정렬 기준 고정).
"""

import json
import re
import sys
from datetime import datetime
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = ROOT / "data"
CSV_PATH = DATA_DIR / "defects.csv"
IMAGES_DIR = DATA_DIR / "images"
ITEMS_JSON = DATA_DIR / "inspection_items.json"
CASES_JSON = DATA_DIR / "defect_cases.json"

EXPECTED_COLUMNS = [
    "순번", "결함유형", "점검일자", "광역자치단체", "기초자치단체", "체육시설",
    "시설물명", "주소", "건물구분", "위치구분", "점검항목", "지적사항", "이미지URL",
    "이미지파일이름", "이미지확장자", "가로해상도", "세로해상도",
]

# id 순서 고정 (사례 수 순서 아님). CSV 결함유형 값과 정확히 일치해야 한다.
ITEM_ORDER = [
    "균열, 누수",
    "탈락, 철근노출",
    "철골재 부식",
    "철골재 휨처짐",
    "철골재 볼트누락",
    "마감재파손",
    "옥상방수마감",
    "옥상 과하중",
    "창호유리",
    "창호문,방충망",
    "난간의 흔들림",
    "미끄럼방지",
    "안전시설(방지망)",
    "통행로",
    "부대시설",
    "광고간판, 조명",
    "옹벽의균열",
    "경사면",
    "부동침하",
    "기초",
    "승강기",
    "보수보강",
]

NOTICE_PATTERN = re.compile(r"^(\S+?)\s*\((.*?)\)\s*(.*)$")
# 회차가 "24(상)"처럼 괄호를 포함해 정규식으로 분해되지 않는 행. 순번 → 회차 원문.
ROUND_EXCEPTIONS = {176: "24(상)", 235: "24(상)"}
DATE_FORMATS = ["%Y-%m-%d", "%Y.%m.%d", "%Y/%m/%d", "%Y%m%d"]
VULNERABLE_PREFIX = "(취약시설)"


def section(title):
    print()
    print(f"=== {title} ===")


def fail(message):
    print(f"[중단] {message}", file=sys.stderr)
    sys.exit(1)


def sorted_counts(series):
    """건수 내림차순, 동률이면 값 오름차순 — 반복 실행 시 출력 순서 고정."""
    counts = series.value_counts(dropna=False)
    return sorted(counts.items(), key=lambda kv: (-kv[1], str(kv[0])))


def parse_notice(raw):
    """지적사항 → (round, grade, notice). 패턴 불일치 시 None."""
    m = NOTICE_PATTERN.match(raw)
    if not m:
        return None
    return m.group(1), m.group(2), m.group(3)


def parse_notice_with_round(raw, round_text):
    """회차 원문을 알고 있는 예외 행 분해. 형식이 예상과 다르면 None."""
    if not raw.startswith(round_text):
        return None
    rest = raw[len(round_text):].lstrip()
    if not rest.startswith("(") or ")" not in rest:
        return None
    close = rest.index(")")
    return round_text, rest[1:close], rest[close + 1:].lstrip()


def normalize_facility_name(raw):
    return raw.replace(VULNERABLE_PREFIX, "").strip()


def to_iso_date(raw):
    """점검일자 → YYYY-MM-DD. 해석 불가 시 None."""
    value = raw.strip()
    for fmt in DATE_FORMATS:
        try:
            return datetime.strptime(value, fmt).strftime("%Y-%m-%d")
        except ValueError:
            continue
    return None


def to_int(raw):
    try:
        return int(raw.strip())
    except ValueError:
        return None


def build_image_name(file_name, extension):
    """확장자가 없으면 이미지확장자를 붙인다. 있으면 원문 그대로."""
    if Path(file_name).suffix:
        return file_name
    ext = extension.strip().lstrip(".")
    return f"{file_name}.{ext}" if ext else file_name


def write_json(path, data):
    with path.open("w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2, allow_nan=False)
        f.write("\n")


def main():
    # 모든 값을 문자열 원문 그대로 읽는다 (NA 자동 변환 금지)
    df = pd.read_csv(CSV_PATH, encoding="utf-8", dtype=str, keep_default_na=False)

    missing_cols = [c for c in EXPECTED_COLUMNS if c not in df.columns]
    if missing_cols:
        fail(f"CSV에 필요한 컬럼이 없습니다: {missing_cols}")

    anomalies = []

    # ---- 순번 ----
    df["seq"] = df["순번"].map(to_int)
    bad_seq = df[df["seq"].isna()]
    if not bad_seq.empty:
        fail(f"정수로 해석할 수 없는 순번: {bad_seq['순번'].tolist()}")
    df["seq"] = df["seq"].astype(int)
    dup_seq = df[df["seq"].duplicated(keep=False)]["seq"].unique().tolist()
    if dup_seq:
        fail(f"중복된 순번: {sorted(dup_seq)}")
    df = df.sort_values("seq", kind="stable").reset_index(drop=True)

    # ---- 1. 지적사항 파싱 ----
    # Series.map은 None을 NaN으로 바꿔 JSON에 NaN이 새므로 파이썬 리스트로 다룬다
    parsed = [parse_notice(raw) for raw in df["지적사항"]]
    applied_exceptions = []
    for seq, round_text in ROUND_EXCEPTIONS.items():
        idx = df.index[df["seq"] == seq]
        if len(idx) != 1:
            fail(f"회차 예외 처리 대상 순번 {seq}이(가) CSV에 없습니다.")
        raw = df.at[idx[0], "지적사항"]
        result = parse_notice_with_round(raw, round_text)
        if result is None:
            fail(f"순번 {seq}의 지적사항이 예외 처리 형식({round_text}(등급) 소견)과 다릅니다: {raw!r}")
        parsed[idx[0]] = result
        applied_exceptions.append((seq, raw, result))
    ok_mask = pd.Series([p is not None for p in parsed], index=df.index)
    rounds = [p[0] if p else None for p in parsed]
    df["grade"] = [p[1] if p else None for p in parsed]
    # 패턴 불일치 행은 notice에 원문 전체
    notices = [p[2] if p else raw for p, raw in zip(parsed, df["지적사항"])]
    # 정규식은 일치했지만 소견이 괄호로 시작 → 분해가 어긋났을 가능성 (예: "24(상)(조치필요) ...")
    suspicious = [
        (seq, raw, p)
        for seq, raw, p in zip(df["seq"], df["지적사항"], parsed)
        if p and p[2].startswith("(") and seq not in ROUND_EXCEPTIONS
    ]

    # ---- 2. 시설물명 정규화 (최종 JSON 미포함) ----
    df["facilityName"] = df["시설물명"].map(normalize_facility_name)
    facility_changed = int((df["facilityName"] != df["시설물명"]).sum())
    facility_dup_prefix = int(df["시설물명"].str.count(re.escape(VULNERABLE_PREFIX)).gt(1).sum())

    # ---- 점검항목 ↔ 결함유형 1:1 검증 및 id 매칭 ----
    pairs = df[["결함유형", "점검항목"]].drop_duplicates()
    type_multi = pairs.groupby("결함유형")["점검항목"].nunique()
    item_multi = pairs.groupby("점검항목")["결함유형"].nunique()
    bad_type = type_multi[type_multi > 1].index.tolist()
    bad_item = item_multi[item_multi > 1].index.tolist()
    if bad_type or bad_item:
        for t in sorted(bad_type):
            print(f"  결함유형 '{t}' → 점검항목 {sorted(pairs[pairs['결함유형'] == t]['점검항목'].tolist())}")
        for i in sorted(bad_item):
            print(f"  점검항목 '{i}' → 결함유형 {sorted(pairs[pairs['점검항목'] == i]['결함유형'].tolist())}")
        fail("점검항목과 결함유형이 1:1로 대응하지 않습니다.")

    csv_types = set(pairs["결함유형"])
    unmatched_csv = sorted(csv_types - set(ITEM_ORDER))
    unmatched_order = [name for name in ITEM_ORDER if name not in csv_types]
    if unmatched_csv or unmatched_order:
        section("결함유형 매칭 실패")
        for name in unmatched_csv:
            print(f"  CSV에만 존재: {name!r}")
        for name in unmatched_order:
            print(f"  고정 목록에만 존재: {name!r}")
        fail("결함유형 이름이 고정 목록과 정확히 일치하지 않습니다.")
    if len(ITEM_ORDER) != 22 or len(csv_types) != 22:
        fail(f"항목 수가 22종이 아닙니다 (고정 목록 {len(ITEM_ORDER)}, CSV {len(csv_types)}).")

    item_id_by_type = {name: i for i, name in enumerate(ITEM_ORDER, start=1)}
    official_by_type = dict(zip(pairs["결함유형"], pairs["점검항목"]))
    df["itemId"] = df["결함유형"].map(item_id_by_type).astype(int)
    case_counts = df["itemId"].value_counts()

    inspection_items = [
        {
            "id": item_id,
            "shortName": name,
            "officialName": official_by_type[name],
            "caseCount": int(case_counts.get(item_id, 0)),
        }
        for item_id, name in enumerate(ITEM_ORDER, start=1)
    ]

    # ---- 점검일자 ----
    df["inspectedOn"] = df["점검일자"].map(to_iso_date)
    for _, row in df[df["inspectedOn"].isna()].iterrows():
        anomalies.append(f"순번 {row['seq']}: 점검일자 해석 불가 → 원문 유지 {row['점검일자']!r}")
    df["inspectedOn"] = df["inspectedOn"].fillna(df["점검일자"])

    # ---- 이미지 파일명 ----
    df["image"] = [build_image_name(f, e) for f, e in zip(df["이미지파일이름"], df["이미지확장자"])]
    for _, row in df.iterrows():
        suffix = Path(row["이미지파일이름"]).suffix.lstrip(".")
        ext = row["이미지확장자"].strip().lstrip(".")
        if not row["이미지파일이름"].strip():
            anomalies.append(f"순번 {row['seq']}: 이미지파일이름 비어 있음")
        elif suffix and ext and suffix.lower() != ext.lower():
            anomalies.append(
                f"순번 {row['seq']}: 파일명 확장자({suffix})와 이미지확장자({ext}) 불일치 → 원문 유지"
            )
    ext_appended = int((df["image"] != df["이미지파일이름"]).sum())

    # ---- 해상도 ----
    for src, dst in (("가로해상도", "width"), ("세로해상도", "height")):
        values = df[src].map(to_int)
        for _, row in df[values.isna()].iterrows():
            anomalies.append(f"순번 {row['seq']}: {src} 정수 해석 불가 → 원문 유지 {row[src]!r}")
        df[dst] = [int(v) if v is not None and not pd.isna(v) else raw for v, raw in zip(values, df[src])]

    # ---- 출력 2 구성 (시설물명·주소·광역/기초자치단체 제외) ----
    defect_cases = [
        {
            "seq": int(row["seq"]),
            "itemId": int(row["itemId"]),
            "round": rounds[i],
            "notice": notices[i],
            "buildingType": row["건물구분"],
            "positionType": row["위치구분"],
            "facilityType": row["체육시설"],
            "inspectedOn": row["inspectedOn"],
            "image": row["image"],
            "width": row["width"],
            "height": row["height"],
        }
        for i, (_, row) in enumerate(df.iterrows())
    ]

    write_json(ITEMS_JSON, inspection_items)
    write_json(CASES_JSON, defect_cases)

    # ================= 콘솔 리포트 =================
    total = len(df)
    n_ok = int(ok_mask.sum())
    n_fail = total - n_ok

    section("요약")
    print(f"총 행 수: {total} (열 {len(df[EXPECTED_COLUMNS].columns)})")
    print(f"지적사항 파싱 성공: {n_ok} / 실패: {n_fail} ({n_ok / total:.1%} 부합)")
    print(f"시설물명 정규화로 값이 바뀐 행: {facility_changed} (접두사 중복 {facility_dup_prefix})")
    print(f"image에 확장자를 붙인 행: {ext_appended}")
    print(f"출력: {ITEMS_JSON.relative_to(ROOT)} ({len(inspection_items)}건), "
          f"{CASES_JSON.relative_to(ROOT)} ({len(defect_cases)}건)")

    section(f"지적사항 패턴 미일치 행 ({n_fail}건)")
    if n_fail == 0:
        print("  없음")
    for _, row in df[~ok_mask].iterrows():
        print(f"  순번 {row['seq']}: {row['지적사항']!r}")

    section(f"회차 예외 처리 적용 행 ({len(applied_exceptions)}건)")
    for seq, raw, (rnd, grade, notice) in applied_exceptions:
        print(f"  순번 {seq}: {raw!r}")
        print(f"      → round={rnd!r} grade={grade!r} notice={notice!r}")

    section(f"패턴은 일치하나 분해가 의심되는 행 ({len(suspicious)}건) — 원문대로 출력됨")
    if not suspicious:
        print("  없음")
    for seq, raw, (rnd, grade, notice) in suspicious:
        print(f"  순번 {seq}: {raw!r}")
        print(f"      → round={rnd!r} grade={grade!r} notice={notice!r}")

    section("등급(grade) 분포 — 출력 미포함, 참고용")
    for value, count in sorted_counts(df.loc[ok_mask, "grade"]):
        print(f"  {count:>4}  {value}")

    position_counts = sorted_counts(df["위치구분"])
    section(f"위치구분 고유값 ({len(position_counts)}종)")
    for value, count in position_counts:
        print(f"  {count:>4}  {value}")

    building_counts = sorted_counts(df["건물구분"])
    section(f"건물구분 고유값 ({len(building_counts)}종)")
    for value, count in building_counts:
        print(f"  {count:>4}  {value}")

    section("항목별 사례 수 (id 순)")
    for item in inspection_items:
        print(f"  {item['id']:>2}  {item['caseCount']:>4}  {item['shortName']}")
    print(f"  합계 {sum(i['caseCount'] for i in inspection_items)}")

    section("data/images/ 에 없는 이미지 파일")
    missing_images = [c["image"] for c in defect_cases if not (IMAGES_DIR / c["image"]).is_file()]
    if not missing_images:
        print("  없음")
    for name in missing_images:
        print(f"  {name}")

    section(f"기타 이상값 ({len(anomalies)}건)")
    if not anomalies:
        print("  없음")
    for line in anomalies:
        print(f"  {line}")


if __name__ == "__main__":
    main()
