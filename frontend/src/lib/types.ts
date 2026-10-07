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

// ------------------------------------------------------------ Phase 2: 은행 대사

export type BankTxStatus = "UNMATCHED" | "SUGGESTED" | "MATCHED";
export type MatchStatus = "SUGGESTED" | "CONFIRMED" | "REJECTED" | "CANCELLED";
export type MatchSource = "AUTO" | "MANUAL";
export type MatchLevel = "HIGH" | "REVIEW" | "LOW";

export interface BankAccount {
  id: number;
  name: string;
  bankName: string | null;
  accountNoMasked: string | null;
  glAccountId: number;
  glAccountCode: string | null;
  glAccountName: string | null;
  active: boolean;
  createdAt: string;
}

export interface BankImport {
  id: number;
  bankAccountId: number;
  fileName: string;
  encoding: string;
  totalRows: number;
  importedRows: number;
  duplicateRows: number;
  createdBy: number;
  createdAt: string;
}

export interface MatchSummary {
  matchId: number;
  status: MatchStatus;
  source: MatchSource;
  score: number;
  level: MatchLevel;
  journalEntryIds: number[];
}

export interface BankTransaction {
  id: number;
  bankAccountId: number;
  txDate: string;
  txTime: string | null;
  direction: Direction;
  amount: Money;
  balance: Money | null;
  counterparty: string | null;
  memo: string | null;
  status: BankTxStatus;
  match: MatchSummary | null;
}

/** 서버 MatchScorer가 저장하는 점수 근거 */
export interface ScoreDetail {
  total: number;
  level: MatchLevel;
  amount: number;
  date: number;
  counterparty: number;
  memo: number;
  pattern: number;
  bankAmount: string;
  entryAmount: string;
  dayDiff: number;
  counterpartySimilarity: number;
  memoSimilarity: number;
  patternCount: number;
  reasons: string[];
}

export interface ReconMatch {
  id: number;
  matchType: string;
  status: MatchStatus;
  source: MatchSource;
  score: number;
  level: MatchLevel;
  scoreDetail: ScoreDetail;
  bankTransactionIds: number[];
  journalEntryIds: number[];
  createdBy: number;
  createdAt: string;
  reviewedBy: number | null;
  reviewedAt: string | null;
  reviewNote: string | null;
}

export interface Candidate {
  journalEntryId: number;
  entryDate: string;
  description: string | null;
  counterparty: string | null;
  bankAmount: Money;
  score: number;
  level: MatchLevel;
  scoreDetail: ScoreDetail;
  suggestedElsewhere: boolean;
  rejected: boolean;
}

export interface RunResult {
  bankAccountId: number;
  processed: number;
  suggestedHigh: number;
  suggestedReview: number;
  unmatched: number;
}

export interface ReconSummary {
  bankAccountId: number | null;
  total: number;
  matched: number;
  suggested: number;
  unmatched: number;
  matchRate: number | null;
  autoConfirmed: number;
  autoRejected: number;
  autoSuccessRate: number | null;
}

// ------------------------------------------------------------ Phase 3: 증빙(Document AI)

export type DocType = "RECEIPT" | "TAX_INVOICE";
export type DocStatus = "PENDING" | "AI_REVIEWED" | "REVIEW_REQUIRED" | "APPROVED" | "REJECTED" | "FAILED";

export interface AiField {
  value: string | null;
  confidence: number;
  source: string;
}

export interface ValidationIssue {
  code: string;
  severity: "ERROR" | "WARNING";
  field: string | null;
  message: string;
}

export interface AccountCandidate {
  accountId: number;
  code: string;
  name: string;
  confidence: number;
  source: "HISTORY" | "RULE";
  reasons: string[];
}

export interface DocumentFields {
  vendorName: string | null;
  vendorBusinessNo: string | null;
  buyerName: string | null;
  buyerBusinessNo: string | null;
  issueDate: string | null;
  supplyAmount: Money | null;
  vatAmount: Money | null;
  totalAmount: Money | null;
  paymentMethod: string | null;
  itemsSummary: string | null;
}

export interface DocumentDetail extends DocumentFields {
  id: number;
  docType: DocType;
  status: DocStatus;
  fileName: string;
  contentType: string;
  fileSize: number;
  aiConfidence: number | null;
  aiFields: Partial<Record<keyof DocumentFields, AiField>>;
  ocrLines: { text: string; score: number; page: number }[];
  ocrInfo: { engine?: string; model?: string; durationMs?: number; pages?: number };
  validation: ValidationIssue[];
  recommendation: AccountCandidate[];
  recommendedAccountId: number | null;
  accountId: number | null;
  journalEntryId: number | null;
  reviewedBy: number | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  attempt: number;
  queuedAt: string | null;
  processedAt: string | null;
  errorMessage: string | null;
  uploadedBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface DocumentListItem {
  id: number;
  docType: DocType;
  status: DocStatus;
  fileName: string;
  vendorName: string | null;
  issueDate: string | null;
  totalAmount: Money | null;
  aiConfidence: number | null;
  errorCount: number;
  warningCount: number;
  accountId: number | null;
  journalEntryId: number | null;
  createdAt: string;
}

export interface DocumentSummary {
  byStatus: Record<DocStatus, number>;
  unprocessed: number;
  total: number;
}

export const DOC_TYPE_LABEL: Record<DocType, string> = { RECEIPT: "영수증", TAX_INVOICE: "세금계산서" };

export const DOC_STATUS_LABEL: Record<DocStatus, string> = {
  PENDING: "AI 처리 중",
  AI_REVIEWED: "AI 검토 완료",
  REVIEW_REQUIRED: "확인 필요",
  APPROVED: "승인",
  REJECTED: "거절",
  FAILED: "처리 실패",
};

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
