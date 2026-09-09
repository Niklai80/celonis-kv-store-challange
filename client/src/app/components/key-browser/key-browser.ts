import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { KvStoreService } from '../../services/kv-store.service';

@Component({
  selector: 'app-key-browser',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './key-browser.html',
  styleUrl: './key-browser.css',
})
export class KeyBrowserComponent {
  limit = 100;
  keys = signal<string[] | null>(null);
  loading = signal(false);
  error = signal<string | null>(null);

  constructor(private readonly kvStore: KvStoreService) {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.error.set(null);
    this.kvStore.listKeys(this.limit).subscribe({
      next: (response) => {
        this.loading.set(false);
        this.keys.set(response.keys);
      },
      error: (err) => {
        this.loading.set(false);
        this.keys.set(null);
        this.error.set(err?.error?.error ?? 'could not reach the server');
      },
    });
  }
}
