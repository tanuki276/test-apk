// Fixed UTC daily budgets count attempts, including provider failures and cancellation.
// A single Durable Object serializes admission across locations and isolates.
export class GenerationBudget {
  constructor(state, env) { this.state = state; this.env = env; }
  async fetch(request) {
    const { uid } = await request.json();
    if (typeof uid !== "string" || !uid || uid.length > 128) return new Response(null, { status: 400 });
    const now = Date.now();
    const day = Math.floor(now / 86400000);
    const cap = (name, fallback) => {
      const value = Number(this.env[name]);
      return Number.isSafeInteger(value) && value > 0 ? value : fallback;
    };
    const allowed = await this.state.storage.transaction(async storage => {
      let total = await storage.get("budget") || { day, count: 0, users: {} };
      if (total.day !== day) total = { day, count: 0, users: {} };
      const key = `user:${uid}`;
      const user = total.users[key] || { count: 0, last: 0 };
      if (total.count >= cap("GLOBAL_DAILY_LIMIT", 500) ||
          user.count >= cap("USER_DAILY_LIMIT", 20) || now - user.last < 15000) return false;
      total.count++;
      total.users[key] = { count: user.count + 1, last: now };
      await storage.put("budget", total);
      return true;
    });
    if (!allowed) return new Response(JSON.stringify({ error: "少し待ってから再試行してください。" }), {
      status: 429, headers: { "Content-Type": "application/json", "Retry-After": "60", "Cache-Control": "no-store" },
    });
    return new Response(null, { status: 204 });
  }
}
