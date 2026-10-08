"""이상거래 탐지 데모용 가상 은행 거래내역 CSV를 만든다. (실제 금융 데이터 아님)

사용: python scripts/make_anomaly_sample.py <출력 파일>
2026-04 ~ 2026-09 평범한 지출(평일 낮, 거래처별 비슷한 금액)에 8~9월 이상 거래를 섞는다.
같은 시드면 항상 같은 파일이 나온다. 표준 라이브러리만 쓴다.
"""

from __future__ import annotations

import csv
import random
import sys
from datetime import date, datetime, time, timedelta
from pathlib import Path

START = date(2026, 4, 1)
END = date(2026, 9, 30)
OPENING_BALANCE = 300_000_000

# (거래처, 적요, 기준 금액, 매월 일자들)
MONTHLY = [
    ("가상통신", "통신비", 55_000, [5]),
    ("(주)가상물산", "매입대금", 1_200_000, [10, 25]),
    ("한빛문구", "사무용품", 83_000, [12]),
    ("가상택배", "택배비", 120_000, [8, 22]),
    ("네이버광고", "광고비", 330_000, [15]),
    ("가상렌탈", "복합기 임대료", 440_000, [20]),
    ("가상전력", "전기요금", 210_000, [18]),
    ("가상빌딩관리", "관리비", 1_650_000, [27]),
    ("가상소프트", "SaaS 구독료", 99_000, [3]),
    ("가상보험", "보험료", 380_000, [14]),
]
# 자주 발생하는 소액 지출
SMALL = [("가상커피", "회의 다과", 18_000), ("가상택시", "교통비", 23_000), ("가상식당", "식대", 64_000)]
# 매출 입금 (분석 대상 아님)
DEPOSITS = [("한빛상사", "외상대금", 8_500_000), ("가상유통", "매출대금", 6_200_000)]

# 8~9월에 섞는 이상 거래 (날짜, 시각, 금액, 거래처, 적요) — 탐지되어야 할 규칙을 주석으로 남긴다
ANOMALIES = [
    # 승인 기준(500만원) 직전 + 30일 내 반복
    (date(2026, 8, 27), time(16, 5), 4_700_000, "가상인테리어", "사무실 보수"),
    # 같은 날 같은 거래처 분할 결제 + 승인 기준 직전 반복 + 신규 거래처 고액
    (date(2026, 9, 8), time(10, 12), 4_950_000, "가상컨설팅", "자문료"),
    (date(2026, 9, 8), time(15, 40), 4_800_000, "가상컨설팅", "자문료 잔금"),
    # 동일 금액 중복 결제
    (date(2026, 9, 16), time(11, 30), 1_253_000, "(주)가상물산", "매입대금"),
    (date(2026, 9, 17), time(9, 50), 1_253_000, "(주)가상물산", "매입대금"),
    # 토요일 심야 결제
    (date(2026, 9, 12), time(23, 40), 450_000, "심야주점", "법인카드"),
    # 거래처 평균(약 33만원) 대비 고액
    (date(2026, 8, 20), time(14, 10), 3_900_000, "네이버광고", "광고비"),
    # 7일 동안 같은 거래처 반복 결제
    (date(2026, 9, 1), time(10, 0), 35_000, "번개퀵", "퀵서비스"),
    (date(2026, 9, 2), time(15, 0), 42_000, "번개퀵", "퀵서비스"),
    (date(2026, 9, 3), time(11, 0), 38_000, "번개퀵", "퀵서비스"),
    (date(2026, 9, 4), time(16, 30), 51_000, "번개퀵", "퀵서비스"),
]


def business_day(d: date) -> date:
    while d.weekday() >= 5:
        d += timedelta(days=1)
    return d


def rows(rng: random.Random) -> list[tuple[datetime, int, int, str, str]]:
    """(일시, 출금액, 입금액, 거래처, 적요)"""
    result = []
    month = date(START.year, START.month, 1)
    while month <= END:
        for vendor, memo, base, days in MONTHLY:
            for day in days:
                d = business_day(month.replace(day=day))
                if START <= d <= END:
                    amount = round(base * rng.uniform(0.93, 1.07), -2)
                    at = time(rng.randint(9, 17), rng.randint(0, 59))
                    result.append((datetime.combine(d, at), int(amount), 0, vendor, memo))
        for vendor, memo, base in DEPOSITS:
            d = business_day(month.replace(day=rng.randint(5, 25)))
            if START <= d <= END:
                result.append((datetime.combine(d, time(10, 0)), 0, int(round(base * rng.uniform(0.9, 1.1), -3)), vendor, memo))
        month = (month.replace(day=28) + timedelta(days=4)).replace(day=1)

    d = START
    while d <= END:
        if d.weekday() < 5 and rng.random() < 0.35:
            vendor, memo, base = rng.choice(SMALL)
            at = time(rng.randint(11, 19), rng.randint(0, 59))
            result.append((datetime.combine(d, at), int(round(base * rng.uniform(0.7, 1.3), -2)), 0, vendor, memo))
        d += timedelta(days=1)

    for d, at, amount, vendor, memo in ANOMALIES:
        result.append((datetime.combine(d, at), amount, 0, vendor, memo))
    return sorted(result)


def main(out: Path) -> None:
    rng = random.Random(20260930)
    balance = OPENING_BALANCE
    out.parent.mkdir(parents=True, exist_ok=True)
    # 은행에서 내려받은 파일처럼 UTF-8 BOM을 붙인다 (엑셀 호환)
    with out.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["거래일자", "거래시간", "출금액", "입금액", "잔액", "거래처", "적요"])
        for at, withdrawal, deposit, vendor, memo in rows(rng):
            balance += deposit - withdrawal
            writer.writerow([
                at.date().isoformat(),
                at.strftime("%H:%M:%S"),
                f"{withdrawal:,}" if withdrawal else "",
                f"{deposit:,}" if deposit else "",
                f"{balance:,}",
                vendor,
                memo,
            ])
    print("written:", out)


if __name__ == "__main__":
    main(Path(sys.argv[1] if len(sys.argv) > 1 else "bank-statement-anomaly-sample.csv"))
