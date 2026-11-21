import assert from 'node:assert/strict';
import test from 'node:test';

import {
  OMR_API_VERSION,
  OmrContractError,
  canFinalizeOmrBatch,
  createOmrCoreRequest,
  parseOmrBatchResult,
  processOmrPages,
} from './omr-mobile.ts';

const successfulResult = {
  schemaVersion: OMR_API_VERSION,
  success: true,
  status: 'OK',
  examCode: 'EXAM-123',
  examCodeSource: 'qr_code',
  sheetCount: 1,
  successfulSheetCount: 1,
  failedSheetCount: 0,
  reviewRequired: false,
  reviewSheetCount: 0,
  compact: true,
  processingTimeMs: 120,
  errors: [],
  sheets: [{ page: 1, success: true, reviewRequired: false, reviewReasons: [] }],
};

test('mobile request uses low-I/O compact defaults', () => {
  const request = createOmrCoreRequest({
    templateJsonPath: '/app/template.json',
    pages: [{ imagePath: '/app/capture.jpg' }],
  });

  assert.equal(request.maxInputDimension, 2600);
  assert.equal(request.compactResult, true);
  assert.equal(request.includeQuestions, false);
  assert.equal(request.returnDebugImages, false);
  assert.equal(request.returnNormalizedImages, false);
});

test('request validation rejects duplicate pages and malformed anchors', () => {
  assert.throws(
    () => createOmrCoreRequest({
      templateJsonPath: '/app/template.json',
      pages: [
        { pageNumber: 1, imagePath: '/app/one.jpg' },
        { pageNumber: 1, imagePath: '/app/two.jpg' },
      ],
    }),
    (error) => error instanceof OmrContractError && error.message === 'duplicate_page_number',
  );

  assert.throws(
    () => createOmrCoreRequest({
      templateJsonPath: '/app/template.json',
      pages: [{ imagePath: '/app/one.jpg', anchorPointsPercent: [1, 2] }],
    }),
    /invalid_anchor_points/,
  );

  assert.throws(
    () => createOmrCoreRequest({
      templateJsonPath: '/app/template.json',
      pages: [{
        imagePath: '/app/one.jpg',
        anchorPointsPercent: [1, 1, 99, 1, 99, 99, 1, 99],
        anchors: [{ x: 1, y: 1 }, { x: 99, y: 1 }, { x: 99, y: 99 }, { x: 1, y: 99 }],
      }],
    }),
    /duplicate_anchor_formats/,
  );

  assert.throws(
    () => createOmrCoreRequest({
      templateJsonPath: '/app/template.json',
      compactResult: true,
      includeQuestions: true,
      pages: [{ imagePath: '/app/one.jpg' }],
    }),
    /question_output_flags_conflict/,
  );
});

test('explicit full question output remains available for review UI', () => {
  const request = createOmrCoreRequest({
    templateJsonPath: '/app/template.json',
    compactResult: false,
    pages: [{ imagePath: '/app/one.jpg' }],
  });

  assert.equal(request.compactResult, false);
  assert.equal(request.includeQuestions, true);
});

test('native response parser enforces schema and aggregate counts', () => {
  const parsed = parseOmrBatchResult(JSON.stringify(successfulResult));
  assert.equal(canFinalizeOmrBatch(parsed), true);

  assert.throws(
    () => parseOmrBatchResult(JSON.stringify({ ...successfulResult, schemaVersion: 3 })),
    /unsupported_schema_version:3/,
  );
  assert.throws(
    () => parseOmrBatchResult(JSON.stringify({ ...successfulResult, failedSheetCount: 1 })),
    /sheet_outcome_count_mismatch/,
  );
});

test('bridge adapter serializes safe defaults before the native call', async () => {
  let receivedRequest;
  const result = await processOmrPages(
    {
      apiVersion: OMR_API_VERSION,
      async processJsonAsync(requestJson) {
        receivedRequest = JSON.parse(requestJson);
        return JSON.stringify(successfulResult);
      },
    },
    {
      templateJsonPath: '/app/template.json',
      pages: [{ imagePath: '/app/capture.jpg' }],
    },
  );

  assert.equal(receivedRequest.compactResult, true);
  assert.equal(receivedRequest.returnNormalizedImages, false);
  assert.equal(canFinalizeOmrBatch(result), true);
});
