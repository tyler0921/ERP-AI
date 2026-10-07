// Spring Boot API의 DTO와 1:1로 대응하는 타입.
// 금액은 정밀도 손실을 막기 위해 백엔드가 문자열("1250000.00")로 직렬화한다.

export type Money = string;

export type Role = "ADMIN" | "ACCOUNTANT" | "VIEWER";
export type AccountType = "ASSET" | "LIABILITY" | "EQUITY" | "REVENUE" | "EXPENSE";
export type Direction = "IN" | "OUT";
export type TxStatus = "PENDING" | "VOID";
export type EntryStatus = "PENDING" | "VOID";

export interface ApiErrorBody {
  code: string;
  message: string;
  details: { field: string; reason: string }[];
  timestamp: string;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
}

export interface Membership {
  companyId: number;
  companyName: string;
  role: Role;
}

export interface Me {
  id: number;
  email: string;
  name: string;
  companies: Membership[];
}

export interface Company {
  id: number;
  name: string;
  businessNo: string | null;
  role: Role;
  createdAt: string;
}

export interface Member {
  userId: number;
  email: string;
  name: string;
  role: Role;
}

export interface Account {
  id: number;
  code: string;
  name: string;
  type: AccountType;
  active: boolean;
}

export interface Transaction {
  id: number;
  txDate: string;
  counterparty: string | null;
  amount: Money;
  direction: Direction;
  memo: string | null;
  status: TxStatus;
  voidReason: string | null;
  journalEntryIds?: number[];
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface JournalLine {
  id: number;
  accountId: number;
  accountCode: string;
  accountName: string;
  debit: Money;
  credit: Money;
}

export interface JournalEntry {
  id: number;
  entryDate: string;
  description: string | null;
  transactionId: number | null;
  status: EntryStatus;
  voidReason: string | null;
  lines: JournalLine[];
  totalDebit: Money;
  totalCredit: Money;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface JournalEntrySummary {
  id: number;
  entryDate: string;
  description: string | null;
  transactionId: number | null;
  status: EntryStatus;
  totalAmount: Money;
  createdAt: string;
}

export interface AuditLog {
  id: number;
  companyId: number | null;
  actorId: number | null;
  action: string;
  targetType: string | null;
  targetId: number | null;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  createdAt: string;
}

export const ROLE_LABEL: Record<Role, string> = {
  ADMIN: "관리자",
  ACCOUNTANT: "회계담당자",
  VIEWER: "조회자",
};

export const ACCOUNT_TYPE_LABEL: Record<AccountType, string> = {
  ASSET: "자산",
  LIABILITY: "부채",
  EQUITY: "자본",
  REVENUE: "수익",
  EXPENSE: "비용",
};

export const DIRECTION_LABEL: Record<Direction, string> = {
  IN: "입금",
  OUT: "출금",
};

export function canWrite(role: Role | undefined): boolean {
  return role === "ADMIN" || role === "ACCOUNTANT";
}
