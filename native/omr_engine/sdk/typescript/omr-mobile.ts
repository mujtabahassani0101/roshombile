/** Framework-neutral TypeScript contract for the Lajvard OMR native bridge. */

export const OMR_API_VERSION = 2 as const;

export type OmrBatchStatus = 'OK' | 'PARTIAL' | 'ERROR';
export type OmrQuestionStatus = 'MARKED' | 'BLANK' | 'MULTI' | 'AMBIGUOUS';

export type OmrAnchor = Readonly<{ x: number; y: number }>;

export type OmrPageInput = Readonly<{
  pageNumber?: number;
  imagePath: string;
  anchors?: readonly OmrAnchor[];
  anchorPointsPercent?: readonly number[];
}>;

export type OmrCoreRequest = Readonly<{
  templateJson?: string;
  templateJsonPath?: string;
  optionsJson?: string;
  optionsJsonPath?: string;
  outputDirectory?: string;
  examCode?: string;
  returnDebugImages?: boolean;
  returnNormalizedImages?: boolean;
  compactResult?: boolean;
  includeQuestions?: boolean;
  maxInputDimension?: number;
  pages: readonly OmrPageInput[];
}>;

export type OmrMachineReadableCode = Readonly<{
  found: boolean;
  attempted: boolean;
  status: string;
  text: string | null;
  format: string | null;
  confidence: number;
  error: string;
  skippedReason: string;
}>;

export type OmrAnswerSummary = Readonly<{
  total: number;
  marked: number;
  blank: number;
  multi: number;
  ambiguous: number;
}>;

export type OmrStudentCodeSummary = Readonly<{
  columns: number;
  marked: number;
  blank: number;
  multi: number;
  ambiguous: number;
}>;

export type OmrStudentCode = Readonly<{
  available: boolean;
  complete: boolean;
  status: string;
  value: string | null;
  rawValue: string | null;
  reason: string;
  summary: OmrStudentCodeSummary;
}>;

export type OmrQuestion = Readonly<{
  question: number;
  status: OmrQuestionStatus;
  selectedOption: string | null;
  selectedOptionNumber: number | null;
  selectedOptions: readonly string[];
  confidence: number;
}>;

export type OmrSheetResult = Readonly<{
  page: number;
  success: boolean;
  status: string;
  reviewRequired: boolean;
  reviewReasons: readonly string[];
  error: string;
  studentId: string | null;
  examCode: string | null;
  normalizedImagePath: string | null;
  qrCode: OmrMachineReadableCode | null;
  /** @deprecated Use qrCode. Kept by native schema v2 for old clients. */
  barcode: OmrMachineReadableCode;
  studentCode: OmrStudentCode;
  answerSummary: OmrAnswerSummary;
  selectedAnswers: Readonly<Record<string, number>>;
  questions: readonly OmrQuestion[];
  warnings: readonly string[];
  processingTimeMs: number;
}>;

export type OmrBatchResult = Readonly<{
  schemaVersion: typeof OMR_API_VERSION;
  success: boolean;
  status: OmrBatchStatus;
  examCode: string | null;
  examCodeSource: 'override' | 'qr_code' | 'barcode' | 'none';
  sheetCount: number;
  successfulSheetCount: number;
  failedSheetCount: number;
  reviewRequired: boolean;
  reviewSheetCount: number;
  compact: boolean;
  processingTimeMs: number;
  /** Present on ERROR envelopes for compatibility; prefer errors. */
  error?: string;
  errors: readonly string[];
  sheets: readonly OmrSheetResult[];
}>;

export interface OmrNativeBridge {
  readonly apiVersion: number;
  processJsonAsync(requestJson: string): Promise<string>;
}

export class OmrContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'OmrContractError';
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function requireField<T>(
  record: Record<string, unknown>,
  key: string,
  predicate: (value: unknown) => value is T,
): T {
  const value = record[key];
  if (!predicate(value)) {
    throw new OmrContractError(`invalid_result_field:${key}`);
  }
  return value;
}

const isBoolean = (value: unknown): value is boolean => typeof value === 'boolean';
const isNumber = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value);
const isString = (value: unknown): value is string => typeof value === 'string';
const isStringArray = (value: unknown): value is string[] => Array.isArray(value) && value.every(isString);

function validatePage(page: OmrPageInput, index: number): void {
  if (!page.imagePath.trim()) {
    throw new OmrContractError(`invalid_page_image_path:${index + 1}`);
  }

  const pageNumber = page.pageNumber ?? index + 1;
  if (!Number.isInteger(pageNumber) || pageNumber < 1) {
    throw new OmrContractError(`invalid_page_number:${pageNumber}`);
  }

  if (page.anchorPointsPercent !== undefined && page.anchors !== undefined) {
    throw new OmrContractError(`duplicate_anchor_formats:${pageNumber}`);
  }

  if (page.anchorPointsPercent !== undefined) {
    if (
      page.anchorPointsPercent.length !== 8 ||
      page.anchorPointsPercent.some((value) => !Number.isFinite(value) || value < 0 || value > 100)
    ) {
      throw new OmrContractError(`invalid_anchor_points:${pageNumber}`);
    }
  }

  if (page.anchors !== undefined) {
    if (
      page.anchors.length !== 4 ||
      page.anchors.some(({ x, y }) => !Number.isFinite(x) || !Number.isFinite(y) || x < 0 || x > 100 || y < 0 || y > 100)
    ) {
      throw new OmrContractError(`invalid_anchors:${pageNumber}`);
    }
  }
}

/** Applies production-safe mobile defaults and rejects bridge mistakes early. */
export function createOmrCoreRequest(input: OmrCoreRequest): OmrCoreRequest {
  if (!input.templateJson?.trim() && !input.templateJsonPath?.trim()) {
    throw new OmrContractError('template_missing');
  }
  if (!Array.isArray(input.pages) || input.pages.length === 0) {
    throw new OmrContractError('pages_empty');
  }

  input.pages.forEach(validatePage);
  const pageNumbers = input.pages.map((page, index) => page.pageNumber ?? index + 1);
  if (new Set(pageNumbers).size !== pageNumbers.length) {
    throw new OmrContractError('duplicate_page_number');
  }

  const maxInputDimension = input.maxInputDimension ?? 2600;
  if (!Number.isInteger(maxInputDimension) || maxInputDimension < 1800 || maxInputDimension > 4096) {
    throw new OmrContractError('max_input_dimension_out_of_range');
  }

  if (
    input.compactResult !== undefined &&
    input.includeQuestions !== undefined &&
    input.compactResult === input.includeQuestions
  ) {
    throw new OmrContractError('question_output_flags_conflict');
  }
  const includeQuestions = input.includeQuestions ?? (
    input.compactResult === undefined ? false : !input.compactResult
  );
  return {
    ...input,
    returnDebugImages: input.returnDebugImages ?? false,
    returnNormalizedImages: input.returnNormalizedImages ?? false,
    compactResult: !includeQuestions,
    includeQuestions,
    maxInputDimension,
  };
}

/** Parses and minimally validates the native boundary before app code consumes it. */
export function parseOmrBatchResult(json: string): OmrBatchResult {
  let value: unknown;
  try {
    value = JSON.parse(json);
  } catch {
    throw new OmrContractError('native_result_is_not_json');
  }
  if (!isRecord(value)) {
    throw new OmrContractError('native_result_is_not_an_object');
  }

  const schemaVersion = requireField(value, 'schemaVersion', isNumber);
  if (schemaVersion !== OMR_API_VERSION) {
    throw new OmrContractError(`unsupported_schema_version:${schemaVersion}`);
  }
  const status = requireField(value, 'status', isString);
  if (status !== 'OK' && status !== 'PARTIAL' && status !== 'ERROR') {
    throw new OmrContractError(`unsupported_batch_status:${status}`);
  }

  requireField(value, 'success', isBoolean);
  requireField(value, 'sheetCount', isNumber);
  requireField(value, 'successfulSheetCount', isNumber);
  requireField(value, 'failedSheetCount', isNumber);
  requireField(value, 'reviewRequired', isBoolean);
  requireField(value, 'reviewSheetCount', isNumber);
  requireField(value, 'compact', isBoolean);
  requireField(value, 'processingTimeMs', isNumber);
  requireField(value, 'errors', isStringArray);

  const sheets = value.sheets;
  if (!Array.isArray(sheets) || sheets.some((sheet) => !isRecord(sheet))) {
    throw new OmrContractError('invalid_result_field:sheets');
  }
  for (const sheet of sheets) {
    requireField(sheet, 'page', isNumber);
    requireField(sheet, 'success', isBoolean);
    requireField(sheet, 'reviewRequired', isBoolean);
    requireField(sheet, 'reviewReasons', isStringArray);
  }

  if (value.sheetCount !== sheets.length) {
    throw new OmrContractError('sheet_count_mismatch');
  }
  if (value.successfulSheetCount + value.failedSheetCount !== value.sheetCount) {
    throw new OmrContractError('sheet_outcome_count_mismatch');
  }

  return value as unknown as OmrBatchResult;
}

/** Calls the native core off the JS thread through an Expo/React Native bridge. */
export async function processOmrPages(
  nativeBridge: OmrNativeBridge,
  request: OmrCoreRequest,
): Promise<OmrBatchResult> {
  if (nativeBridge.apiVersion !== OMR_API_VERSION) {
    throw new OmrContractError(`unsupported_native_api_version:${nativeBridge.apiVersion}`);
  }
  const normalizedRequest = createOmrCoreRequest(request);
  return parseOmrBatchResult(await nativeBridge.processJsonAsync(JSON.stringify(normalizedRequest)));
}

/** Final results must be successful and pass every native review gate. */
export function canFinalizeOmrBatch(result: OmrBatchResult): boolean {
  return result.success &&
    result.status === 'OK' &&
    !result.reviewRequired &&
    result.sheets.every((sheet) => sheet.success && !sheet.reviewRequired);
}
