import { Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { KvStoreService } from '../../services/kv-store.service';
import { NodeStatsResponse } from '../../models/api.models';

@Component({
  selector: 'app-cluster-view',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './cluster-view.html',
  styleUrl: './cluster-view.css',
})
export class ClusterViewComponent {
  nodes = signal<NodeStatsResponse[] | null>(null);
  loading = signal(false);
  error = signal<string | null>(null);

  lookupKey = '';
  ownerShardIndex = signal<number | null>(null);
  lookupError = signal<string | null>(null);

  // A derived value, not a stored field - it always reflects the current nodes()/ownerShardIndex()
  // pair, so there's no way for it to go stale the way a manually-updated field could.
  sortedNodes = computed(() => {
    const nodes = this.nodes();
    return nodes ? [...nodes].sort((a, b) => a.shardIndex - b.shardIndex) : null;
  });

  constructor(private readonly kvStore: KvStoreService) {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.error.set(null);
    this.kvStore.clusterStats().subscribe({
      next: (nodes) => {
        this.loading.set(false);
        this.nodes.set(nodes);
      },
      error: (err) => {
        this.loading.set(false);
        this.nodes.set(null);
        this.error.set(err?.error?.error ?? 'could not reach the server');
      },
    });
  }

  lookupOwner(): void {
    if (!this.lookupKey) {
      return;
    }
    this.lookupError.set(null);
    this.kvStore.ownerOf(this.lookupKey).subscribe({
      next: (response) => this.ownerShardIndex.set(response.shardIndex),
      error: (err) => {
        this.ownerShardIndex.set(null);
        this.lookupError.set(err?.error?.error ?? 'could not reach the server');
      },
    });
  }

  heapPercent(node: NodeStatsResponse): number {
    return node.heapMaxBytes > 0 ? Math.round((node.heapUsedBytes / node.heapMaxBytes) * 100) : 0;
  }

  formatBytes(bytes: number): string {
    const mb = bytes / (1024 * 1024);
    return `${mb.toFixed(1)} MB`;
  }
}
