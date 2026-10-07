import { HttpErrorResponse } from '@angular/common/http';

import { ErrorResponse } from './admin-api.models';

export class ApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error;
  }
  if (error instanceof HttpErrorResponse) {
    const response = isErrorResponse(error.error) ? error.error : null;
    return new ApiError(
      response?.error ?? 'request_failed',
      response?.message ?? networkMessage(error.status),
      error.status,
    );
  }
  return new ApiError('unexpected_error', 'An unexpected error occurred.', 0);
}

function isErrorResponse(value: unknown): value is ErrorResponse {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<ErrorResponse>;
  return typeof candidate.error === 'string' && typeof candidate.message === 'string';
}

function networkMessage(status: number): string {
  return status === 0
    ? 'The banking API is unavailable. Make sure the Scalatra server is running.'
    : 'The admin request could not be completed.';
}
