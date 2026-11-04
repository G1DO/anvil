import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { App } from './App';

const container = document.getElementById('root');

if (container === null) {
  throw new Error('ANVIL console root element is missing');
}

const root = createRoot(container);

// Render synchronously so the accessible shell exists as soon as the deferred
// classic script finishes, before any browser automation inspects the page.
flushSync(() => {
  root.render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
});
