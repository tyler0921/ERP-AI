"""데모·테스트용 가상 영수증/세금계산서 이미지를 만든다. (실제 거래 데이터 아님)

사용: python scripts/make_samples.py <출력 폴더>
사업자등록번호는 체크섬이 맞는 가상 번호다.
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

FONT = "/usr/share/fonts/truetype/nanum/NanumGothic.ttf"
FONT_BOLD = "/usr/share/fonts/truetype/nanum/NanumGothicBold.ttf"


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT_BOLD if bold else FONT, size)


def receipt() -> Image.Image:
    img = Image.new("RGB", (620, 900), "white")
    d = ImageDraw.Draw(img)
    f, fb = font(24), font(30, bold=True)
    rows = [
        ("카드 영수증 (고객용)", fb),
        ("상호: 가상커피 강남점", f),
        ("사업자번호: 211-88-55554", f),
        ("대표: 김가상  TEL 02-123-4567", f),
        ("거래일시: 2026-10-07 12:34:56", f),
        ("------------------------------------------", f),
        ("아메리카노        2        9,000", f),
        ("카페라떼          1        5,000", f),
        ("베이글            1        4,000", f),
        ("------------------------------------------", f),
        ("공급가액                  16,364", f),
        ("부가세                     1,636", f),
        ("합계금액                  18,000", fb),
        ("신용카드 승인   일시불", f),
        ("카드번호 1234-56**-****-7890", f),
        ("승인번호 12345678", f),
    ]
    y = 30
    for text, fnt in rows:
        d.text((40, y), text, fill="black", font=fnt)
        y += 52
    return img


def tax_invoice() -> Image.Image:
    img = Image.new("RGB", (1100, 760), "white")
    d = ImageDraw.Draw(img)
    f, fb = font(24), font(34, bold=True)
    d.text((400, 30), "전자세금계산서", fill="black", font=fb)
    d.line((30, 100, 1070, 100), fill="black", width=2)
    d.line((550, 100, 550, 300), fill="black", width=2)
    # 왼쪽: 공급자 / 오른쪽: 공급받는자 (라벨과 값을 떨어뜨려 표 칸처럼 배치)
    for x, role, biz, name, ceo in (
        (40, "공급자", "314-86-12340", "(주)가상소프트", "이가상"),
        (580, "공급받는자", "123-45-67891", "가상상사", "박데모"),
    ):
        d.text((x, 115), role, fill="black", font=f)
        d.text((x, 160), "등록번호", fill="black", font=f)
        d.text((x + 170, 160), biz, fill="black", font=f)
        d.text((x, 210), "상호", fill="black", font=f)
        d.text((x + 170, 210), name, fill="black", font=f)
        d.text((x, 260), "성명", fill="black", font=f)
        d.text((x + 170, 260), ceo, fill="black", font=f)
    d.line((30, 310, 1070, 310), fill="black", width=2)
    # 작성일자 / 공급가액 / 세액: 머리글 아래 칸에 값
    for x, label, value in ((60, "작성일자", "2026-10-05"), (430, "공급가액", "3,000,000"), (800, "세액", "300,000")):
        d.text((x, 330), label, fill="black", font=f)
        d.text((x, 380), value, fill="black", font=f)
    d.line((30, 430, 1070, 430), fill="black", width=2)
    d.text((40, 450), "월   일     품목                  수량       공급가액        세액", fill="black", font=f)
    d.text((40, 500), "10   05     소프트웨어 유지보수     1      3,000,000      300,000", fill="black", font=f)
    d.line((30, 560, 1070, 560), fill="black", width=2)
    d.text((40, 590), "합계금액", fill="black", font=f)
    d.text((240, 590), "3,300,000", fill="black", font=fb)
    d.text((640, 590), "이 금액을 청구함", fill="black", font=f)
    return img


def main(out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    receipt().save(out / "receipt-sample.png")
    invoice = tax_invoice()
    invoice.save(out / "tax-invoice-sample.png")
    invoice.save(out / "tax-invoice-sample.pdf", "PDF", resolution=150)
    print("written:", *sorted(p.name for p in out.iterdir()))


if __name__ == "__main__":
    main(Path(sys.argv[1] if len(sys.argv) > 1 else "samples"))
