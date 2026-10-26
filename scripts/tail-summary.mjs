// Never publish request objects/headers: traces may contain Authorization tokens.
import { readFileSync } from 'node:fs';
const input = readFileSync(process.argv[2], 'utf8');
let depth = 0, start = -1, quoted = false, escaped = false;
for (let i = 0; i < input.length; i++) {
  const char = input[i];
  if (quoted) { if (escaped) escaped = false; else if (char === '\\') escaped = true; else if (char === '"') quoted = false; continue; }
  if (char === '"') { quoted = true; continue; }
  if (char === '{') { if (depth++ === 0) start = i; }
  else if (char === '}' && --depth === 0 && start >= 0) {
    try {
      const event = JSON.parse(input.slice(start, i + 1));
      for (const error of event.exceptions || []) console.log('Worker exception:', error.name, error.message);
      for (const log of event.logs || []) if (log.level === 'error') console.log('Worker error:', ...(log.message || []));
    } catch { /* Ignore non-JSON connection status lines. */ }
    start = -1;
  }
}
