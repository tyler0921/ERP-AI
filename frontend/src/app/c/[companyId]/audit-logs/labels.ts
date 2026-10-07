// 백엔드 AuditActions 상수와 대응하는 표시 이름
export const ACTION_LABEL: Record<string, string> = {
  USER_SIGNUP: "회원가입",
  LOGIN_SUCCESS: "로그인 성공",
  LOGIN_FAILURE: "로그인 실패",
  COMPANY_CREATE: "회사 등록",
  COMPANY_UPDATE: "회사 정보 수정",
  MEMBER_ADD: "멤버 추가",
  MEMBER_ROLE_CHANGE: "멤버 권한 변경",
  MEMBER_REMOVE: "멤버 제거",
  ACCOUNT_CREATE: "계정과목 생성",
  ACCOUNT_UPDATE: "계정과목 수정",
  TX_CREATE: "거래 등록",
  TX_UPDATE: "거래 수정",
  TX_VOID: "거래 취소",
  JE_CREATE: "전표 작성",
  JE_UPDATE: "전표 수정",
  JE_VOID: "전표 취소",
};

export const TARGET_LABEL: Record<string, string> = {
  USER: "사용자",
  COMPANY: "회사",
  MEMBER: "멤버",
  ACCOUNT: "계정과목",
  TRANSACTION: "거래",
  JOURNAL_ENTRY: "전표",
};

export function actionLabel(action: string): string {
  return ACTION_LABEL[action] ?? action;
}
