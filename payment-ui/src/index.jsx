import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import { loadConfig } from './config.js';
import stylesUrl from './styles.css';

// styles.css is emitted as a separate file (webpack asset/resource), so the strict
// Content-Security-Policy needs no inline styles.
const link = document.createElement('link');
link.rel = 'stylesheet';
link.href = stylesUrl;
document.head.appendChild(link);

// Load runtime config first, then render.
loadConfig().then((config) => {
  createRoot(document.getElementById('root')).render(<App config={config} />);
});
