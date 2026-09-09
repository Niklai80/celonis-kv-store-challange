import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('defaults to the operations tab', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance.activeTab()).toBe('operations');
  });

  it('switches tabs via setTab', () => {
    const fixture = TestBed.createComponent(App);
    fixture.componentInstance.setTab('cluster');
    expect(fixture.componentInstance.activeTab()).toBe('cluster');
  });
});
