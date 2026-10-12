// Runtime configuration. In Docker, nginx writes /config.json at container start from
// the PAYMENTS_API_URL environment variable, so changing the URL only needs a container
// restart, not a rebuild. Under `npm run dev` there is no such file and the default below is used.
const defaults = {
  apiUrl: 'http://localhost:8080',
};

export async function loadConfig() {
  try {
    const res = await fetch('/config.json', { cache: 'no-store' });
    if (!res.ok) throw new Error(`config.json: HTTP ${res.status}`);
    return { ...defaults, ...(await res.json()) };
  } catch {
    return defaults;
  }
}
