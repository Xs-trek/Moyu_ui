const sizes = Object.freeze({
  360: '360 × 800',
  390: '390 × 844',
  412: '412 × 915',
  430: '430 × 932',
  768: '768 × 1024'
});

const frame = document.querySelector('#device-frame');
const label = document.querySelector('#viewport-label');
const iframe = frame.querySelector('iframe');
const pageParams = new URLSearchParams(window.location.search);
const previewParams = new URLSearchParams();
['fixture', 'theme', 'route'].forEach((key) => {
  const value = pageParams.get(key);
  if (value) previewParams.set(key, value);
});
if ([...previewParams].length) iframe.setAttribute('src', `./preview.html?${previewParams}`);

function applyViewport(viewport) {
  if (!sizes[viewport]) return;
  frame.className = `device-frame viewport-${viewport}`;
  label.textContent = sizes[viewport];
  document.querySelectorAll('[data-viewport]').forEach((button) => {
    button.setAttribute('aria-pressed', String(button.dataset.viewport === viewport));
  });
}

document.querySelectorAll('[data-viewport]').forEach((control) => {
  control.addEventListener('click', () => applyViewport(control.dataset.viewport));
});

applyViewport(pageParams.get('viewport') || '390');
