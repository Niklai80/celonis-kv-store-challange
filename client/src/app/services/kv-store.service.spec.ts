import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { KvStoreService } from './kv-store.service';

describe('KvStoreService', () => {
  let service: KvStoreService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(KvStoreService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('maps a 201 response to created', () => {
    let result: any;
    service.put('key', 'value').subscribe((r) => (result = r));

    const req = httpMock.expectOne((r) => r.url === '/api/v1/keys' && r.params.get('key') === 'key');
    expect(req.request.method).toBe('PUT');
    req.flush(null, { status: 201, statusText: 'Created' });

    expect(result).toEqual({ kind: 'created' });
  });

  it('maps a 200 response to updated', () => {
    let result: any;
    service.put('key', 'value').subscribe((r) => (result = r));

    const req = httpMock.expectOne((r) => r.url === '/api/v1/keys');
    req.flush(null, { status: 200, statusText: 'OK' });

    expect(result).toEqual({ kind: 'updated' });
  });

  it('maps a 507 response to capacityExceeded with the reason body', () => {
    let result: any;
    service.put('key', 'value').subscribe((r) => (result = r));

    const req = httpMock.expectOne((r) => r.url === '/api/v1/keys');
    req.flush('node entry limit reached: 2', { status: 507, statusText: 'Insufficient Storage' });

    expect(result).toEqual({ kind: 'capacityExceeded', reason: 'node entry limit reached: 2' });
  });

  it('maps a 404 GET response to notFound', () => {
    let result: any;
    service.get('missing').subscribe((r) => (result = r));

    const req = httpMock.expectOne((r) => r.url === '/api/v1/keys/lookup' && r.params.get('key') === 'missing');
    req.flush('not found', { status: 404, statusText: 'Not Found' });

    expect(result).toEqual({ kind: 'notFound' });
  });

  it('maps a 200 GET response to found with its body', () => {
    let result: any;
    service.get('key').subscribe((r) => (result = r));

    const req = httpMock.expectOne((r) => r.url === '/api/v1/keys/lookup');
    req.flush('the-value', { status: 200, statusText: 'OK' });

    expect(result).toEqual({ kind: 'found', value: 'the-value' });
  });

  it('maps a network failure (status 0) to a readable error', () => {
    let result: any;
    service.get('key').subscribe((r) => (result = r));

    const req = httpMock.expectOne((r) => r.url === '/api/v1/keys/lookup');
    req.error(new ProgressEvent('network error'), { status: 0, statusText: 'Unknown Error' });

    expect(result.kind).toBe('error');
    expect(result.message).toContain('could not reach the server');
  });
});
