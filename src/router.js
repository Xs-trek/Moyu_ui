import { sendIntent } from './bridge.js';
import { update } from './state.js';

export const routes = ['console', 'conversation', 'sessions', 'nodes', 'accounts', 'settings', 'diagnostics'];

export function openRoute(route, options = {}) {
  if (!routes.includes(route)) return;
  update((next) => { next.route = route; });
  if (options.notifyHost !== false) sendIntent('nav.open', { route });
  if (options.scroll !== false) window.scrollTo(0, 0);
  document.querySelector('#main-content')?.focus({ preventScroll: true });
}
