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

// ------------------------------------------------------------ Phase 4: 이상거래 탐지

export type RiskLevel = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
export type AnomalyStatus = "OPEN" | "CONFIRMED" | "DISMISSED" | "CLEARED";
export type AnomalySource = "TRANSACTION" | "BANK_TRANSACTION";
export type MlStatus = "APPLIED" | "PARTIAL" | "SKIPPED" | "UNAVAILABLE" | "DISABLED";

export interface AnomalySettings {
  approvalLimit: Money;
  nearLimitRatio: string;
  evidenceMinAmount: Money;
  newVendorMinAmount: Money;
  spikeMultiplier: string;
  nightStartHour: number;
  nightEndHour: number;
  mlEnabled: boolean;
  /** false면 저장된 설정이 없어 기본값 */
  saved: boolean;
  updatedBy: number | null;
  updatedAt: string | null;
}

export interface MlSourceInfo {
  status: MlStatus;
  samples: number;
  model?: string;
  durationMs?: number;
  message?: string;
}

// ------------------------------------------------------------ Phase 5: LLM 설명

export type ExplanationStatus = "PENDING" | "GENERATED" | "FALLBACK" | "FAILED" | "EDITED";

export interface ExplanationIssue {
  code: string;
  message: string;
}

/** LLM 설명. facts는 LLM에 준 입력 그대로 */
export interface Explanation {
  id: number;
  status: ExplanationStatus;
  text: string | null;
  checkpoints: string[];
  meta: {
    provider?: string;
    model?: string;
    promptVersion?: string;
    durationMs?: number;
    validation?: ExplanationIssue[];
    rejectedText?: string;
    llmError?: string;
    error?: string;
    stale?: boolean;
    editedFrom?: ExplanationStatus;
  };
  facts: Record<string, unknown>;
  attempt: number;
  requestedAt: string;
  completedAt: string | null;
  editedBy: number | null;
  editedAt: string | null;
}

export const EXPLANATION_STATUS_LABEL: Record<ExplanationStatus, string> = {
  PENDING: "생성 중",
  GENERATED: "AI 설명",
  FALLBACK: "규칙 요약(대체)",
  FAILED: "생성 실패",
  EDITED: "담당자 수정",
};

export interface AnomalyScan {
  id: number;
  periodFrom: string;
  periodTo: string;
  analyzedCount: number;
  flaggedCount: number;
  summary: {
    created: number;
    updated: number;
    cleared: number;
    reopened: number;
    keptReviewed: number;
    byLevel: Record<RiskLevel, number>;
    analyzedBySource: Record<AnomalySource, number>;
  };
  mlStatus: MlStatus;
  mlInfo: Partial<Record<AnomalySource, MlSourceInfo>>;
  settings: Record<string, string | number | boolean>;
  durationMs: number;
  createdBy: number;
  createdAt: string;
  explanation: Explanation | null;
}

export interface RuleHit {
  code: string;
  title: string;
  weight: number;
  message: string;
  evidence: Record<string, unknown>;
}

export interface MlTopFeature {
  feature: string;
  value: number | string | null;
  median: number | null;
  zScore: number;
}

export interface MlDetail {
  status: MlStatus;
  model: string | null;
  points: number;
  maxPoints: number;
  score?: number;
  rawScore?: number;
  topFeatures?: MlTopFeature[];
  message?: string;
}

export interface AnomalyListItem {
  id: number;
  sourceType: AnomalySource;
  sourceId: number;
  txDate: string;
  txTime: string | null;
  counterparty: string | null;
  amount: Money;
  riskScore: number;
  riskLevel: RiskLevel;
  effectiveLevel: RiskLevel;
  adjustedLevel: RiskLevel | null;
  ruleScore: number;
  mlScore: string | null;
  ruleCodes: string[];
  status: AnomalyStatus;
  reviewedAt: string | null;
  updatedAt: string;
}

export interface PreviousReview {
  status: AnomalyStatus;
  riskLevel: RiskLevel;
  adjustedLevel: RiskLevel | null;
  note: string | null;
  reviewedBy: number | null;
  reviewedAt: string | null;
}

export interface AnomalyDetail extends Omit<AnomalyListItem, "ruleCodes"> {
  ruleHits: RuleHit[];
  features: Record<string, string | number | null>;
  mlDetail: MlDetail;
  context: {
    memo: string | null;
    bankAccountId: number | null;
    accountId: number | null;
    accountCode: string | null;
    accountName: string | null;
    journalEntryIds: number[];
    documentIds: number[];
    clearedReason?: string;
    previousReview?: PreviousReview;
  };
  reviewNote: string | null;
  reviewedBy: number | null;
  scanId: number;
  firstDetectedAt: string;
  explanation: Explanation | null;
}

export interface AnomalySummary {
  openByLevel: Record<RiskLevel, number>;
  open: number;
  highRisk: number;
  confirmed: number;
  dismissed: number;
  cleared: number;
  lastScan: AnomalyScan | null;
}

export const RISK_LEVEL_LABEL: Record<RiskLevel, string> = { LOW: "낮음", MEDIUM: "중간", HIGH: "높음", CRITICAL: "심각" };

export const ANOMALY_STATUS_LABEL: Record<AnomalyStatus, string> = {
  OPEN: "검토 대기",
  CONFIRMED: "이상 확정",
  DISMISSED: "정상 처리",
  CLEARED: "해제",
};

export const ANOMALY_SOURCE_LABEL: Record<AnomalySource, string> = { TRANSACTION: "거래", BANK_TRANSACTION: "은행 거래" };

export const ML_STATUS_LABEL: Record<MlStatus, string> = {
  APPLIED: "ML 반영",
  PARTIAL: "ML 일부 반영",
  SKIPPED: "ML 건너뜀",
  UNAVAILABLE: "ML 사용 불가",
  DISABLED: "ML 꺼짐",
};

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

// ------------------------------------------------------------ Phase 6: 월마감

/** 비교 한 쌍. text 예: "21,000,000원 증가 (+700.0%)". pct는 비교값이 0이면 null */
export interface ClosingChange {
  diff: string;
  pct: string | null;
  text: string;
}

export interface ClosingLine {
  kind: "TOTAL" | "ACCOUNT" | "VENDOR";
  key: string;
  label: string;
  type: string;
  current: string;
  prevMonth: string;
  prevYear: string;
  vsPrevMonth: ClosingChange;
  vsPrevYear: ClosingChange;
}

export type VarianceCode = "INCREASE" | "DECREASE" | "NEW" | "GONE";
export type VarianceBasis = "PREV_MONTH" | "PREV_YEAR";

export interface ClosingVariance {
  kind: ClosingLine["kind"];
  key: string;
  label: string;
  basis: VarianceBasis;
  code: VarianceCode;
  current: string;
  base: string;
  diff: string;
  pct: string | null;
  message: string;
}

export interface ClosingChecklistItem {
  code: string;
  title: string;
  done: boolean;
  remaining: number;
  detail: string;
  /** 회사 화면 경로 (예: "documents") */
  href: string;
}

export type ClosingStatus = "OPEN" | "CLOSED";

export interface ClosingReport {
  month: string;
  prevMonth: string;
  prevYearMonth: string;
  totals: ClosingLine[];
  accounts: ClosingLine[];
  vendors: ClosingLine[];
  variances: ClosingVariance[];
  anomalies: { open: number; highRisk: number; confirmed: number; dismissed: number };
  checklist: ClosingChecklistItem[];
  progress: { done: number; total: number; percent: number };
  closing: {
    id: number | null;
    status: ClosingStatus;
    closedBy: number | null;
    closedAt: string | null;
    note: string | null;
    changedSinceClose: boolean;
    snapshot: { totals?: Record<string, string>; checklist?: { code: string; title: string; done: boolean; remaining: number }[] } | null;
  };
  explanation: Explanation | null;
}

export interface ClosingTrendPoint {
  month: string;
  revenue: string;
  expense: string;
  operatingProfit: string;
  status: ClosingStatus;
}

export const VARIANCE_CODE_LABEL: Record<VarianceCode, string> = {
  INCREASE: "증가",
  DECREASE: "감소",
  NEW: "신규",
  GONE: "소멸",
};

export const VARIANCE_BASIS_LABEL: Record<VarianceBasis, string> = {
  PREV_MONTH: "전월",
  PREV_YEAR: "전년 동월",
};

// ------------------------------------------------------------ Phase 7: AI Copilot

export type CopilotStatus = "PLANNING" | "ANSWERED" | "UNSUPPORTED" | "FAILED";
export type PlanSource = "LLM" | "RULE" | "USER";

/** 질의 계획. LLM·규칙 Planner·사람이 낸 것을 백엔드가 검증한 형태 */
export interface CopilotPlan {
  tool: CopilotTool;
  period?: { type: PeriodType; year?: number; month?: number; quarter?: number };
  compare?: "PREV_PERIOD" | "PREV_YEAR";
  account?: string;
  order?: "AMOUNT" | "INCREASE" | "DECREASE";
  minLevel?: RiskLevel;
  status?: "OPEN" | "ALL";
  limit?: number;
  reason?: string;
}

export type CopilotTool = "PROFIT_SUMMARY" | "ACCOUNT_SUMMARY" | "VENDOR_RANKING" | "ANOMALY_LIST" | "CLOSING_STATUS" | "UNSUPPORTED";
export type PeriodType = "THIS_MONTH" | "LAST_MONTH" | "MONTH" | "THIS_QUARTER" | "LAST_QUARTER" | "QUARTER" | "THIS_YEAR" | "LAST_YEAR" | "YEAR";

export type CopilotValueType = "money" | "pct" | "number" | "text";

export interface CopilotResult {
  tool: CopilotTool;
  title: string;
  period: string | null;
  comparePeriod: string | null;
  highlights: { label: string; value: string | null; type: CopilotValueType }[];
  tables: {
    title: string;
    columns: { key: string; label: string; type: CopilotValueType }[];
    rows: Record<string, string | number | null>[];
  }[];
  summary: string[];
  directions: { item: string; direction: "증가" | "감소" }[];
}

export interface CopilotQuestion {
  id: number;
  question: string;
  status: CopilotStatus;
  attempt: number;
  plan: CopilotPlan | null;
  planSource: PlanSource | null;
  planMeta: {
    model?: string;
    promptVersion?: string;
    durationMs?: number;
    llmError?: string;
    llmIssues?: string[];
    planIssues?: string[];
  };
  result: CopilotResult | null;
  error: string | null;
  createdAt: string;
  plannedAt: string | null;
  answer: Explanation | null;
}

export interface CopilotQuestionSummary {
  id: number;
  question: string;
  status: CopilotStatus;
  tool: string | null;
  period: string | null;
  createdAt: string;
}
