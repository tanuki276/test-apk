// Fixed UTC daily budgets count attempts, including provider failures and cancellation.
// A single Durable Object serializes admission across locations and isolates.
export class GenerationBudget {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "Invalid request" }, 400);
    }

    const uid = typeof body?.uid === "string" ? body.uid : "";
    const guest = body?.guest === true;
    const mode = body?.mode === "high" ? "high" : "normal";
    if (!uid || uid.length > 128) return json({ error: "Invalid user" }, 400);

    const url = new URL(request.url);
    if (url.pathname === "/status") {
      return json(await this.status(uid, guest));
    }
    if (url.pathname !== "/admit") return json({ error: "Not found" }, 404);

    if (guest && mode === "high") {
      return json({ error: "高精度モードはログイン後に利用できます。" }, 403);
    }

    const now = Date.now();
    const day = Math.floor(now / 86400000);
    const limits = this.limits(guest);
    const result = await this.state.storage.transaction(async storage => {
      let total = await storage.get("budget-v2") || { day, count: 0, users: {} };
      if (total.day !== day) total = { day, count: 0, users: {} };

      const key = `user:${uid}`;
      const user = total.users[key] || { normal: 0, high: 0, last: 0 };
      const selectedLimit = mode === "high" ? limits.high : limits.normal;
      const selectedCount = mode === "high" ? user.high : user.normal;

      if (selectedLimit <= 0) return { allowed: false, reason: "mode" };
      if (total.count >= this.cap("GLOBAL_DAILY_LIMIT", 500)) return { allowed: false, reason: "global" };
      if (selectedCount >= selectedLimit) return { allowed: false, reason: "daily" };
      if (now - Number(user.last || 0) < 15000) return { allowed: false, reason: "cooldown" };

      total.count++;
      if (mode === "high") user.high++;
      else user.normal++;
      user.last = now;
      total.users[key] = user;
      await storage.put("budget-v2", total);
      return { allowed: true };
    });

    if (result.allowed) return new Response(null, { status: 204 });
    if (result.reason === "mode") return json({ error: "高精度モードはログイン後に利用できます。" }, 403);
    const message = result.reason === "daily"
      ? (mode === "high" ? "本日の高精度モード利用上限に達しました。" : "本日の通常生成の利用上限に達しました。")
      : "少し待ってから再試行してください。";
    return json({ error: message }, 429, { "Retry-After": result.reason === "cooldown" ? "15" : "60" });
  }

  limits(guest) {
    return {
      normal: guest ? this.cap("GUEST_NORMAL_DAILY_LIMIT", 5) : this.cap("USER_NORMAL_DAILY_LIMIT", 20),
      high: guest ? 0 : this.cap("USER_HIGH_DAILY_LIMIT", 10),
    };
  }

  cap(name, fallback) {
    const value = Number(this.env[name]);
    return Number.isSafeInteger(value) && value >= 0 ? value : fallback;
  }

  async status(uid, guest) {
    const now = Date.now();
    const day = Math.floor(now / 86400000);
    const limits = this.limits(guest);
    let total = await this.state.storage.get("budget-v2") || { day, count: 0, users: {} };
    if (total.day !== day) total = { day, count: 0, users: {} };
    const user = total.users[`user:${uid}`] || { normal: 0, high: 0, last: 0 };
    const normalUsed = Math.max(0, Number(user.normal || 0));
    const highUsed = Math.max(0, Number(user.high || 0));
    return {
      guest,
      day,
      normal: {
        used: normalUsed,
        limit: limits.normal,
        remaining: Math.max(0, limits.normal - normalUsed),
      },
      highAccuracy: {
        available: !guest,
        used: highUsed,
        limit: limits.high,
        remaining: Math.max(0, limits.high - highUsed),
      },
    };
  }
}

function json(body, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      ...extraHeaders,
    },
  });
}
