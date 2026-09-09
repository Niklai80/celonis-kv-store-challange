import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DeleteOutcome, GetOutcome, KvStoreService, PutOutcome } from '../../services/kv-store.service';

@Component({
  selector: 'app-key-form',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './key-form.html',
  styleUrl: './key-form.css',
})
export class KeyFormComponent {
  putKey = '';
  putValue = '';
  putStatus = signal<PutOutcome | null>(null);
  putBusy = signal(false);

  getKey = '';
  getStatus = signal<GetOutcome | null>(null);
  getBusy = signal(false);

  deleteKey = '';
  deleteStatus = signal<DeleteOutcome | null>(null);
  deleteBusy = signal(false);

  constructor(private readonly kvStore: KvStoreService) {}

  submitPut(): void {
    if (!this.putKey) {
      return;
    }
    this.putBusy.set(true);
    this.putStatus.set(null);
    this.kvStore.put(this.putKey, this.putValue).subscribe((outcome) => {
      this.putBusy.set(false);
      this.putStatus.set(outcome);
    });
  }

  submitGet(): void {
    if (!this.getKey) {
      return;
    }
    this.getBusy.set(true);
    this.getStatus.set(null);
    this.kvStore.get(this.getKey).subscribe((outcome) => {
      this.getBusy.set(false);
      this.getStatus.set(outcome);
    });
  }

  submitDelete(): void {
    if (!this.deleteKey) {
      return;
    }
    this.deleteBusy.set(true);
    this.deleteStatus.set(null);
    this.kvStore.delete(this.deleteKey).subscribe((outcome) => {
      this.deleteBusy.set(false);
      this.deleteStatus.set(outcome);
    });
  }
}
