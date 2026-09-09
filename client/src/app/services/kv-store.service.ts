import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { catchError, map, Observable, of } from 'rxjs';
import { KeysResponse, NodeStatsResponse, OwnerResponse } from '../models/api.models';

// Mirrors StoreResult on the server (see StoreResult.java) as a discriminated union instead of
// a sealed interface - same idea (make every caller handle every case explicitly), TypeScript's
// version of it.
export type PutOutcome =
  | { kind: 'created' }
  | { kind: 'updated' }
  | { kind: 'capacityExceeded'; reason: string }
  | { kind: 'error'; message: string };

export type GetOutcome = { kind: 'found'; value: string } | { kind: 'notFound' } | { kind: 'error'; message: string };

export type DeleteOutcome = { kind: 'deleted' } | { kind: 'notFound' } | { kind: 'error'; message: string };

@Injectable({ providedIn: 'root' })
export class KvStoreService {
  // Relative, not absolute - the same origin serves both the UI and /api, via the Ingress in
  // Kubernetes (see k8s/ingress.yaml) or the nginx reverse proxy in the compose/production
  // container (see client/nginx.conf). This is what avoids needing CORS at all.
  private readonly base = '/api/v1';

  constructor(private readonly http: HttpClient) {}

  put(key: string, value: string): Observable<PutOutcome> {
    return this.http
      .put(`${this.base}/keys`, value, {
        params: { key },
        headers: { 'Content-Type': 'text/plain' },
        observe: 'response',
        responseType: 'text',
      })
      .pipe(
        map((response) => (response.status === 201 ? { kind: 'created' as const } : { kind: 'updated' as const })),
        catchError((err: HttpErrorResponse) =>
          of(
            err.status === 507
              ? { kind: 'capacityExceeded' as const, reason: err.error ?? 'node entry limit reached' }
              : { kind: 'error' as const, message: describeError(err) },
          ),
        ),
      );
  }

  get(key: string): Observable<GetOutcome> {
    return this.http.get(`${this.base}/keys/lookup`, { params: { key }, responseType: 'text' }).pipe(
      map((value) => ({ kind: 'found' as const, value })),
      catchError((err: HttpErrorResponse) =>
        of(
          err.status === 404
            ? { kind: 'notFound' as const }
            : { kind: 'error' as const, message: describeError(err) },
        ),
      ),
    );
  }

  delete(key: string): Observable<DeleteOutcome> {
    return this.http.delete(`${this.base}/keys`, { params: { key }, observe: 'response' }).pipe(
      map(() => ({ kind: 'deleted' as const })),
      catchError((err: HttpErrorResponse) =>
        of(
          err.status === 404
            ? { kind: 'notFound' as const }
            : { kind: 'error' as const, message: describeError(err) },
        ),
      ),
    );
  }

  listKeys(limit: number): Observable<KeysResponse> {
    return this.http.get<KeysResponse>(`${this.base}/keys`, { params: { limit } });
  }

  nodeStats(): Observable<NodeStatsResponse> {
    return this.http.get<NodeStatsResponse>(`${this.base}/stats`);
  }

  clusterStats(): Observable<NodeStatsResponse[]> {
    return this.http.get<NodeStatsResponse[]>(`${this.base}/cluster/stats`);
  }

  ownerOf(key: string): Observable<OwnerResponse> {
    return this.http.get<OwnerResponse>(`${this.base}/keys/owner`, { params: { key } });
  }
}

function describeError(err: HttpErrorResponse): string {
  if (err.status === 0) {
    return 'could not reach the server';
  }
  const body = typeof err.error === 'string' ? err.error : err.error?.error;
  return body || `unexpected error (HTTP ${err.status})`;
}
