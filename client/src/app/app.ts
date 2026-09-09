import { Component, signal } from '@angular/core';
import { KeyFormComponent } from './components/key-form/key-form';
import { KeyBrowserComponent } from './components/key-browser/key-browser';
import { ClusterViewComponent } from './components/cluster-view/cluster-view';

type Tab = 'operations' | 'browser' | 'cluster';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [KeyFormComponent, KeyBrowserComponent, ClusterViewComponent],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  // A signal, not a plain field - the template reads it reactively (activeTab() in [class.active]
  // and the @switch below), so switching tabs re-renders without any manual change detection call.
  activeTab = signal<Tab>('operations');

  setTab(tab: Tab): void {
    this.activeTab.set(tab);
  }
}
