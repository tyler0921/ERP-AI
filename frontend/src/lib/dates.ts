// 날짜는 회사 기준 시간대(Asia/Seoul)의 "YYYY-MM-DD" 문자열로 다룬다. (백엔드 app.zone과 동일)

const KST = "Asia/Seoul";

export function todayKst(now: Date = new Date()): string {
  // en-CA 로케일은 YYYY-MM-DD 형식으로 출력한다.
  return new Intl.DateTimeFormat("en-CA", { timeZone: KST, year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
}

/** 해당 날짜가 속한 달의 첫날·마지막날 */
export function monthRange(date: string): { from: string; to: string } {
  const [y, m] = date.split("-").map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const mm = String(m).padStart(2, "0");
  return { from: `${y}-${mm}-01`, to: `${y}-${mm}-${String(last).padStart(2, "0")}` };
}
